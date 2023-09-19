extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
}

#include <android/bitmap.h>
#include "frame_loader_context.h"
#include "log.h"

bool frame_extractor_load_frame(JNIEnv *env, int64_t jFrameLoaderContextHandle, jobject jBitmap) {
    AndroidBitmapInfo bitmapMetricInfo;
    AndroidBitmap_getInfo(env, jBitmap, &bitmapMetricInfo);

    auto *videoStream = frame_loader_context_from_handle(jFrameLoaderContextHandle);

    auto pixelFormat = static_cast<AVPixelFormat>(videoStream->parameters->format);
    if (pixelFormat == AV_PIX_FMT_NONE) {
        // With pipe protocol some files fail to provide pixel format info.
        // In this case we can't establish neither scaling nor even a frame extracting.
        return false;
    }

    bool resultValue = true;

    SwsContext *scalingContext =
            sws_getContext(
                    // srcW
                    videoStream->parameters->width,
                    // srcH
                    videoStream->parameters->height,
                    // srcFormat
                    pixelFormat,
                    // dstW
                    bitmapMetricInfo.width,
                    // dstH
                    bitmapMetricInfo.height,
                    // dstFormat
                    AV_PIX_FMT_RGBA,
                    SWS_BICUBIC, nullptr, nullptr, nullptr);

    AVStream *avVideoStream = videoStream->
            avFormatContext->
            streams[videoStream->videoStreamIndex];

    int64_t videoDuration = avVideoStream->duration;

    // In some cases the duration is of a video stream is set to Long.MIN_VALUE and we need compute it in another way
    if (videoDuration == LONG_LONG_MIN && avVideoStream->time_base.den != 0) {
        videoDuration = videoStream->avFormatContext->duration / avVideoStream->time_base.den;
    }

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();

    int64_t seekPosition = videoDuration / 2;
    av_seek_frame(videoStream->avFormatContext,
                  videoStream->videoStreamIndex,
                  seekPosition,
                  0);

    AVCodecContext *videoCodecContext = avcodec_alloc_context3(videoStream->avVideoCodec);
    avcodec_parameters_to_context(videoCodecContext, videoStream->parameters);
    avcodec_open2(videoCodecContext, videoStream->avVideoCodec, nullptr);

    while (true) {
        if (av_read_frame(videoStream->avFormatContext, packet) < 0) {
            // Couldn't read a packet, so we skip the whole frame
            resultValue = false;
            break;
        }

        if (packet->stream_index == videoStream->videoStreamIndex) {
            avcodec_send_packet(videoCodecContext, packet);
            int response = avcodec_receive_frame(videoCodecContext, frame);
            if (response == AVERROR(EAGAIN)) {
                // A frame can be split across several packets, so continue reading in this case
                continue;
            }

            if (response >= 0) {
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
                        videoStream->parameters->height,
                        frameForDrawing->data,
                        frameForDrawing->linesize);

                av_frame_free(&frameForDrawing);

                AndroidBitmap_unlockPixels(env, jBitmap);
                break;
            }
        }
        av_packet_unref(packet);
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
                                                                          jlong duration_millis,
                                                                          jobject jBitmap) {
    bool successfullyLoaded = frame_extractor_load_frame(env, jFrameLoaderContextHandle, jBitmap);
    return static_cast<jboolean>(successfullyLoaded);
}