// Compare the bundled decoder's normal frame threading with the legacy low-delay
// configuration. Includes demuxing and optionally the production RGBA conversion.
#include "../../main/cpp/ffvideo.cpp"
extern "C" {
#include <libavformat/avformat.h>
}
#include <cassert>
#include <chrono>
#include <cstdio>

int main(int argc, char **argv) {
    assert(argc == 5); // file, threads, low_delay (0/1), convert_rgba (0/1)
    AVFormatContext *input = nullptr;
    assert(avformat_open_input(&input, argv[1], nullptr, nullptr) == 0);
    assert(avformat_find_stream_info(input, nullptr) >= 0);
    int stream = av_find_best_stream(input, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    assert(stream >= 0);
    auto *codec = const_cast<AVCodec *>(avcodec_find_decoder_by_name("libdav1d"));
    assert(codec);
    JNINativeInterface functions{};
    functions.FindClass = [](JNIEnv *, const char *) { return reinterpret_cast<jclass>(1); };
    functions.GetFieldID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jfieldID>(1); };
    functions.GetMethodID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jmethodID>(1); };
    functions.ExceptionCheck = [](JNIEnv *) -> jboolean { return false; };
    JNIEnv env{&functions};
    std::unique_ptr<JniContext> context(createVideoContext(&env, codec, nullptr, atoi(argv[2])));
    assert(context);
    if (atoi(argv[3])) {
        // Only difference from production: reopen with the previous LOW_DELAY flag.
        avcodec_free_context(&context->codecContext);
        context->codecContext = avcodec_alloc_context3(codec);
        assert(context->codecContext);
        context->codecContext->thread_count = atoi(argv[2]);
        context->codecContext->flags |= AV_CODEC_FLAG_LOW_DELAY;
        context->codecContext->err_recognition = AV_EF_IGNORE_ERR;
        assert(avcodec_open2(context->codecContext, codec, nullptr) == 0);
    }
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = nullptr;
    AVFrame *rgba = av_frame_alloc();
    assert(packet && rgba);
    bool convert = atoi(argv[4]);
    int frames = 0;
    double conversionMs = 0;
    auto receive = [&]() {
        int result = context->ReceiveFrame(&frame);
        if (!result) {
            frames++;
            if (convert) {
                if (!rgba->data[0]) {
                    rgba->width = frame->width;
                    rgba->height = frame->height;
                    rgba->format = AV_PIX_FMT_RGBA;
                    assert(av_frame_get_buffer(rgba, 32) == 0);
                }
                auto start = std::chrono::steady_clock::now();
                assert(context->renderContext.Convert(frame, AV_PIX_FMT_RGBA,
                        rgba->data, rgba->linesize, 0));
                conversionMs += std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - start).count();
            }
        }
        av_frame_free(&frame);
        return result;
    };
    auto start = std::chrono::steady_clock::now();
    int result;
    while ((result = av_read_frame(input, packet)) >= 0) {
        if (packet->stream_index == stream) {
            assert(context->SendPacket(packet) == VIDEO_DECODER_SUCCESS);
            int received = receive();
            assert(received == 0 || received == AVERROR(EAGAIN));
        }
        av_packet_unref(packet);
    }
    assert(result == AVERROR_EOF);
    assert(context->SendPacket(nullptr) == VIDEO_DECODER_SUCCESS);
    while ((result = receive()) == 0) {}
    assert(result == AVERROR_EOF);
    double ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
    printf("frames=%d threads=%s low_delay=%s rgba=%d elapsed_ms=%.3f fps=%.3f conversion_ms=%.3f\n",
            frames, argv[2], argv[3], convert, ms, frames * 1000.0 / ms, conversionMs);
    av_frame_free(&rgba);
    av_packet_free(&packet);
    avformat_close_input(&input);
}
