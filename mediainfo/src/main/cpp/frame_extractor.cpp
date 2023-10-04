extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

#include <android/bitmap.h>
#include "frame_loader_context.h"
#include "log.h"

bool read_frame(FrameLoaderContext *frameLoaderContext, AVPacket *packet, AVFrame *frame, AVCodecContext *videoCodecContext) {
    while (av_read_frame(frameLoaderContext->avFormatContext, packet) >= 0) {
        if (packet->stream_index != frameLoaderContext->videoStreamIndex) {
            continue;
        }
        int response = avcodec_send_packet(videoCodecContext, packet);
        if (response < 0) {
            break;
        }
        response = avcodec_receive_frame(videoCodecContext, frame);

        if (response == AVERROR(EAGAIN) || response == AVERROR_EOF) {
            continue;
        } else if (response < 0) {
            break;
        }

        av_packet_unref(packet);
        return true;
    }
    return false;
}


bool frame_extractor_load_frame(JNIEnv *env, int64_t jFrameLoaderContextHandle, int64_t time_millis, jobject jBitmap) {
    AndroidBitmapInfo bitmapMetricInfo;
    AndroidBitmap_getInfo(env, jBitmap, &bitmapMetricInfo);

    auto *frameLoaderContext = frame_loader_context_from_handle(jFrameLoaderContextHandle);

    auto pixelFormat = static_cast<AVPixelFormat>(frameLoaderContext->parameters->format);
    if (pixelFormat == AV_PIX_FMT_NONE) {
        // With pipe protocol some files fail to provide pixel format info.
        // In this case we can't establish neither scaling nor even a frame extracting.
        return false;
    }

    SwsContext *scalingContext =
            sws_getContext(
                    // srcW
                    frameLoaderContext->parameters->width,
                    // srcH
                    frameLoaderContext->parameters->height,
                    // srcFormat
                    pixelFormat,
                    // dstW
                    bitmapMetricInfo.width,
                    // dstH
                    bitmapMetricInfo.height,
                    // dstFormat
                    AV_PIX_FMT_RGBA,
                    SWS_BICUBIC, nullptr, nullptr, nullptr);

    AVStream *avVideoStream = frameLoaderContext->avFormatContext->streams[frameLoaderContext->videoStreamIndex];

    int64_t videoDuration = avVideoStream->duration;
    // In some cases the duration is of a video stream is set to Long.MIN_VALUE and we need compute it in another way
    if (videoDuration == LONG_LONG_MIN && avVideoStream->time_base.den != 0) {
        videoDuration = frameLoaderContext->avFormatContext->duration / avVideoStream->time_base.den;
    }


    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    int64_t seekPosition = videoDuration / 3;

    if (time_millis != -1) {
        int64_t seek_time = av_rescale_q(time_millis, AV_TIME_BASE_Q, avVideoStream->time_base);
        if (seek_time < videoDuration) {
            seekPosition = seek_time;
        }
    }

    AVCodecContext *videoCodecContext = avcodec_alloc_context3(frameLoaderContext->avVideoCodec);
    avcodec_parameters_to_context(videoCodecContext, frameLoaderContext->parameters);
    avcodec_open2(videoCodecContext, frameLoaderContext->avVideoCodec, nullptr);

    av_seek_frame(frameLoaderContext->avFormatContext,
                  frameLoaderContext->videoStreamIndex,
                  seekPosition,
                  0);

    bool resultValue = read_frame(frameLoaderContext, packet, frame, videoCodecContext);

    if (!resultValue) {
        av_seek_frame(frameLoaderContext->avFormatContext,
                      frameLoaderContext->videoStreamIndex,
                      0,
                      0);
        resultValue = read_frame(frameLoaderContext, packet, frame, videoCodecContext);
    }

    if (resultValue) {
        AVFrame *frameForDrawing = av_frame_alloc();
        void *bitmapBuffer;
        AndroidBitmap_lockPixels(env, jBitmap, &bitmapBuffer);

        // Prepare a FFmpeg's frame to use Android Bitmap's buffer
        av_image_fill_arrays(
                frameForDrawing->data,
                frameForDrawing->linesize,
                static_cast<const uint8_t *>(bitmapBuffer),
                AV_PIX_FMT_RGBA,
                bitmapMetricInfo.width,
                bitmapMetricInfo.height,
                1);

        // Scale the frame that was read from the media to a frame that wraps Android Bitmap's buffer
        sws_scale(
                scalingContext,
                frame->data,
                frame->linesize,
                0,
                frameLoaderContext->parameters->height,
                frameForDrawing->data,
                frameForDrawing->linesize);

        av_frame_free(&frameForDrawing);

        AndroidBitmap_unlockPixels(env, jBitmap);
    }

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&videoCodecContext);

    sws_freeContext(scalingContext);

    return resultValue;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_FrameLoader_nativeRelease(JNIEnv *env, jclass clazz,
                                                                        jlong jFrameLoaderContextHandle) {
    frame_loader_context_free(jFrameLoaderContextHandle);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_FrameLoader_nativeLoadFrame(JNIEnv *env, jclass clazz,
                                                                          jlong jFrameLoaderContextHandle,
                                                                          jlong time_millis,
                                                                          jobject jBitmap) {
    bool successfullyLoaded = frame_extractor_load_frame(env, jFrameLoaderContextHandle, time_millis, jBitmap);
    return static_cast<jboolean>(successfullyLoaded);
}