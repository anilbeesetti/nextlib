#include <jni.h>
#include <stdio.h>
#include "utils.h"
#include "log.h"

extern "C" {
#include "libavformat/avformat.h"
#include "libavcodec/codec_desc.h"
}

static char *get_string(AVDictionary *metadata, const char *key) {
    char *result = nullptr;
    AVDictionaryEntry *tag = av_dict_get(metadata, key, nullptr, 0);
    if (tag != nullptr) {
        result = tag->value;
    }
    return result;
}

static char *get_title(AVDictionary *metadata) {
    return get_string(metadata, "title");
}

static char *get_language(AVDictionary *metadata) {
    return get_string(metadata, "language");
}

static void onError(JNIEnv *env, jobject jMediaInfoBuilder) {
    utils_call_instance_method_void(env, jMediaInfoBuilder, fields.MediaInfoBuilder.onErrorID);
}

void onMediaInfoFound(JNIEnv *env, jobject jMediaInfoBuilder, AVFormatContext *avFormatContext) {
    const char *fileFormatName = avFormatContext->iformat->long_name;

    jstring jFileFormatName = env->NewStringUTF(fileFormatName);

    utils_call_instance_method_void(env,
                                    jMediaInfoBuilder,
                                    fields.MediaInfoBuilder.onMediaInfoFoundID,
                                    jFileFormatName,
                                    avFormatContext->duration / 1000);
}

void onVideoStreamFound(JNIEnv *env, jobject jMediaInfoBuilder, AVFormatContext *avFormatContext, int index) {
    AVStream *stream = avFormatContext->streams[index];
    AVCodecParameters *parameters = stream->codecpar;

    auto codecDescriptor = avcodec_descriptor_get(parameters->codec_id);

    AVRational guessedFrameRate = av_guess_frame_rate(avFormatContext, avFormatContext->streams[index], nullptr);

    double resultFrameRate = guessedFrameRate.den == 0 ? 0.0 : guessedFrameRate.num / (double) guessedFrameRate.den;

    jstring jTitle = env->NewStringUTF(get_title(stream->metadata));
    jstring jCodecName = env->NewStringUTF(codecDescriptor->long_name);
    jstring jLanguage = env->NewStringUTF(get_language(stream->metadata));

    utils_call_instance_method_void(env,
                                    jMediaInfoBuilder,
                                    fields.MediaInfoBuilder.onVideoStreamFoundID,
                                    index,
                                    jTitle,
                                    jCodecName,
                                    jLanguage,
                                    stream->disposition,
                                    parameters->bit_rate,
                                    resultFrameRate,
                                    parameters->width,
                                    parameters->height);
}

void onAudioStreamFound(JNIEnv *env, jobject jMediaInfoBuilder, AVFormatContext *avFormatContext, int index) {
    AVStream *stream = avFormatContext->streams[index];
    AVCodecParameters *parameters = stream->codecpar;

    auto codecDescriptor = avcodec_descriptor_get(parameters->codec_id);

    auto avSampleFormat = static_cast<AVSampleFormat>(parameters->format);
    auto jSampleFormat = env->NewStringUTF(av_get_sample_fmt_name(avSampleFormat));

    jstring jTitle = env->NewStringUTF(get_title(stream->metadata));
    jstring jCodecName = env->NewStringUTF(codecDescriptor->long_name);
    jstring jLanguage = env->NewStringUTF(get_language(stream->metadata));

    // TODO: Channel layout

    utils_call_instance_method_void(env,
                                    jMediaInfoBuilder,
                                    fields.MediaInfoBuilder.onAudioStreamFoundID,
                                    index,
                                    jTitle,
                                    jCodecName,
                                    jLanguage,
                                    stream->disposition,
                                    parameters->bit_rate,
                                    jSampleFormat,
                                    parameters->sample_rate,
                                    parameters->ch_layout.nb_channels,
                                    jCodecName);
}

void onSubtitleStreamFound(JNIEnv *env, jobject jMediaInfoBuilder, AVFormatContext *avFormatContext, int index) {
    AVStream *stream = avFormatContext->streams[index];
    AVCodecParameters *parameters = stream->codecpar;

    auto codecDescriptor = avcodec_descriptor_get(parameters->codec_id);

    jstring jTitle = env->NewStringUTF(get_title(stream->metadata));
    jstring jCodecName = env->NewStringUTF(codecDescriptor->long_name);
    jstring jLanguage = env->NewStringUTF(get_language(stream->metadata));

    utils_call_instance_method_void(env,
                                    jMediaInfoBuilder,
                                    fields.MediaInfoBuilder.onSubtitleStreamFoundID,
                                    index,
                                    jTitle,
                                    jCodecName,
                                    jLanguage,
                                    stream->disposition);
}

void media_info_build(JNIEnv *env, jobject jMediaInfoBuilder, const char *uri) {
    AVFormatContext *avFormatContext = nullptr;
    if (int result = avformat_open_input(&avFormatContext, uri, nullptr, nullptr)) {
        LOGE("ERROR Could not open file %s - %s", uri, av_err2str(result));
        onError(env, jMediaInfoBuilder);
        return;
    }

    if (avformat_find_stream_info(avFormatContext, nullptr) < 0) {
        avformat_free_context(avFormatContext);
        LOGE("ERROR Could not get the stream info");
        onError(env, jMediaInfoBuilder);
        return;
    }

    onMediaInfoFound(env, jMediaInfoBuilder, avFormatContext);

    for (int pos = 0; pos < avFormatContext->nb_streams; pos++) {
        AVCodecParameters *parameters = avFormatContext->streams[pos]->codecpar;
        AVMediaType type = parameters->codec_type;
        switch (type) {
            case AVMEDIA_TYPE_VIDEO:
                onVideoStreamFound(env, jMediaInfoBuilder, avFormatContext, pos);
                break;
            case AVMEDIA_TYPE_AUDIO:
                onAudioStreamFound(env, jMediaInfoBuilder, avFormatContext, pos);
                break;
            case AVMEDIA_TYPE_SUBTITLE:
                onSubtitleStreamFound(env, jMediaInfoBuilder, avFormatContext, pos);
                break;
        }
    }
    avformat_free_context(avFormatContext);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaInfoBuilder_nativeCreateFromFD(JNIEnv *env, jobject thiz, jint file_descriptor) {
    char pipe[32];
    sprintf(pipe, "pipe:%d", file_descriptor);

    media_info_build(env, thiz, pipe);
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_mediainfo_MediaInfoBuilder_nativeCreateFromPath(JNIEnv *env, jobject thiz, jstring jFilePath) {
    const char *cFilePath = env->GetStringUTFChars(jFilePath, nullptr);

    media_info_build(env, thiz, cFilePath);

    env->ReleaseStringUTFChars(jFilePath, cFilePath);
}