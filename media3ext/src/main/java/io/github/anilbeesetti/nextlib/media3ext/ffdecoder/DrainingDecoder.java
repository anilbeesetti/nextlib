/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;

import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * Buffer-pool worker adapted from Media3 SimpleDecoder for codecs with delayed output.
 * Unlike SimpleDecoder, an input may produce no output, and EOS is decoded repeatedly
 * until the codec has emitted all delayed frames and an EOS output buffer.
 */
@SuppressWarnings("UngroupedOverloads")
@UnstableApi
abstract class DrainingDecoder<
        I extends DecoderInputBuffer, O extends DecoderOutputBuffer, E extends DecoderException>
    implements Decoder<I, O, E> {

  private final Thread decodeThread;

  private final Object lock;
  private final ArrayDeque<I> queuedInputBuffers;
  private final ArrayDeque<O> queuedOutputBuffers;
  private final I[] availableInputBuffers;
  private final O[] availableOutputBuffers;

  private int availableInputBufferCount;
  private int availableOutputBufferCount;
  @Nullable private I dequeuedInputBuffer;

  @Nullable private E exception;
  private boolean flushed;
  private boolean released;
  private int skippedOutputBufferCount;
  private long outputStartTimeUs;

  @Nullable private Executor executor;
  @Nullable private Runnable onOutputBufferAvailableRunnable;
  @Nullable private Runnable onInputBufferAvailableRunnable;

  /**
   * @param inputBuffers An array of nulls that will be used to store references to input buffers.
   * @param outputBuffers An array of nulls that will be used to store references to output buffers.
   */
  @SuppressWarnings("nullness:method.invocation")
  protected DrainingDecoder(I[] inputBuffers, O[] outputBuffers) {
    lock = new Object();
    outputStartTimeUs = C.TIME_UNSET;
    queuedInputBuffers = new ArrayDeque<>();
    queuedOutputBuffers = new ArrayDeque<>();
    availableInputBuffers = inputBuffers;
    availableInputBufferCount = inputBuffers.length;
    for (int i = 0; i < availableInputBufferCount; i++) {
      availableInputBuffers[i] = createInputBuffer();
    }
    availableOutputBuffers = outputBuffers;
    availableOutputBufferCount = outputBuffers.length;
    for (int i = 0; i < availableOutputBufferCount; i++) {
      availableOutputBuffers[i] = createOutputBuffer();
    }
    decodeThread =
        new Thread("Nextlib:DrainingDecoder") {
          @Override
          public void run() {
            DrainingDecoder.this.run();
          }
        };
    decodeThread.start();
  }

  /**
   * Sets the initial size of each input buffer.
   *
   * <p>This method should only be called before the decoder is used (i.e. before the first call to
   * {@link #dequeueInputBuffer()}.
   *
   * @param size The required input buffer size.
   */
  protected final void setInitialInputBufferSize(int size) {
    checkState(availableInputBufferCount == availableInputBuffers.length);
    for (I inputBuffer : availableInputBuffers) {
      inputBuffer.ensureSpaceForWrite(size);
    }
  }

  /**
   * Returns whether a sample time is greater or equal to the {@link #setOutputStartTimeUs output
   * start time}, if set.
   *
   * <p>If this method returns false, the buffer will not be made available as an output buffer.
   *
   * @param timeUs The buffer time, in microseconds.
   * @return Whether the buffer time is greater or equal to the output start time, or {@code true}
   *     if the output start time is not set.
   */
  protected final boolean isAtLeastOutputStartTimeUs(long timeUs) {
    synchronized (lock) {
      return outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs;
    }
  }

  @Override
  public final void setOutputStartTimeUs(long outputStartTimeUs) {
    synchronized (lock) {
      checkState(availableInputBufferCount == availableInputBuffers.length || flushed);
      this.outputStartTimeUs = outputStartTimeUs;
    }
  }

  @Override
  public final void setCallback(Callback callback, Executor executor) {
    synchronized (lock) {
      this.executor = executor;
      this.onOutputBufferAvailableRunnable =
          callback != null ? callback::onOutputBufferAvailable : null;
      this.onInputBufferAvailableRunnable =
          callback != null ? callback::onInputBufferAvailable : null;
    }
  }

  @Override
  @Nullable
  public final I dequeueInputBuffer() throws E {
    synchronized (lock) {
      maybeThrowException();
      checkState(dequeuedInputBuffer == null);
      dequeuedInputBuffer =
          availableInputBufferCount == 0
              ? null
              : availableInputBuffers[--availableInputBufferCount];
      return dequeuedInputBuffer;
    }
  }

  @Override
  public final void queueInputBuffer(I inputBuffer) throws E {
    synchronized (lock) {
      maybeThrowException();
      checkArgument(inputBuffer == dequeuedInputBuffer);
      queuedInputBuffers.addLast(inputBuffer);
      maybeNotifyDecodeLoop();
      dequeuedInputBuffer = null;
    }
  }

  @Override
  @Nullable
  public final O dequeueOutputBuffer() throws E {
    synchronized (lock) {
      maybeThrowException();
      if (queuedOutputBuffers.isEmpty()) {
        return null;
      }
      return queuedOutputBuffers.removeFirst();
    }
  }

  /**
   * Releases an output buffer back to the decoder.
   *
   * @param outputBuffer The output buffer being released.
   */
  @CallSuper
  protected void releaseOutputBuffer(O outputBuffer) {
    synchronized (lock) {
      releaseOutputBufferInternal(outputBuffer);
      maybeNotifyDecodeLoop();
    }
  }

  @Override
  public final void flush() {
    synchronized (lock) {
      flushed = true;
      skippedOutputBufferCount = 0;
      if (dequeuedInputBuffer != null) {
        releaseInputBufferInternal(dequeuedInputBuffer, /* notifyCallback= */ false);
        dequeuedInputBuffer = null;
      }
      while (!queuedInputBuffers.isEmpty()) {
        releaseInputBufferInternal(queuedInputBuffers.removeFirst(), /* notifyCallback= */ false);
      }
      while (!queuedOutputBuffers.isEmpty()) {
        queuedOutputBuffers.removeFirst().release();
      }
    }
  }

  @CallSuper
  @Override
  public void release() {
    synchronized (lock) {
      released = true;
      lock.notify();
    }
    try {
      decodeThread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Throws a decode exception, if there is one.
   *
   * @throws E The decode exception.
   */
  private void maybeThrowException() throws E {
    @Nullable E exception = this.exception;
    if (exception != null) {
      throw exception;
    }
  }

  /**
   * Notifies the decode loop if there exists a queued input buffer and an available output buffer
   * to decode into.
   *
   * <p>Should only be called whilst synchronized on the lock object.
   */
  private void maybeNotifyDecodeLoop() {
    if (canDecodeBuffer()) {
      lock.notify();
    }
  }

  private void run() {
    try {
      while (decode()) {
        // Do nothing.
      }
    } catch (InterruptedException e) {
      // Not expected.
      throw new IllegalStateException(e);
    }
  }

  private boolean decode() throws InterruptedException {
    I inputBuffer;
    O outputBuffer;
    boolean resetDecoder;

    // Wait until we have an input buffer to decode, and an output buffer to decode into.
    synchronized (lock) {
      while (!released && !canDecodeBuffer()) {
        lock.wait();
      }
      if (released) {
        return false;
      }
      inputBuffer = queuedInputBuffers.removeFirst();
      outputBuffer = availableOutputBuffers[--availableOutputBufferCount];
      resetDecoder = flushed;
      flushed = false;
    }

    boolean hasOutput;
    try {
      hasOutput = decode(inputBuffer, outputBuffer, resetDecoder);
    } catch (DecoderException | RuntimeException | OutOfMemoryError e) {
      synchronized (lock) {
        exception = createUnexpectedDecodeException(e);
        outputBuffer.release();
        releaseInputBufferInternal(inputBuffer, /* notifyCallback= */ false);
      }
      return false;
    }

    synchronized (lock) {
      // Save this before release()/clear(), including when flush races with decoding.
      boolean keepDraining = inputBuffer.isEndOfStream() && !outputBuffer.isEndOfStream();
      if (flushed || !hasOutput) {
        outputBuffer.release();
      } else if (outputBuffer.shouldBeSkipped
          || (!outputBuffer.isEndOfStream() && !isAtLeastOutputStartTimeUs(outputBuffer.timeUs))) {
        skippedOutputBufferCount++;
        outputBuffer.release();
      } else {
        outputBuffer.skippedOutputBufferCount = skippedOutputBufferCount;
        skippedOutputBufferCount = 0;
        queuedOutputBuffers.addLast(outputBuffer);
        Runnable currentOnOutputBufferAvailableRunnable = this.onOutputBufferAvailableRunnable;
        Executor currentExecutor = this.executor;
        if (currentOnOutputBufferAvailableRunnable != null && currentExecutor != null) {
          currentExecutor.execute(currentOnOutputBufferAvailableRunnable);
        }
      }
      if (keepDraining && !flushed) {
        // Retain EOS until the codec reports EOF. Output-pool exhaustion supplies backpressure.
        queuedInputBuffers.addFirst(inputBuffer);
      } else {
        releaseInputBufferInternal(inputBuffer, /* notifyCallback= */ true);
      }
    }

    return true;
  }

  private boolean canDecodeBuffer() {
    return !queuedInputBuffers.isEmpty() && availableOutputBufferCount > 0;
  }

  private void releaseInputBufferInternal(I inputBuffer, boolean notifyCallback) {
    inputBuffer.clear();
    availableInputBuffers[availableInputBufferCount++] = inputBuffer;
    Runnable currentOnInputBufferAvailableRunnable = this.onInputBufferAvailableRunnable;
    Executor currentExecutor = this.executor;
    if (notifyCallback
        && currentOnInputBufferAvailableRunnable != null
        && currentExecutor != null) {
      currentExecutor.execute(currentOnInputBufferAvailableRunnable);
    }
  }

  private void releaseOutputBufferInternal(O outputBuffer) {
    outputBuffer.clear();
    availableOutputBuffers[availableOutputBufferCount++] = outputBuffer;
  }

  /** Creates a new input buffer. */
  protected abstract I createInputBuffer();

  /** Creates a new output buffer. */
  protected abstract O createOutputBuffer();

  /**
   * Creates an exception to propagate for an unexpected decode error.
   *
   * @param error The unexpected decode error.
   * @return The exception to propagate.
   */
  protected abstract E createUnexpectedDecodeException(Throwable error);

  /**
   * Submits input and receives one output, or drains one frame when input is EOS.
   * Returns false when the codec needs more input. An EOS input will be passed again
   * until an output with BUFFER_FLAG_END_OF_STREAM is returned. Reset must discard
   * all codec state from before flush, including any partially completed drain.
   */
  protected abstract boolean decode(I inputBuffer, O outputBuffer, boolean reset) throws E;
}
