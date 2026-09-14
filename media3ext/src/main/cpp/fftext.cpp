#include <android/bitmap.h>
#include <ass/ass.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <string>
#include <vector>
#include <zlib.h>
#include "ffcommon.h"

namespace {
constexpr int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;
constexpr int MAX_DIMENSION = 4096;

struct BitmapSubtitle {
    AVSubtitle subtitle{};
    int64_t start = 0;
    int64_t end = 0;
    int width = 0;
    int height = 0;
    ~BitmapSubtitle() { avsubtitle_free(&subtitle); }
};

struct TextContext {
    ASS_Library *library = nullptr;
    ASS_Renderer *renderer = nullptr;
    ASS_Track *track = nullptr;
    AVCodecContext *codec = nullptr;
    bool embedded = false;
    bool changed = true;
    int width = 0;
    int height = 0;
    int64_t end = 0;
    std::unique_ptr<BitmapSubtitle> current;
    std::deque<std::unique_ptr<BitmapSubtitle>> pending;

    ~TextContext() {
        avcodec_free_context(&codec);
        if (track) ass_free_track(track);
        if (renderer) ass_renderer_done(renderer);
        if (library) ass_library_done(library);
    }
};

int64_t assTime(const std::string &value) {
    int h, m, s, cs, consumed = 0;
    if (sscanf(value.c_str(), "%d:%d:%d%*[:.]%d%n", &h, &m, &s, &cs, &consumed) != 4 ||
        consumed != value.size() || h < 0 || h > 100000 || m < 0 || m > 59 ||
        s < 0 || s > 59 || cs < 0 || cs > 99) return -1;
    return ((int64_t(h) * 60 + m) * 60 + s) * 1000 + cs * 10;
}

// Android ARGB_8888 memory is RGBA with premultiplied alpha.
void blend(uint8_t *pixel, int r, int g, int b, int alpha) {
    const int inverse = 255 - alpha;
    pixel[0] = (r * alpha + pixel[0] * inverse + 127) / 255;
    pixel[1] = (g * alpha + pixel[1] * inverse + 127) / 255;
    pixel[2] = (b * alpha + pixel[2] * inverse + 127) / 255;
    pixel[3] = alpha + (pixel[3] * inverse + 127) / 255;
}

void assLog(int level, const char *format, va_list args, void *) {
    if (level <= 2) __android_log_vprint(ANDROID_LOG_WARN, "libass", format, args);
}

bool decodeAss(TextContext &context, const uint8_t *data, int length, int64_t time, int64_t offset) {
    if (context.embedded) {
        // MatroskaExtractor prefixes the original ASS chunk with two relative timestamps.
        std::string sample(reinterpret_cast<const char *>(data), length);
        if (sample.compare(0, 10, "Dialogue: ") != 0) return false;
        auto first = sample.find(',', 10);
        auto second = first == std::string::npos ? first : sample.find(',', first + 1);
        if (second == std::string::npos) return false;
        int64_t start = assTime(sample.substr(10, first - 10));
        int64_t end = assTime(sample.substr(first + 1, second - first - 1));
        if (start < 0 || end <= start) return false;
        ass_process_chunk(context.track, sample.data() + second + 1, length - second - 1,
                          offset + start, end - start);
        context.end = std::max(context.end, offset + end);
    } else {
        ASS_Track *track = ass_read_memory(context.library,
                reinterpret_cast<char *>(const_cast<uint8_t *>(data)), length, nullptr);
        if (!track) return false;
        ass_free_track(context.track);
        context.track = track;
        context.end = time;
        for (int i = 0; i < track->n_events; ++i) {
            track->events[i].Start += offset;
            context.end = std::max(context.end, int64_t(track->events[i].Start + track->events[i].Duration));
        }
    }
    return true;
}

bool decodeBitmap(TextContext &context, const uint8_t *data, int length, int64_t time) {
    // Some Matroska PGS tracks use zlib compression inside the subtitle sample.
    std::vector<uint8_t> inflated;
    if (context.codec->codec_id == AV_CODEC_ID_HDMV_PGS_SUBTITLE && length > 1 &&
        data[0] == 0x78 && ((int(data[0]) << 8) + data[1]) % 31 == 0) {
        uLongf size = 64 * 1024;
        int result;
        do {
            inflated.resize(size);
            uLongf actual = size;
            result = uncompress(inflated.data(), &actual, data, length);
            if (result == Z_OK) { inflated.resize(actual); break; }
            size *= 2;
        } while (result == Z_BUF_ERROR && size <= MAX_SAMPLE_BYTES);
        if (result != Z_OK) return false;
        data = inflated.data();
        length = inflated.size();
    }
    AVPacket *packet = av_packet_alloc();
    if (!packet) return false;
    if (av_new_packet(packet, length) < 0) { av_packet_free(&packet); return false; }
    memcpy(packet->data, data, length);
    packet->pts = time * 1000;
    packet->dts = packet->pts;
    auto subtitle = std::make_unique<BitmapSubtitle>();
    int got = 0;
    int result = avcodec_decode_subtitle2(context.codec, &subtitle->subtitle, &got, packet);
    av_packet_free(&packet);
    if (result < 0) return false;
    if (!got) return true;
    subtitle->width = context.codec->width;
    subtitle->height = context.codec->height;
    if (subtitle->width <= 0 || subtitle->height <= 0 ||
        subtitle->width > MAX_DIMENSION || subtitle->height > MAX_DIMENSION) return false;
    auto &sub = subtitle->subtitle;
    subtitle->start = time + sub.start_display_time;
    subtitle->end = sub.end_display_time == UINT32_MAX ? INT64_MAX : time + sub.end_display_time;
    context.end = std::max(context.end, subtitle->end == INT64_MAX ? subtitle->start : subtitle->end);
    context.pending.push_back(std::move(subtitle));
    return true;
}
} // namespace

#define JNI_METHOD(name) Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegSubtitleDecoder_##name

extern "C" JNIEXPORT jlong JNICALL
JNI_METHOD(nativeCreate)(JNIEnv *env, jobject, jstring codecName, jbyteArray header, jstring configPath, jobjectArray fonts) try {
    const char *name = env->GetStringUTFChars(codecName, nullptr);
    if (!name) return 0;
    bool ass = strcmp(name, "ass") == 0;
    const AVCodec *codec = ass ? nullptr : avcodec_find_decoder_by_name(name);
    env->ReleaseStringUTFChars(codecName, name);
    int headerSize = env->GetArrayLength(header);
    if (headerSize > MAX_SAMPLE_BYTES) return 0;
    std::vector<char> extra(headerSize + AV_INPUT_BUFFER_PADDING_SIZE, 0);
    env->GetByteArrayRegion(header, 0, headerSize, reinterpret_cast<jbyte *>(extra.data()));
    if (env->ExceptionCheck()) return 0;
    auto context = std::make_unique<TextContext>();
    if (ass) {
        context->library = ass_library_init();
        if (!context->library) return 0;
        ass_set_message_cb(context->library, assLog, nullptr);
        ass_set_extract_fonts(context->library, 1);
        const int count = env->GetArrayLength(fonts);
        if (count > 64) return 0;
        int total = 0;
        for (int i = 0; i < count; ++i) {
            auto font = static_cast<jbyteArray>(env->GetObjectArrayElement(fonts, i));
            if (!font || env->ExceptionCheck()) return 0;
            const int size = env->GetArrayLength(font);
            if (size <= 0 || size > MAX_SAMPLE_BYTES || size > 32 * 1024 * 1024 - total) {
                env->DeleteLocalRef(font);
                return 0;
            }
            std::vector<char> bytes(size);
            env->GetByteArrayRegion(font, 0, size, reinterpret_cast<jbyte *>(bytes.data()));
            env->DeleteLocalRef(font);
            if (env->ExceptionCheck()) return 0;
            ass_add_font(context->library, std::to_string(i).c_str(), bytes.data(), size);
            total += size;
        }
        context->renderer = ass_renderer_init(context->library);
        context->track = ass_new_track(context->library);
        if (!context->renderer || !context->track) return 0;
        ass_set_cache_limits(context->renderer, 1000, 32);
        const char *config = configPath ? env->GetStringUTFChars(configPath, nullptr) : nullptr;
        ass_set_fonts(context->renderer, nullptr, "sans-serif", ASS_FONTPROVIDER_FONTCONFIG, config, 1);
        if (config) env->ReleaseStringUTFChars(configPath, config);
        context->embedded = headerSize > 0;
        if (headerSize) ass_process_codec_private(context->track, extra.data(), headerSize);
        // ponytail: libass retains events for this stream; upgrade to its pruning API for long live ASS feeds.
    } else {
        if (!codec) return 0;
        context->codec = avcodec_alloc_context3(codec);
        if (!context->codec) return 0;
        context->codec->pkt_timebase = {1, 1000000};
        context->codec->max_pixels = MAX_DIMENSION * MAX_DIMENSION;
        if (headerSize) {
            context->codec->extradata = static_cast<uint8_t *>(av_mallocz(extra.size()));
            if (!context->codec->extradata) return 0;
            memcpy(context->codec->extradata, extra.data(), headerSize);
            context->codec->extradata_size = headerSize;
        }
        if (avcodec_open2(context->codec, codec, nullptr) < 0) return 0;
    }
    return reinterpret_cast<jlong>(context.release());
} catch (const std::bad_alloc &) {
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
JNI_METHOD(nativeDecode)(JNIEnv *env, jobject, jlong handle, jobject buffer, jint length, jlong time, jlong offset) try {
    auto *context = reinterpret_cast<TextContext *>(handle);
    auto *data = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!context || !data || length <= 0 || length > MAX_SAMPLE_BYTES ||
        length > env->GetDirectBufferCapacity(buffer)) return false;
    return context->renderer ? decodeAss(*context, data, length, time, offset)
                             : decodeBitmap(*context, data, length, time);
} catch (const std::bad_alloc &) {
    return false;
}

extern "C" JNIEXPORT jobject JNICALL
JNI_METHOD(nativeRender)(JNIEnv *env, jobject, jlong handle, jlong time, jint width, jint height, jintArray frame) {
    auto *context = reinterpret_cast<TextContext *>(handle);
    jint info[7] = {};
    if (!context || width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
        env->GetArrayLength(frame) < 7) return nullptr;
    ASS_Image *images = nullptr;
    bool changed = context->changed;
    if (context->renderer) {
        if (width != context->width || height != context->height) {
            ass_set_frame_size(context->renderer, width, height);
            ass_set_storage_size(context->renderer, width, height);
            context->width = width;
            context->height = height;
            changed = true;
        }
        int assChanged = 0;
        images = ass_render_frame(context->renderer, context->track, time, &assChanged);
        changed |= assChanged != 0;
    } else {
        while (!context->pending.empty() && context->pending.front()->start <= time) {
            context->current = std::move(context->pending.front());
            context->pending.pop_front();
            changed = true;
        }
        if (context->current && context->current->end <= time) {
            context->current.reset();
            changed = true;
        }
        if (context->current) {
            width = context->current->width;
            height = context->current->height;
        }
    }
    if (!changed) {
        env->SetIntArrayRegion(frame, 0, 7, info);
        return nullptr;
    }
    context->changed = false;
    int left = width, top = height, right = 0, bottom = 0;
    auto bounds = [&](int x, int y, int w, int h) {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || w > width - x || h > height - y) return;
        left = std::min(left, x); top = std::min(top, y);
        right = std::max(right, x + w); bottom = std::max(bottom, y + h);
    };
    for (auto *im = images; im; im = im->next) bounds(im->dst_x, im->dst_y, im->w, im->h);
    if (!context->renderer && context->current) {
        auto &sub = context->current->subtitle;
        for (unsigned i = 0; i < sub.num_rects; ++i) {
            auto *r = sub.rects[i];
            if (r->type == SUBTITLE_BITMAP && r->data[0] && r->data[1]) bounds(r->x, r->y, r->w, r->h);
        }
    }
    info[0] = 1; info[1] = left; info[2] = top; info[3] = right - left; info[4] = bottom - top;
    info[5] = width; info[6] = height;
    env->SetIntArrayRegion(frame, 0, 7, info);
    if (right <= left || bottom <= top) return nullptr;
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configClass, argb);
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, right - left, bottom - top, config);
    env->DeleteLocalRef(config); env->DeleteLocalRef(configClass); env->DeleteLocalRef(bitmapClass);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo bitmapInfo;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    memset(pixels, 0, bitmapInfo.stride * bitmapInfo.height);
    auto row = [&](int x, int y) {
        return static_cast<uint8_t *>(pixels) + (y - top) * bitmapInfo.stride + (x - left) * 4;
    };
    for (auto *im = images; im; im = im->next) {
        if (im->w <= 0 || im->h <= 0 || im->dst_x < left || im->dst_y < top ||
            im->w > right - im->dst_x || im->h > bottom - im->dst_y) continue;
        for (int y = 0; y < im->h; ++y) {
            auto *dst = row(im->dst_x, im->dst_y + y);
            for (int x = 0; x < im->w; ++x) {
                int alpha = (im->bitmap[y * im->stride + x] * (255 - (im->color & 255)) + 127) / 255;
                blend(dst + x * 4, im->color >> 24, (im->color >> 16) & 255, (im->color >> 8) & 255, alpha);
            }
        }
    }
    if (!context->renderer && context->current) {
        auto &sub = context->current->subtitle;
        for (unsigned i = 0; i < sub.num_rects; ++i) {
            auto *r = sub.rects[i];
            if (r->type != SUBTITLE_BITMAP || !r->data[0] || !r->data[1] || r->x < left || r->y < top ||
                r->w <= 0 || r->h <= 0 || r->w > right - r->x || r->h > bottom - r->y || r->linesize[0] < r->w) continue;
            auto *palette = reinterpret_cast<uint32_t *>(r->data[1]);
            for (int y = 0; y < r->h; ++y) {
                auto *dst = row(r->x, r->y + y);
                for (int x = 0; x < r->w; ++x) {
                    int index = r->data[0][y * r->linesize[0] + x];
                    if (index >= r->nb_colors) continue;
                    uint32_t color = palette[index];
                    blend(dst + x * 4, (color >> 16) & 255, (color >> 8) & 255, color & 255, color >> 24);
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

extern "C" JNIEXPORT jlong JNICALL
JNI_METHOD(nativeEndTimeMs)(JNIEnv *, jobject, jlong handle) {
    auto *context = reinterpret_cast<TextContext *>(handle);
    return context ? context->end : 0;
}

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(nativeRelease)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<TextContext *>(handle);
}
