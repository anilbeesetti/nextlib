// Exercise the production JNI entry point with real codecs; only Java objects are stubbed.
#include "../../main/cpp/ffvideo.cpp"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

static bool copyException;
static JNINativeInterface functions{};
static JNIEnv env{&functions};

static std::unique_ptr<JniContext> Initialize(const char *name, std::vector<uint8_t> *extra,
                                            int width, int height) {
    return std::unique_ptr<JniContext>(reinterpret_cast<JniContext *>(
            Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegInitialize(
                    &env, nullptr, reinterpret_cast<jstring>(const_cast<char *>(name)),
                    reinterpret_cast<jbyteArray>(extra), 1, width, height)));
}

int main(int argc, char **argv) {
    functions.GetStringUTFChars = [](JNIEnv *, jstring name, jboolean *) { return reinterpret_cast<const char *>(name); };
    functions.ReleaseStringUTFChars = [](JNIEnv *, jstring, const char *) {};
    functions.GetArrayLength = [](JNIEnv *, jarray data) -> jsize { return reinterpret_cast<std::vector<uint8_t> *>(data)->size(); };
    functions.GetByteArrayRegion = [](JNIEnv *, jbyteArray data, jsize start, jsize size, jbyte *output) {
        if (copyException) return;
        auto *bytes = reinterpret_cast<std::vector<uint8_t> *>(data);
        if (size) memcpy(output, bytes->data() + start, size);
    };
    functions.FindClass = [](JNIEnv *, const char *) { return reinterpret_cast<jclass>(1); };
    functions.GetFieldID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jfieldID>(1); };
    functions.GetMethodID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jmethodID>(1); };
    functions.ExceptionCheck = [](JNIEnv *) -> jboolean { return copyException; };

    assert(!Initialize("nextlib_missing_decoder", nullptr, 320, 180));
    for (const char *name : {"h264", "hevc"}) {
        auto context = Initialize(name, nullptr, 320, 180);
        assert(context && context->codecContext->width == 320 && context->codecContext->height == 180);
        context = Initialize(name, nullptr, -1, -1);
        assert(context && !context->codecContext->width && !context->codecContext->height);
        context = Initialize(name, nullptr, 0, -10);
        assert(context && !context->codecContext->width && !context->codecContext->height);
        // Invalid extreme dimensions must be rejected or cleared by libavcodec, never allocated.
        context = Initialize(name, nullptr, INT_MAX, INT_MAX);
        assert(!context || (!context->codecContext->width && !context->codecContext->height));
    }
    std::vector<uint8_t> empty;
    assert(Initialize("h264", &empty, 0, 0));
    std::vector<uint8_t> truncated{0, 0, 1, 0x67};
    auto context = Initialize("h264", &truncated, 320, 180);
    if (context) {
        assert(context->codecContext->extradata_size == truncated.size());
        assert(!memcmp(context->codecContext->extradata, truncated.data(), truncated.size()));
        for (int i = 0; i < AV_INPUT_BUFFER_PADDING_SIZE; i++) {
            assert(context->codecContext->extradata[truncated.size() + i] == 0);
        }
    }
    copyException = true;
    assert(!Initialize("h264", &truncated, 320, 180));
    copyException = false;
    puts("PASS: JNI discovery, known/unknown/extreme dimensions, empty/truncated extradata, padding and JNI copy failure");

    if (argc == 2) {
        // msmpeg4v3 is deliberately enabled only in the test FFmpeg build. Its frame headers
        // omit dimensions; it proves the container values are supplied before avcodec_open2.
        assert(avcodec_find_decoder_by_name("msmpeg4"));
        assert(!Initialize("msmpeg4", nullptr, 0, 0));
        assert(!Initialize("msmpeg4", nullptr, 320, 0));
        assert(!Initialize("msmpeg4", nullptr, -1, 180));
        auto decoder = Initialize("msmpeg4", nullptr, 320, 180);
        assert(decoder);
        std::ifstream input(argv[1], std::ios::binary);
        std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
        assert(!bytes.empty());
        AVPacket *packet = av_packet_alloc();
        assert(packet && av_new_packet(packet, bytes.size()) == 0);
        memcpy(packet->data, bytes.data(), bytes.size());
        for (int pass = 0; pass < 4; pass++) {
            assert(decoder->SendPacket(packet) == VIDEO_DECODER_SUCCESS);
            AVFrame *frame = nullptr;
            assert(decoder->ReceiveFrame(&frame) == 0);
            assert(frame->width == 320 && frame->height == 180 && frame->data[0]);
            av_frame_free(&frame);
            assert(Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReset(
                    &env, nullptr, reinterpret_cast<jlong>(decoder.get())));
        }
        av_packet_free(&packet);
        puts("PASS: dimension-dependent msmpeg4v3 initializes and decodes only with container dimensions, including three resets");
    }
}
