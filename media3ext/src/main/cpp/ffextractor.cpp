#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/display.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/intreadwrite.h>
#include <libavutil/mastering_display_metadata.h>
}

namespace {
constexpr int kBufferSize = 64 * 1024;
constexpr int kMaxPacket = 32 * 1024 * 1024;
constexpr int64_t kTimeUnset = INT64_MIN + 1;

struct TrueHdClock {
    int64_t anchorUs = AV_NOPTS_VALUE;
    int64_t accessUnits = 0;

    int64_t timestamp(int64_t pts, AVRational timeBase, int sampleRate) {
        if (pts != AV_NOPTS_VALUE) {
            anchorUs = av_rescale_q(pts, timeBase, AV_TIME_BASE_Q);
            accessUnits = 0;
        }
        if (anchorUs == AV_NOPTS_VALUE || sampleRate <= 0) return anchorUs;
        // Each TrueHD access unit has 40 samples at the base rate, scaled along with the sample rate.
        int baseRate = sampleRate % 44100 == 0 ? 44100 : 48000;
        return av_sat_add64(anchorUs, av_rescale_q(accessUnits++, {40, baseRate}, AV_TIME_BASE_Q));
    }
};

struct Demuxer {
    JNIEnv *env;
    jobject owner;
    jmethodID readInput, seekInput, sample;
    AVFormatContext *format = nullptr;
    AVIOContext *io = nullptr;
    AVPacket *packet = nullptr;
    std::vector<bool> supported;
    std::vector<TrueHdClock> trueHdClocks;
    int64_t startUs = 0;
    ~Demuxer() {
        av_packet_free(&packet);
        avformat_close_input(&format);
        if (io) av_freep(&io->buffer);
        avio_context_free(&io);
        env->DeleteGlobalRef(owner);
    }
};

void fail(JNIEnv *env, const char *message, int error = 0) {
    if (env->ExceptionCheck()) return;
    char detail[AV_ERROR_MAX_STRING_SIZE] = {};
    if (error) av_strerror(error, detail, sizeof(detail));
    char text[512];
    snprintf(text, sizeof(text), "%s: %s", message, detail);
    jclass type = env->FindClass("java/io/IOException");
    env->ThrowNew(type, text);
    env->DeleteLocalRef(type);
}

int readInput(void *opaque, uint8_t *buffer, int size) {
    auto *d = static_cast<Demuxer *>(opaque);
    JNIEnv *env = d->env;
    if (env->ExceptionCheck()) return AVERROR_EXTERNAL;
    jbyteArray bytes = env->NewByteArray(size);
    if (!bytes) return AVERROR(ENOMEM);
    int count = env->CallIntMethod(d->owner, d->readInput, bytes, size);
    if (!env->ExceptionCheck() && count > 0 && count <= size) {
        env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte *>(buffer));
    }
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) return AVERROR_EXTERNAL;
    return count > 0 && count <= size ? count : count == -1 ? AVERROR_EOF : AVERROR(EIO);
}

int64_t seekInput(void *opaque, int64_t offset, int whence) {
    auto *d = static_cast<Demuxer *>(opaque);
    JNIEnv *env = d->env;
    if (env->ExceptionCheck()) return AVERROR_EXTERNAL;
    if (whence == AVSEEK_SIZE) {
        int64_t length = env->CallLongMethod(d->owner, d->seekInput, static_cast<jlong>(-1));
        return env->ExceptionCheck() ? AVERROR_EXTERNAL : length < 0 ? AVERROR(ENOSYS) : length;
    }
    whence &= ~AVSEEK_FORCE;
    if (whence != SEEK_SET || offset < 0) return AVERROR(ENOSYS);
    int64_t result = env->CallLongMethod(d->owner, d->seekInput, static_cast<jlong>(offset));
    return env->ExceptionCheck() ? AVERROR_EXTERNAL : result;
}

const char *mime(AVCodecID id) {
    switch (id) {
        case AV_CODEC_ID_H264: return "video/avc";
        case AV_CODEC_ID_HEVC: return "video/hevc";
        case AV_CODEC_ID_MPEG2VIDEO: return "video/mpeg2";
        case AV_CODEC_ID_MPEG4: return "video/mp4v-es";
        case AV_CODEC_ID_VP8: return "video/x-vnd.on2.vp8";
        case AV_CODEC_ID_VP9: return "video/x-vnd.on2.vp9";
        case AV_CODEC_ID_AV1: return "video/av01";
        case AV_CODEC_ID_AAC: return "audio/mp4a-latm";
        case AV_CODEC_ID_MP3: return "audio/mpeg";
        case AV_CODEC_ID_MP2: return "audio/mpeg-L2";
        case AV_CODEC_ID_OPUS: return "audio/opus";
        case AV_CODEC_ID_VORBIS: return "audio/vorbis";
        case AV_CODEC_ID_FLAC: return "audio/flac";
        case AV_CODEC_ID_ALAC: return "audio/alac";
        case AV_CODEC_ID_TRUEHD:
        case AV_CODEC_ID_MLP: return "audio/true-hd";
        case AV_CODEC_ID_AMR_NB: return "audio/3gpp";
        case AV_CODEC_ID_AMR_WB: return "audio/amr-wb";
        case AV_CODEC_ID_PCM_MULAW: return "audio/g711-mlaw";
        case AV_CODEC_ID_PCM_ALAW: return "audio/g711-alaw";
        case AV_CODEC_ID_PCM_U8:
        case AV_CODEC_ID_PCM_S16LE:
        case AV_CODEC_ID_PCM_S24LE:
        case AV_CODEC_ID_PCM_S32LE:
        case AV_CODEC_ID_PCM_F32LE: return "audio/raw";
        case AV_CODEC_ID_AC3: return "audio/ac3";
        case AV_CODEC_ID_EAC3: return "audio/eac3";
        case AV_CODEC_ID_DTS: return "audio/vnd.dts";
        case AV_CODEC_ID_ASS:
        case AV_CODEC_ID_SSA: return "text/x-ssa";
        case AV_CODEC_ID_SUBRIP: return "application/x-subrip";
        case AV_CODEC_ID_HDMV_PGS_SUBTITLE: return "application/pgs";
        case AV_CODEC_ID_DVD_SUBTITLE: return "application/vobsub";
        case AV_CODEC_ID_DVB_SUBTITLE: return "application/dvbsubs";
        default: return nullptr;
    }
}

const AVPacketSideData *sideData(const AVCodecParameters *params, AVPacketSideDataType type) {
    return av_packet_side_data_get(params->coded_side_data, params->nb_coded_side_data, type);
}

bool hasHdr10DolbyVisionBaseLayer(const AVCodecParameters *params, const AVPacketSideData *side) {
    if (!side || side->size < sizeof(AVDOVIDecoderConfigurationRecord) ||
        params->codec_id != AV_CODEC_ID_HEVC || params->color_primaries != AVCOL_PRI_BT2020 ||
        params->color_range != AVCOL_RANGE_MPEG || params->color_trc != AVCOL_TRC_SMPTE2084) {
        return false;
    }
    const auto *config = reinterpret_cast<const AVDOVIDecoderConfigurationRecord *>(side->data);
    return config->dv_profile == 7 && config->bl_present_flag && config->dv_bl_signal_compatibility_id == 6;
}

jbyteArray hdrStaticInfo(JNIEnv *env, const AVCodecParameters *params) {
    const auto *side = sideData(params, AV_PKT_DATA_MASTERING_DISPLAY_METADATA);
    if (!side || side->size < sizeof(AVMasteringDisplayMetadata)) return nullptr;
    const auto *mastering = reinterpret_cast<const AVMasteringDisplayMetadata *>(side->data);
    if (!mastering->has_primaries || !mastering->has_luminance) return nullptr;

    // Android uses the little-endian 25-byte HDR static metadata type 1 descriptor.
    uint8_t bytes[25] = {};
    auto write = [&bytes](int field, AVRational value, int scale) {
        if (value.num < 0 || value.den <= 0) return;
        const auto scaled = std::min<int64_t>(av_rescale(value.num, scale, value.den), UINT16_MAX);
        AV_WL16(bytes + 1 + field * 2, scaled);
    };
    for (int i = 0; i < 3; i++) {
        write(i * 2, mastering->display_primaries[i][0], 50000);
        write(i * 2 + 1, mastering->display_primaries[i][1], 50000);
    }
    write(6, mastering->white_point[0], 50000);
    write(7, mastering->white_point[1], 50000);
    write(8, mastering->max_luminance, 1);
    write(9, mastering->min_luminance, 10000);
    side = sideData(params, AV_PKT_DATA_CONTENT_LIGHT_LEVEL);
    if (side && side->size >= sizeof(AVContentLightMetadata)) {
        const auto *light = reinterpret_cast<const AVContentLightMetadata *>(side->data);
        AV_WL16(bytes + 21, std::min<unsigned>(light->MaxCLL, UINT16_MAX));
        AV_WL16(bytes + 23, std::min<unsigned>(light->MaxFALL, UINT16_MAX));
    }
    jbyteArray data = env->NewByteArray(sizeof(bytes));
    if (data) env->SetByteArrayRegion(data, 0, sizeof(bytes), reinterpret_cast<const jbyte *>(bytes));
    return data;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegProgressiveMediaExtractor_nativeOpen(
        JNIEnv *env, jobject owner) {
    auto *d = new Demuxer();
    d->env = env;
    d->owner = env->NewGlobalRef(owner);
    jclass cls = env->GetObjectClass(owner);
    d->readInput = env->GetMethodID(cls, "readInput", "([BI)I");
    d->seekInput = env->GetMethodID(cls, "seekInput", "(J)J");
    d->sample = env->GetMethodID(cls, "onSample", "(IJJI[B)V");
    jmethodID track = env->GetMethodID(cls, "onTrack", "(ILjava/lang/String;IIIIII[BLjava/lang/String;)V");
    jmethodID videoFormat = env->GetMethodID(cls, "onVideoFormat", "(IIFIII[B)V");
    jmethodID ended = env->GetMethodID(cls, "onTracksEnded", "(JZ)V");
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) { delete d; return 0; }
    auto *buffer = static_cast<uint8_t *>(av_malloc(kBufferSize));
    d->io = buffer ? avio_alloc_context(buffer, kBufferSize, 0, d, readInput, nullptr, seekInput) : nullptr;
    d->format = avformat_alloc_context();
    d->packet = av_packet_alloc();
    if (!d->io || !d->format || !d->packet) {
        if (!d->io) av_free(buffer);
        fail(env, "Cannot allocate FFmpeg extractor"); delete d; return 0;
    }
    d->format->pb = d->io;
    d->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    d->format->probesize = 4 * 1024 * 1024;
    d->format->max_analyze_duration = 5 * AV_TIME_BASE;
    d->format->max_streams = 128;
    AVDictionary *options = nullptr;
    // Only self-contained containers; no playlists or demuxer-created external network/file reads.
    av_dict_set(&options, "format_whitelist", "mov,matroska,webm,mpeg,mpegts,avi,asf,flv,ogg,wav,mp3,aac,flac,h264,hevc", 0);
    av_dict_set(&options, "protocol_whitelist", "none", 0);
    int result = avformat_open_input(&d->format, nullptr, nullptr, &options);
    av_dict_free(&options);
    if (result >= 0 && !env->ExceptionCheck()) result = avformat_find_stream_info(d->format, nullptr);
    if (result < 0 || env->ExceptionCheck()) {
        fail(env, "Cannot probe FFmpeg container", result); delete d; return 0;
    }
    d->startUs = d->format->start_time == AV_NOPTS_VALUE ? 0 : d->format->start_time;
    cls = env->GetObjectClass(owner);
    jmethodID font = env->GetMethodID(cls, "onFont", "([B)V");
    env->DeleteLocalRef(cls);
    int fontBytes = 0;
    for (unsigned i = 0; i < d->format->nb_streams && !env->ExceptionCheck(); i++) {
        AVCodecParameters *p = d->format->streams[i]->codecpar;
        if (p->codec_type != AVMEDIA_TYPE_ATTACHMENT || p->extradata_size <= 0 ||
            p->extradata_size > 16 * 1024 * 1024 || p->extradata_size > 32 * 1024 * 1024 - fontBytes) continue;
        jbyteArray bytes = env->NewByteArray(p->extradata_size);
        if (!bytes) break;
        env->SetByteArrayRegion(bytes, 0, p->extradata_size, reinterpret_cast<jbyte *>(p->extradata));
        env->CallVoidMethod(owner, font, bytes);
        env->DeleteLocalRef(bytes);
        fontBytes += p->extradata_size;
    }
    d->supported.resize(d->format->nb_streams, false);
    d->trueHdClocks.resize(d->format->nb_streams);
    for (unsigned i = 0; i < d->format->nb_streams && !env->ExceptionCheck(); i++) {
        AVStream *stream = d->format->streams[i];
        AVCodecParameters *p = stream->codecpar;
        if (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) continue;
        const auto *dolbyVision = sideData(p, AV_PKT_DATA_DOVI_CONF);
        const bool hdr10BaseLayer = hasHdr10DolbyVisionBaseLayer(p, dolbyVision);
        if (!hdr10BaseLayer && (dolbyVision || p->color_trc == AVCOL_TRC_SMPTE2084 ||
            p->color_trc == AVCOL_TRC_ARIB_STD_B67 || sideData(p, AV_PKT_DATA_MASTERING_DISPLAY_METADATA))) {
            fail(env, "HDR metadata is not supported by FFmpeg extraction; use HW"); break;
        }
        const char *type = mime(p->codec_id);
        if (!type) {
            if (p->codec_type == AVMEDIA_TYPE_AUDIO || p->codec_type == AVMEDIA_TYPE_VIDEO) {
                fail(env, "Unsupported FFmpeg extraction codec; use the standard reader"); break;
            }
            continue;
        }
        if (p->extradata_size < 0 || p->extradata_size > 1024 * 1024) {
            fail(env, "Oversized codec initialization data"); break;
        }
        jstring jmime = env->NewStringUTF(type);
        jbyteArray extra = env->NewByteArray(p->extradata_size);
        if (!jmime || !extra) break;
        if (p->extradata_size) env->SetByteArrayRegion(extra, 0, p->extradata_size, reinterpret_cast<jbyte *>(p->extradata));
        AVDictionaryEntry *language = av_dict_get(stream->metadata, "language", nullptr, 0);
        bool validLanguage = language && strnlen(language->value, 36) < 36;
        if (validLanguage) {
            for (const unsigned char *c = reinterpret_cast<const unsigned char *>(language->value); *c; c++) {
                if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') ||
                      (*c >= '0' && *c <= '9') || *c == '-' || *c == '_')) { validLanguage = false; break; }
            }
        }
        jstring lang = validLanguage ? env->NewStringUTF(language->value) : nullptr;
        int pcm = -1;
        switch (p->codec_id) {
            case AV_CODEC_ID_PCM_U8: pcm = 3; break;
            case AV_CODEC_ID_PCM_S16LE: pcm = 2; break;
            case AV_CODEC_ID_PCM_S24LE: pcm = 0x20000000; break;
            case AV_CODEC_ID_PCM_S32LE: pcm = 0x30000000; break;
            case AV_CODEC_ID_PCM_F32LE: pcm = 4; break;
            default: break;
        }
        env->CallVoidMethod(owner, track, static_cast<jint>(i), jmime, p->width, p->height,
                            p->ch_layout.nb_channels, p->sample_rate, pcm,
                            ((stream->disposition & AV_DISPOSITION_DEFAULT) ? 1 : 0) |
                            ((stream->disposition & AV_DISPOSITION_FORCED) ? 2 : 0), extra, lang);
        if (p->codec_type == AVMEDIA_TYPE_VIDEO && !env->ExceptionCheck()) {
            const AVPacketSideData *matrix = av_packet_side_data_get(p->coded_side_data,
                p->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
            double degrees = matrix && matrix->size >= 9 * sizeof(int32_t)
                ? -av_display_rotation_get(reinterpret_cast<const int32_t *>(matrix->data)) : 0;
            int rotation = std::isfinite(degrees) ? (static_cast<int>(std::round(degrees)) % 360 + 360) % 360 : 0;
            float ratio = p->sample_aspect_ratio.num > 0 && p->sample_aspect_ratio.den > 0
                ? static_cast<float>(av_q2d(p->sample_aspect_ratio)) : 0;
            jbyteArray hdr = hdr10BaseLayer ? hdrStaticInfo(env, p) : nullptr;
            if (!env->ExceptionCheck()) {
                env->CallVoidMethod(owner, videoFormat, static_cast<jint>(i), rotation, ratio,
                                   p->color_primaries, p->color_range, p->color_trc, hdr);
            }
            if (hdr) env->DeleteLocalRef(hdr);
        }
        env->DeleteLocalRef(jmime);
        env->DeleteLocalRef(extra);
        if (lang) env->DeleteLocalRef(lang);
        d->supported[i] = true;
    }
    if (!env->ExceptionCheck()) {
        int64_t duration = d->format->duration == AV_NOPTS_VALUE ? kTimeUnset : d->format->duration;
        env->CallVoidMethod(owner, ended, static_cast<jlong>(duration), static_cast<jboolean>(d->io->seekable != 0));
    }
    if (env->ExceptionCheck()) { delete d; return 0; }
    return reinterpret_cast<jlong>(d);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegProgressiveMediaExtractor_nativeRead(
        JNIEnv *env, jobject, jlong handle) {
    auto *d = reinterpret_cast<Demuxer *>(handle);
    d->env = env;
    av_packet_unref(d->packet);
    int result = av_read_frame(d->format, d->packet);
    if (env->ExceptionCheck()) return false;
    if (result == AVERROR_EOF) return false;
    if (result < 0) { fail(env, "Cannot read FFmpeg packet", result); return false; }
    AVPacket *p = d->packet;
    if (p->size < 0 || p->size > kMaxPacket) { fail(env, "Oversized FFmpeg packet"); return false; }
    if (p->stream_index < 0 || static_cast<size_t>(p->stream_index) >= d->supported.size()) {
        fail(env, "Invalid FFmpeg stream index"); return false;
    }
    if (!d->supported[p->stream_index] || !p->size) return true;
    if (av_packet_get_side_data(p, AV_PKT_DATA_ENCRYPTION_INFO, nullptr)) {
        fail(env, "Encrypted samples are not supported by FFmpeg extraction"); return false;
    }
    int64_t pts = p->pts == AV_NOPTS_VALUE ? p->dts : p->pts;
    AVStream *stream = d->format->streams[p->stream_index];
    int64_t timeUs = pts == AV_NOPTS_VALUE ? AV_NOPTS_VALUE : av_rescale_q(pts, stream->time_base, AV_TIME_BASE_Q);
    if (stream->codecpar->codec_id == AV_CODEC_ID_TRUEHD) {
        // Matroska lacing may timestamp only the first access unit in a block.
        timeUs = d->trueHdClocks[p->stream_index].timestamp(pts, stream->time_base, stream->codecpar->sample_rate);
    }
    if (timeUs == AV_NOPTS_VALUE) return true;
    jbyteArray bytes = env->NewByteArray(p->size);
    if (!bytes) return false;
    env->SetByteArrayRegion(bytes, 0, p->size, reinterpret_cast<jbyte *>(p->data));
    int64_t durationUs = av_rescale_q(p->duration, stream->time_base, AV_TIME_BASE_Q);
    env->CallVoidMethod(d->owner, d->sample, p->stream_index, static_cast<jlong>(av_sat_sub64(timeUs, d->startUs)), static_cast<jlong>(durationUs),
                       (p->flags & AV_PKT_FLAG_KEY) ? 1 : 0, bytes);
    env->DeleteLocalRef(bytes);
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegProgressiveMediaExtractor_nativeSeek(
        JNIEnv *env, jobject, jlong handle, jlong timeUs) {
    auto *d = reinterpret_cast<Demuxer *>(handle);
    d->env = env;
    int streamIndex = -1;
    for (unsigned i = 0; i < d->format->nb_streams; i++) {
        if (!d->supported[i]) continue;
        AVMediaType type = d->format->streams[i]->codecpar->codec_type;
        if (type == AVMEDIA_TYPE_VIDEO) { streamIndex = static_cast<int>(i); break; }
        if (streamIndex < 0 && type == AVMEDIA_TYPE_AUDIO) streamIndex = static_cast<int>(i);
    }
    if (streamIndex < 0) { fail(env, "No seekable media track"); return; }
    int64_t timestamp = av_rescale_q(timeUs + d->startUs, AV_TIME_BASE_Q,
                                    d->format->streams[streamIndex]->time_base);
    int result = av_seek_frame(d->format, streamIndex, timestamp, AVSEEK_FLAG_BACKWARD);
    if (result < 0) fail(env, "Cannot seek FFmpeg container", result);
    else std::fill(d->trueHdClocks.begin(), d->trueHdClocks.end(), TrueHdClock{});
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegProgressiveMediaExtractor_nativeRelease(
        JNIEnv *env, jobject, jlong handle) {
    auto *d = reinterpret_cast<Demuxer *>(handle);
    d->env = env;
    delete d;
}
