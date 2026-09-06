
#include <android/log.h>
#include <jni.h>
#include <cstdlib>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include "ffcommon.h"

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <cstdint>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define ALIGN(x, a) (((x) + ((a) - 1)) & ~((a) - 1))

// ANativeWindow_lock() implicitly connects the Surface's BufferQueue to the CPU
// producer API. Since this Surface is shared with ExoPlayer's
// MediaCodecVideoRenderer, that connection must be released once we are done, or
// a hardware codec selected for a following media item cannot connect to the
// same Surface and MediaCodec.configure() fails with IllegalArgumentException.
//
// native_window_api_disconnect() does this, but it lives in the platform-only
// <system/window.h> and is neither declared nor exported by the public NDK, so
// we reimplement it here against the stable ANativeWindow C ABI (perform()).
constexpr int NATIVE_WINDOW_API_CPU = 2;        // producer connected via ANativeWindow_lock()
constexpr int NATIVE_WINDOW_API_DISCONNECT = 14; // perform() operation code

static int native_window_api_disconnect(ANativeWindow *window, int api) {
    // Layout mirrors struct ANativeWindow from <system/window.h> up to perform().
    struct ANativeWindowAbi {
        int magic;
        int version;
        void *reserved[4];
        void (*incRef)(void *);
        void (*decRef)(void *);
        const uint32_t flags;
        const int minSwapInterval;
        const int maxSwapInterval;
        const float xdpi;
        const float ydpi;
        intptr_t oem[4];
        int (*setSwapInterval)(void *, int);
        int (*dequeueBuffer_DEPRECATED)(void *, void **);
        int (*lockBuffer_DEPRECATED)(void *, void *);
        int (*queueBuffer_DEPRECATED)(void *, void *);
        int (*query)(const void *, int, int *);
        int (*perform)(void *, int, ...);
    };
    auto *w = reinterpret_cast<ANativeWindowAbi *>(window);
    return w->perform(window, NATIVE_WINDOW_API_DISCONNECT, api);
}

static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
static const int VIDEO_DECODER_ERROR_READ_FRAME = -3;


// Media3 C.VIDEO_OUTPUT_MODE_SURFACE_YUV.
constexpr int kOutputModeSurface = 1;

struct JniContext {
    ~JniContext() {
        sws_freeContext(renderContext);
        sws_freeContext(yuvContext);
        releaseContext(codecContext);
    }

    void ReleaseSurface(JNIEnv *env) {
        if (native_window) {
            if (connected_as_cpu) {
                native_window_api_disconnect(native_window, NATIVE_WINDOW_API_CPU);
            }
            ANativeWindow_release(native_window);
        }
        if (surface) env->DeleteGlobalRef(surface);
        native_window = nullptr;
        surface = nullptr;
        connected_as_cpu = false;
        native_window_width = 0;
        native_window_height = 0;
    }

    bool MaybeAcquireNativeWindow(JNIEnv *env, jobject new_surface) {
        if (surface && env->IsSameObject(surface, new_surface)) return true;
        ReleaseSurface(env);
        native_window = ANativeWindow_fromSurface(env, new_surface);
        if (!native_window) return false;
        surface = env->NewGlobalRef(new_surface);
        if (!surface) {
            ReleaseSurface(env);
            return false;
        }
        return true;
    }

    jfieldID data_field{};
    jfieldID decoder_private_field{};
    jmethodID init_for_private_frame_method{};
    jmethodID init_for_yuv_frame_method{};
    jmethodID init_method{};

    AVCodecContext *codecContext{};
    // Decoding and rendering run on different threads.
    SwsContext *renderContext{};
    SwsContext *yuvContext{};

    ANativeWindow *native_window = nullptr;
    jobject surface = nullptr;
    int native_window_width = 0;
    int native_window_height = 0;
    bool connected_as_cpu = false;
};

JniContext *createVideoContext(JNIEnv *env,
                               AVCodec *codec,
                               jbyteArray extraData,
                               jint threads) {
    auto jniContext = std::make_unique<JniContext>();

    AVCodecContext *codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) {
        LOGE("Failed to allocate context.");
        return nullptr;
    }

    jniContext->codecContext = codecContext;

    if (extraData) {
        jsize size = env->GetArrayLength(extraData);
        codecContext->extradata_size = size;
        codecContext->extradata = (uint8_t *) av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!codecContext->extradata) {
            LOGE("Failed to allocate extradata.");
            return nullptr;
        }
        env->GetByteArrayRegion(extraData, 0, size, (jbyte *) codecContext->extradata);
    }

    codecContext->thread_count = threads;
    codecContext->err_recognition = AV_EF_IGNORE_ERR;
    int result = avcodec_open2(codecContext, codec, nullptr);
    if (result < 0) {
        logError("avcodec_open2", result);
        return nullptr;
    }

    // Populate JNI References.
    jclass outputBufferClass = env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
    jniContext->data_field = env->GetFieldID(outputBufferClass, "data", "Ljava/nio/ByteBuffer;");
    jniContext->decoder_private_field = env->GetFieldID(outputBufferClass, "decoderPrivate", "J");
    jniContext->init_for_private_frame_method = env->GetMethodID(outputBufferClass, "initForPrivateFrame", "(II)V");
    jniContext->init_for_yuv_frame_method = env->GetMethodID(outputBufferClass, "initForYuvFrame", "(IIIII)Z");
    jniContext->init_method = env->GetMethodID(outputBufferClass, "init", "(JILjava/nio/ByteBuffer;)V");

    if (env->ExceptionCheck()) return nullptr;
    return jniContext.release();
}


extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegInitialize(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jstring codec_name,
                                                                                 jbyteArray extra_data,
                                                                                 jint threads) {
    AVCodec *codec = getCodecByName(env, codec_name);
    if (!codec) {
        LOGE("Codec not found.");
        return 0L;
    }

    return (jlong) createVideoContext(env, codec, extra_data, threads);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReset(JNIEnv *env, jobject thiz,
                                                                            jlong jContext) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *context = jniContext->codecContext;
    if (!context) {
        LOGE("Tried to reset without a context.");
        return 0L;
    }

    avcodec_flush_buffers(context);
    return (jlong) jniContext;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegRelease(JNIEnv *env, jobject thiz,
                                                                              jlong jContext) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    if (jniContext) {
        jniContext->ReleaseSurface(env);
        delete jniContext;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReleaseFrame(
        JNIEnv *, jobject, jlong frame_pointer) {
    auto *frame = reinterpret_cast<AVFrame *>(frame_pointer);
    av_frame_free(&frame);
}

// Convert using metadata from this frame, not the decoder's next queued frame.
static SwsContext *GetScaleContext(SwsContext *context, const AVFrame *frame,
                                   AVPixelFormat output_format) {
    context = sws_getCachedContext(context, frame->width, frame->height,
            static_cast<AVPixelFormat>(frame->format), frame->width, frame->height,
            output_format, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (context) {
        const int *coefficients = sws_getCoefficients(
                frame->colorspace == AVCOL_SPC_UNSPECIFIED ? SWS_CS_DEFAULT : frame->colorspace);
        sws_setColorspaceDetails(context, coefficients, frame->color_range == AVCOL_RANGE_JPEG,
                coefficients, output_format == AV_PIX_FMT_RGBA, 0, 1 << 16, 1 << 16);
    }
    return context;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegRenderFrame(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jlong jContext,
                                                                                  jobject surface,
                                                                                  jobject output_buffer,
                                                                                  jint displayed_width,
                                                                                  jint displayed_height) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    auto *frame = reinterpret_cast<AVFrame *>(
            env->GetLongField(output_buffer, jniContext->decoder_private_field));
    if (!frame || !jniContext->MaybeAcquireNativeWindow(env, surface)) {
        return VIDEO_DECODER_ERROR_OTHER;
    }

    if (jniContext->native_window_width != frame->width ||
        jniContext->native_window_height != frame->height) {
        // RGBA is a public NDK window format; YV12 CPU buffers are not portable
        // across Surface producers (including the emulator's graphics backend).
        if (ANativeWindow_setBuffersGeometry(jniContext->native_window,
                frame->width, frame->height, WINDOW_FORMAT_RGBA_8888)) {
            return VIDEO_DECODER_ERROR_OTHER;
        }
        jniContext->native_window_width = frame->width;
        jniContext->native_window_height = frame->height;
    }
    jniContext->renderContext = GetScaleContext(jniContext->renderContext, frame, AV_PIX_FMT_RGBA);
    if (!jniContext->renderContext) return VIDEO_DECODER_ERROR_OTHER;

    ANativeWindow_Buffer buffer;
    int result = ANativeWindow_lock(jniContext->native_window, &buffer, nullptr);
    if (result == -19) {
        jniContext->ReleaseSurface(env);
        return VIDEO_DECODER_SUCCESS;
    }
    if (result) return VIDEO_DECODER_ERROR_OTHER;
    jniContext->connected_as_cpu = true;

    int rows = 0;
    if (buffer.bits && buffer.format == WINDOW_FORMAT_RGBA_8888 &&
        buffer.width >= frame->width && buffer.height >= frame->height) {
        uint8_t *dest[] = {static_cast<uint8_t *>(buffer.bits), nullptr, nullptr, nullptr};
        int strides[] = {buffer.stride * 4, 0, 0, 0};
        rows = sws_scale(jniContext->renderContext, frame->data, frame->linesize,
                        0, frame->height, dest, strides);
    }
    result = ANativeWindow_unlockAndPost(jniContext->native_window);
    return !result && rows == frame->height ? VIDEO_DECODER_SUCCESS : VIDEO_DECODER_ERROR_OTHER;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegSendPacket(JNIEnv *env,
                                                                                 jobject thiz,
                                                                                 jlong jContext,
                                                                                 jobject encoded_data,
                                                                                 jint length,
                                                                                 jlong input_time) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;

    auto *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(encoded_data);
    if (!inputBuffer || length < 0 ||
        env->GetDirectBufferCapacity(encoded_data) < static_cast<jlong>(length) + AV_INPUT_BUFFER_PADDING_SIZE) {
        return VIDEO_DECODER_ERROR_OTHER;
    }
    memset(inputBuffer + length, 0, AV_INPUT_BUFFER_PADDING_SIZE);
    AVPacket packet{};
    packet.dts = AV_NOPTS_VALUE;
    packet.pos = -1;
    packet.data = inputBuffer;
    packet.size = length;
    packet.pts = input_time;

    // Queue input data.
    int result = avcodec_send_packet(avContext, &packet);
    av_packet_unref(&packet);
    if (result) {
        logError("avcodec_send_packet", result);
        if (result == AVERROR_INVALIDDATA) {
            // need more data
            return VIDEO_DECODER_ERROR_INVALID_DATA;
        } else if (result == AVERROR(EAGAIN)) {
            // need read frame
            return VIDEO_DECODER_ERROR_READ_FRAME;
        } else {
            return VIDEO_DECODER_ERROR_OTHER;
        }
    }
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReceiveFrame(JNIEnv *env,
                                                                                   jobject thiz,
                                                                                   jlong jContext,
                                                                                   jint output_mode,
                                                                                   jobject output_buffer,
                                                                                   jboolean decode_only) {
    auto *const jniContext = reinterpret_cast<JniContext *>(jContext);
    AVCodecContext *avContext = jniContext->codecContext;

    AVFrame *frame = av_frame_alloc();
    if (!frame) {
        LOGE("Failed to allocate output frame.");
        return VIDEO_DECODER_ERROR_OTHER;
    }
    int result = avcodec_receive_frame(avContext, frame);

    // fail
    if (decode_only || result == AVERROR(EAGAIN)) {
        // This is not an error. The input data was decode-only or no displayable
        // frames are available.
        av_frame_free(&frame);
        return VIDEO_DECODER_ERROR_INVALID_DATA;
    }
    if (result) {
        av_frame_free(&frame);
        logError("avcodec_receive_frame", result);
        return VIDEO_DECODER_ERROR_OTHER;
    }

    env->CallVoidMethod(output_buffer, jniContext->init_method, frame->pts, output_mode, nullptr);
    if (env->ExceptionCheck()) {
        av_frame_free(&frame);
        return VIDEO_DECODER_ERROR_OTHER;
    }
    if (output_mode == kOutputModeSurface) {
        env->CallVoidMethod(output_buffer, jniContext->init_for_private_frame_method,
                           frame->width, frame->height);
        if (env->ExceptionCheck()) {
            av_frame_free(&frame);
            return VIDEO_DECODER_ERROR_OTHER;
        }
        // The output buffer owns this reference until it is rendered, dropped or flushed.
        // Preserve the actual planes, bit depth and color metadata without a full-frame copy.
        env->SetLongField(output_buffer, jniContext->decoder_private_field,
                          reinterpret_cast<jlong>(frame));
        return VIDEO_DECODER_SUCCESS;
    }

    // Media3's YUV output contract is always planar 8-bit 4:2:0, even for 10-bit/4:4:4 input.
    const int yStride = ALIGN(frame->width, 32);
    const int uvStride = ALIGN((frame->width + 1) / 2, 16);
    const int colorspace = frame->colorspace == AVCOL_SPC_BT709 ? 2 :
            frame->colorspace == AVCOL_SPC_BT2020_NCL ? 3 : 1;
    const jboolean initialized = env->CallBooleanMethod(output_buffer,
            jniContext->init_for_yuv_frame_method, frame->width, frame->height,
            yStride, uvStride, colorspace);
    if (env->ExceptionCheck() || !initialized) {
        av_frame_free(&frame);
        return VIDEO_DECODER_ERROR_OTHER;
    }
    jobject data_object = env->GetObjectField(output_buffer, jniContext->data_field);
    auto *data = static_cast<uint8_t *>(env->GetDirectBufferAddress(data_object));
    const size_t yLength = static_cast<size_t>(yStride) * frame->height;
    const size_t uvLength = static_cast<size_t>(uvStride) * ((frame->height + 1) / 2);
    uint8_t *dest[] = {data, data + yLength, data + yLength + uvLength, nullptr};
    int strides[] = {yStride, uvStride, uvStride, 0};
    jniContext->yuvContext = GetScaleContext(jniContext->yuvContext, frame, AV_PIX_FMT_YUV420P);
    result = jniContext->yuvContext ? sws_scale(jniContext->yuvContext, frame->data,
            frame->linesize, 0, frame->height, dest, strides) : 0;
    const bool success = result == frame->height;
    av_frame_free(&frame);
    return success ? VIDEO_DECODER_SUCCESS : VIDEO_DECODER_ERROR_OTHER;
}
