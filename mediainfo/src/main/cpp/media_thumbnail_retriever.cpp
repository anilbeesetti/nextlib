extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
}

#include <android/bitmap.h>
#include <jni.h>
#include <cstdio>
#include <cstdlib>

struct MediaThumbnailRetrieverContext {
    AVFormatContext *formatContext;
    int videoStreamIndex;
    int rotationDegrees;
};

static MediaThumbnailRetrieverContext *context_from_handle(jlong handle) {
    return reinterpret_cast<MediaThumbnailRetrieverContext *>(handle);
}

static jlong handle_from_context(MediaThumbnailRetrieverContext *context) {
    return reinterpret_cast<jlong>(context);
}

static int normalize_rotation(int rotation) {
    rotation %= 360;
    if (rotation < 0) {
        rotation += 360;
    }
    return rotation;
}

static int read_rotation_degrees(AVStream *stream) {
    if (!stream) {
        return 0;
    }

    int rotation = 0;
    AVDictionaryEntry *rotateTag = av_dict_get(stream->metadata, "rotate", nullptr, 0);
    if (rotateTag && rotateTag->value && *rotateTag->value) {
        rotation = normalize_rotation(atoi(rotateTag->value));
    }

    uint8_t *displayMatrix = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, nullptr);
    if (displayMatrix) {
        double theta = av_display_rotation_get(reinterpret_cast<int32_t *>(displayMatrix));
        rotation = normalize_rotation(static_cast<int>(-theta));
    }

    return rotation;
}

static jobject create_bitmap(JNIEnv *env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) {
        return nullptr;
    }

    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapConfigClass) {
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
            bitmapConfigClass,
            "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;");
    if (!argb8888Field) {
        return nullptr;
    }

    jobject argb8888Obj = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);
    if (!argb8888Obj) {
        return nullptr;
    }

    jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!createBitmapMethod) {
        return nullptr;
    }

    return env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height, argb8888Obj);
}

static jobject frame_to_bitmap(JNIEnv *env, const AVFrame *frame) {
    if (!frame || frame->width <= 0 || frame->height <= 0) {
        return nullptr;
    }

    jobject bitmap = create_bitmap(env, frame->width, frame->height);
    if (!bitmap) {
        return nullptr;
    }

    AndroidBitmapInfo bitmapInfo;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) < 0) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    void *bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) < 0 || !bitmapPixels) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    SwsContext *swsContext = sws_getContext(
            frame->width,
            frame->height,
            static_cast<AVPixelFormat>(frame->format),
            bitmapInfo.width,
            bitmapInfo.height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);

    if (!swsContext) {
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    AVFrame *outputFrame = av_frame_alloc();
    if (!outputFrame) {
        sws_freeContext(swsContext);
        AndroidBitmap_unlockPixels(env, bitmap);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    av_image_fill_arrays(
            outputFrame->data,
            outputFrame->linesize,
            static_cast<uint8_t *>(bitmapPixels),
            AV_PIX_FMT_RGBA,
            bitmapInfo.width,
            bitmapInfo.height,
            1);

    sws_scale(
            swsContext,
            frame->data,
            frame->linesize,
            0,
            frame->height,
            outputFrame->data,
            outputFrame->linesize);

    av_frame_free(&outputFrame);
    sws_freeContext(swsContext);
    AndroidBitmap_unlockPixels(env, bitmap);

    return bitmap;
}

static AVCodecContext *create_decoder_context(MediaThumbnailRetrieverContext *context) {
    if (!context || !context->formatContext || context->videoStreamIndex < 0) {
        return nullptr;
    }

    AVStream *videoStream = context->formatContext->streams[context->videoStreamIndex];
    if (!videoStream || !videoStream->codecpar) {
        return nullptr;
    }

    const AVCodec *decoder = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!decoder) {
        return nullptr;
    }

    AVCodecContext *codecContext = avcodec_alloc_context3(decoder);
    if (!codecContext) {
        return nullptr;
    }

    if (avcodec_parameters_to_context(codecContext, videoStream->codecpar) < 0 ||
            avcodec_open2(codecContext, decoder, nullptr) < 0) {
        avcodec_free_context(&codecContext);
        return nullptr;
    }

    return codecContext;
}

static bool decode_next_frame(MediaThumbnailRetrieverContext *context,
        AVCodecContext *codecContext,
        AVPacket *packet,
        AVFrame *frame) {
    while (av_read_frame(context->formatContext, packet) >= 0) {
        if (packet->stream_index != context->videoStreamIndex) {
            av_packet_unref(packet);
            continue;
        }

        int sendResult = avcodec_send_packet(codecContext, packet);
        av_packet_unref(packet);
        if (sendResult < 0) {
            return false;
        }

        int receiveResult = avcodec_receive_frame(codecContext, frame);
        if (receiveResult == AVERROR(EAGAIN) || receiveResult == AVERROR_EOF) {
            continue;
        }
        if (receiveResult < 0) {
            return false;
        }

        return true;
    }

    avcodec_send_packet(codecContext, nullptr);
    if (avcodec_receive_frame(codecContext, frame) >= 0) {
        return true;
    }

    return false;
}

static jobject decode_frame_at_time(JNIEnv *env, MediaThumbnailRetrieverContext *context, int64_t timeUs) {
    AVCodecContext *codecContext = create_decoder_context(context);
    if (!codecContext) {
        return nullptr;
    }

    AVStream *videoStream = context->formatContext->streams[context->videoStreamIndex];
    int64_t targetTimestamp = av_rescale_q(timeUs, AV_TIME_BASE_Q, videoStream->time_base);
    av_seek_frame(context->formatContext, context->videoStreamIndex, targetTimestamp, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(codecContext);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!packet || !frame) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        return nullptr;
    }

    jobject result = nullptr;
    if (decode_next_frame(context, codecContext, packet, frame)) {
        result = frame_to_bitmap(env, frame);
    }

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    return result;
}

static jobject decode_frame_at_index(JNIEnv *env, MediaThumbnailRetrieverContext *context, int frameIndex) {
    AVCodecContext *codecContext = create_decoder_context(context);
    if (!codecContext) {
        return nullptr;
    }

    av_seek_frame(context->formatContext, context->videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(codecContext);

    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (!packet || !frame) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        avcodec_free_context(&codecContext);
        return nullptr;
    }

    int decodedFrameCount = 0;
    jobject result = nullptr;

    while (decode_next_frame(context, codecContext, packet, frame)) {
        if (decodedFrameCount == frameIndex) {
            result = frame_to_bitmap(env, frame);
            break;
        }
        decodedFrameCount++;
        av_frame_unref(frame);
    }

    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codecContext);

    return result;
}

static jlong create_context_from_source(const char *source) {
    AVFormatContext *formatContext = nullptr;
    if (!source || avformat_open_input(&formatContext, source, nullptr, nullptr) < 0) {
        return 0;
    }

    if (avformat_find_stream_info(formatContext, nullptr) < 0) {
        avformat_close_input(&formatContext);
        return 0;
    }

    int videoStreamIndex = -1;
    for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
        AVStream *stream = formatContext->streams[i];
        if (stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
            continue;
        }
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) {
            continue;
        }
        videoStreamIndex = static_cast<int>(i);
        break;
    }

    if (videoStreamIndex < 0) {
        videoStreamIndex = av_find_best_stream(formatContext, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    }

    auto *context = reinterpret_cast<MediaThumbnailRetrieverContext *>(malloc(sizeof(MediaThumbnailRetrieverContext)));
    if (!context) {
        avformat_close_input(&formatContext);
        return 0;
    }

    context->formatContext = formatContext;
    context->videoStreamIndex = videoStreamIndex;
    context->rotationDegrees = (videoStreamIndex >= 0)
            ? read_rotation_degrees(formatContext->streams[videoStreamIndex])
            : 0;
    return handle_from_context(context);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeCreateFromPath(
        JNIEnv *env,
        jobject thiz,
        jstring file_path) {
    const char *source = env->GetStringUTFChars(file_path, nullptr);
    jlong handle = create_context_from_source(source);
    env->ReleaseStringUTFChars(file_path, source);
    return handle;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeCreateFromFD(
        JNIEnv *env,
        jobject thiz,
        jint file_descriptor) {
    char fdPath[64];
    snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", file_descriptor);
    return create_context_from_source(fdPath);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeGetEmbeddedPicture(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context || !context->formatContext) {
        return nullptr;
    }

    for (unsigned int i = 0; i < context->formatContext->nb_streams; ++i) {
        AVStream *stream = context->formatContext->streams[i];
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) == 0) {
            continue;
        }

        AVPacket attachedPic = stream->attached_pic;
        if (!attachedPic.data || attachedPic.size <= 0) {
            continue;
        }

        jbyteArray result = env->NewByteArray(attachedPic.size);
        if (!result) {
            return nullptr;
        }

        env->SetByteArrayRegion(result, 0, attachedPic.size, reinterpret_cast<const jbyte *>(attachedPic.data));
        return result;
    }

    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeGetFrameAtTime(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jlong time_us) {
    auto *context = context_from_handle(handle);
    if (!context || !context->formatContext || context->videoStreamIndex < 0) {
        return nullptr;
    }

    return decode_frame_at_time(env, context, time_us);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeGetFrameAtIndex(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jint frame_index) {
    auto *context = context_from_handle(handle);
    if (!context || !context->formatContext || context->videoStreamIndex < 0 || frame_index < 0) {
        return nullptr;
    }

    return decode_frame_at_index(env, context, frame_index);
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeGetRotationDegrees(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context) {
        return 0;
    }
    return context->rotationDegrees;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaThumbnailRetriever_nativeRelease(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {
    auto *context = context_from_handle(handle);
    if (!context) {
        return;
    }

    if (context->formatContext) {
        avformat_close_input(&context->formatContext);
    }

    free(context);
}
