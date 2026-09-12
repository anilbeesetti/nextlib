package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

@UnstableApi
public class FfmpegVideoRendererTest {
    private static final Format VP9 = new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(64).setHeight(48).build();

    private static FfmpegVideoRenderer renderer() {
        return new FfmpegVideoRenderer(0, null, null, 0, 1, 1, 1);
    }

    @Test
    public void capabilitiesRejectMissingUnsupportedAndEncryptedFormats() {
        assertTrue("Tests require the bundled native decoder", FfmpegLibrary.isAvailable());
        FfmpegVideoRenderer renderer = renderer();
        assertSupport(renderer, new Format.Builder().build(), C.FORMAT_UNSUPPORTED_TYPE);
        assertSupport(renderer, new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
                C.FORMAT_UNSUPPORTED_TYPE);
        assertSupport(renderer, new Format.Builder().setSampleMimeType("video/unsupported").build(),
                C.FORMAT_UNSUPPORTED_SUBTYPE);
        assertSupport(renderer, VP9, C.FORMAT_HANDLED);
        for (int cryptoType : new int[] {C.CRYPTO_TYPE_UNSUPPORTED, C.CRYPTO_TYPE_FRAMEWORK,
                C.CRYPTO_TYPE_CUSTOM_BASE}) {
            Format encrypted = VP9.buildUpon().setCryptoType(cryptoType).build();
            assertNull(encrypted.drmInitData);
            assertSupport(renderer, encrypted, C.FORMAT_UNSUPPORTED_DRM);
        }
        Format drm = VP9.buildUpon().setDrmInitData(new DrmInitData(
                new DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", new byte[] {1}))).build();
        assertEquals(C.CRYPTO_TYPE_UNSUPPORTED, drm.cryptoType);
        assertSupport(renderer, drm, C.FORMAT_UNSUPPORTED_DRM);
    }

    @Test
    public void adaptationIsNotSeamlessAndKeepsInheritedNoReuse() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        int capabilities = renderer.supportsFormat(VP9);
        assertEquals(RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
                RendererCapabilities.getAdaptiveSupport(capabilities));
        assertEquals(RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                RendererCapabilities.getTunnelingSupport(capabilities));
        Method reuse = DecoderVideoRenderer.class.getDeclaredMethod(
                "canReuseDecoder", String.class, Format.class, Format.class);
        reuse.setAccessible(true);
        for (Format next : new Format[] {VP9, VP9.buildUpon().setWidth(128).setHeight(96).build(),
                VP9.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H264).build()}) {
            DecoderReuseEvaluation result = (DecoderReuseEvaluation) reuse.invoke(
                    renderer, "ffmpeg-vp9", VP9, next);
            assertEquals(DecoderReuseEvaluation.REUSE_RESULT_NO, result.result);
        }
        for (Method declared : FfmpegVideoRenderer.class.getDeclaredMethods()) {
            assertNotEquals("canReuseDecoder", declared.getName());
        }
    }

    @Test
    public void missingDecoderStillReleasesOutputExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        VideoDecoderOutputBuffer output = new VideoDecoderOutputBuffer(b -> releases.incrementAndGet());
        FfmpegDecoderException error = assertThrows(FfmpegDecoderException.class,
                () -> renderer().renderOutputBufferToSurface(output, null));
        assertTrue(error.getMessage().contains("decoder is not initialized"));
        assertEquals(1, releases.get());
    }

    @Test
    public void renderFailureReturnsSingleBufferAndCleanupDoesNotFreeFrameAgain() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        VideoDecoderOutputBuffer output = null;
        try {
            for (int i = 0; i < 12; i++) {
                output = decodeKeyFrame(decoder);
                assertNotEquals(0, output.decoderPrivate);
                output.mode = C.VIDEO_OUTPUT_MODE_YUV;
                VideoDecoderOutputBuffer failed = output;
                assertThrows(FfmpegDecoderException.class,
                        () -> renderer.renderOutputBufferToSurface(failed, null));
                assertEquals(0, output.decoderPrivate);
            }
        } finally {
            decoder.release();
        }
        assertNotNull(output);
        assertEquals(0, output.decoderPrivate);
    }

    @Test
    public void nativeSurfaceFailureReleasesFrame() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        try (ImageReader images = ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2)) {
            Surface invalid = images.getSurface();
            invalid.release();
            VideoDecoderOutputBuffer output = decodeKeyFrame(decoder);
            assertNotEquals(0, output.decoderPrivate);
            assertThrows(FfmpegDecoderException.class,
                    () -> renderer.renderOutputBufferToSurface(output, invalid));
            assertEquals(0, output.decoderPrivate);
        } finally {
            decoder.release();
        }
    }

    @Test
    public void successfulRenderAndDecoderFirstCleanupReleaseFrames() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        try (ImageReader images = ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2)) {
            try {
                VideoDecoderOutputBuffer rendered = decodeKeyFrame(decoder);
                renderer.renderOutputBufferToSurface(rendered, images.getSurface());
                assertEquals(0, rendered.decoderPrivate);
                try (Image image = images.acquireNextImage()) {
                    assertNotNull("Rendering must publish an image", image);
                }
                VideoDecoderOutputBuffer held = decodeKeyFrame(decoder);
                assertNotEquals(0, held.decoderPrivate);
                decoder.release();
                assertEquals(0, held.decoderPrivate);
                held.release();
                assertEquals(0, held.decoderPrivate);
            } finally {
                decoder.release();
            }
        }
    }

    private static void assertSupport(FfmpegVideoRenderer renderer, Format format, int expected) {
        assertEquals(expected, RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)));
    }

    private static VideoDecoderOutputBuffer decodeKeyFrame(FfmpegVideoDecoder decoder) throws Exception {
        byte[] packet;
        try (DataInputStream asset = new DataInputStream(InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("vp9.ivf"))) {
            asset.skipBytes(32);
            int size = Integer.reverseBytes(asset.readInt());
            asset.skipBytes(8);
            packet = new byte[size];
            asset.readFully(packet);
        }
        DecoderInputBuffer input = decoder.dequeueInputBuffer();
        assertNotNull(input);
        input.ensureSpaceForWrite(packet.length);
        input.data.put(packet);
        input.timeUs = 0;
        input.format = VP9;
        input.flip();
        decoder.queueInputBuffer(input);
        long deadline = SystemClock.elapsedRealtime() + 5000;
        VideoDecoderOutputBuffer output;
        while ((output = decoder.dequeueOutputBuffer()) == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(5);
        }
        assertNotNull("Output pool starved or decoder failed to produce a frame", output);
        return output;
    }
}
