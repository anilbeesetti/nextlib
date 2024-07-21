extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswscale/swscale.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
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


bool frame_extractor_load_frame(JNIEnv *env, int64_t jFrameLoaderContextHandle, int64_t time_millis,
                                jobject jBitmap) {
    auto *frameLoaderContext = frame_loader_context_from_handle(jFrameLoaderContextHandle);
    if (!frameLoaderContext || !frameLoaderContext->avFormatContext ||
        !frameLoaderContext->parameters) {
        return false;
    }

    AndroidBitmapInfo bitmapMetricInfo;
    if (AndroidBitmap_getInfo(env, jBitmap, &bitmapMetricInfo) < 0) {
        return false;
    }

    auto pixelFormat = static_cast<AVPixelFormat>(frameLoaderContext->parameters->format);
    if (pixelFormat == AV_PIX_FMT_NONE) {
        return false;
    }

    AVStream *avVideoStream = frameLoaderContext->avFormatContext->streams[frameLoaderContext->videoStreamIndex];
    if (!avVideoStream) {
        return false;
    }

    int rotation = 0;
    AVDictionaryEntry *rotateTag = av_dict_get(avVideoStream->metadata, "rotate", nullptr, 0);
    if (rotateTag && *rotateTag->value) {
        rotation = atoi(rotateTag->value);
        rotation %= 360;
        if (rotation < 0) rotation += 360;
    }
    uint8_t *displaymatrix = av_stream_get_side_data(avVideoStream, AV_PKT_DATA_DISPLAYMATRIX,
                                                     NULL);
    if (displaymatrix) {
        double theta = av_display_rotation_get((int32_t *) displaymatrix);
        rotation = (int) (theta) % 360;
        if (rotation < 0) rotation += 360;
    }

    int srcW = frameLoaderContext->parameters->width;
    int srcH = frameLoaderContext->parameters->height;
    int dstW = bitmapMetricInfo.width;
    int dstH = bitmapMetricInfo.height;

    bool swapDimensions = (rotation == 90 || rotation == 270);
    if (swapDimensions) {
        std::swap(srcW, srcH);
    }

    SwsContext *scalingContext = sws_getContext(
            srcW, srcH, pixelFormat,
            dstW, dstH, AV_PIX_FMT_RGBA,
            SWS_BICUBIC, nullptr, nullptr, nullptr);

    if (!scalingContext) {
        return false;
    }

    int64_t videoDuration = avVideoStream->duration;
    if (videoDuration == LONG_LONG_MIN && avVideoStream->time_base.den != 0) {
        videoDuration = av_rescale_q(frameLoaderContext->avFormatContext->duration, AV_TIME_BASE_Q,
                                     avVideoStream->time_base);
    }

    int64_t seekPosition = (time_millis != -1) ?
                           av_rescale_q(time_millis, AV_TIME_BASE_Q, avVideoStream->time_base) :
                           videoDuration / 3;

    seekPosition = FFMIN(seekPosition, videoDuration);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!packet || !frame) {
        sws_freeContext(scalingContext);
        av_packet_free(&packet);
        av_frame_free(&frame);
        return false;
    }

    AVCodecContext *videoCodecContext = avcodec_alloc_context3(frameLoaderContext->avVideoCodec);
    if (!videoCodecContext ||
        avcodec_parameters_to_context(videoCodecContext, frameLoaderContext->parameters) < 0 ||
        avcodec_open2(videoCodecContext, frameLoaderContext->avVideoCodec, nullptr) < 0) {
        sws_freeContext(scalingContext);
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&videoCodecContext);
        return false;
    }

    av_seek_frame(frameLoaderContext->avFormatContext, frameLoaderContext->videoStreamIndex,
                  seekPosition, AVSEEK_FLAG_BACKWARD);

    bool resultValue = read_frame(frameLoaderContext, packet, frame, videoCodecContext);

    if (!resultValue) {
        av_seek_frame(frameLoaderContext->avFormatContext, frameLoaderContext->videoStreamIndex, 0,
                      0);
        resultValue = read_frame(frameLoaderContext, packet, frame, videoCodecContext);
    }

    if (resultValue) {
        void *bitmapBuffer;
        if (AndroidBitmap_lockPixels(env, jBitmap, &bitmapBuffer) < 0) {
            resultValue = false;
        } else {
            AVFrame *frameForDrawing = av_frame_alloc();
            if (frameForDrawing) {
                av_image_fill_arrays(frameForDrawing->data, frameForDrawing->linesize,
                                     static_cast<const uint8_t *>(bitmapBuffer), AV_PIX_FMT_RGBA,
                                     bitmapMetricInfo.width, bitmapMetricInfo.height, 1);

                AVFrame *rotatedFrame = av_frame_alloc();
                if (rotatedFrame) {
                    av_image_alloc(rotatedFrame->data, rotatedFrame->linesize,
                                   swapDimensions ? frame->height : frame->width,
                                   swapDimensions ? frame->width : frame->height,
                                   AV_PIX_FMT_YUV420P, 1);

                    // Perform rotation
                    for (int plane = 0; plane < 3; plane++) {
                        int h = (plane == 0) ? frame->height : frame->height / 2;
                        int w = (plane == 0) ? frame->width : frame->width / 2;
                        for (int i = 0; i < h; i++) {
                            for (int j = 0; j < w; j++) {
                                int src_x = j, src_y = i;
                                int dst_x, dst_y;
                                switch (rotation) {
                                    case 90:
                                        dst_x = i;
                                        dst_y = w - 1 - j;
                                        break;
                                    case 180:
                                        dst_x = w - 1 - j;
                                        dst_y = h - 1 - i;
                                        break;
                                    case 270:
                                        dst_x = h - 1 - i;
                                        dst_y = j;
                                        break;
                                    default:
                                        dst_x = j;
                                        dst_y = i;
                                        break;
                                }
                                rotatedFrame->data[plane][dst_y * rotatedFrame->linesize[plane] +
                                                          dst_x] =
                                        frame->data[plane][src_y * frame->linesize[plane] + src_x];
                            }
                        }
                    }

                    // Scale and convert color space
                    sws_scale(scalingContext, rotatedFrame->data, rotatedFrame->linesize, 0,
                              swapDimensions ? frame->width : frame->height,
                              frameForDrawing->data, frameForDrawing->linesize);

                    av_freep(&rotatedFrame->data[0]);
                    av_frame_free(&rotatedFrame);
                } else {
                    // If rotation fails, use original frame
                    sws_scale(scalingContext, frame->data, frame->linesize, 0,
                              frame->height, frameForDrawing->data, frameForDrawing->linesize);
                }

                av_frame_free(&frameForDrawing);
            }
            AndroidBitmap_unlockPixels(env, jBitmap);
        }
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