// Exercise production initialization, delayed output, EOS draining and reset.
#include "../../main/cpp/ffvideo.cpp"
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/pixdesc.h>
}
#include <cassert>
#include <cstdio>
#include <cstdlib>

int main(int argc, char **argv) {
    assert(argc == 4); // file, expected bit depth, expected frame count
    const int depth = atoi(argv[2]);
    const int expectedFrames = atoi(argv[3]);
    const AVCodec *codec = avcodec_find_decoder_by_name("libdav1d");
    assert(codec && codec->id == AV_CODEC_ID_AV1);
    assert(avcodec_find_decoder(AV_CODEC_ID_AV1) == codec);

    AVFormatContext *input = nullptr;
    assert(avformat_open_input(&input, argv[1], nullptr, nullptr) == 0);
    assert(avformat_find_stream_info(input, nullptr) >= 0);
    int stream = av_find_best_stream(input, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    assert(stream >= 0);
    // Only JNI metadata lookup is stubbed; FFmpeg and dav1d run normally.
    JNINativeInterface functions{};
    functions.FindClass = [](JNIEnv *, const char *) { return reinterpret_cast<jclass>(1); };
    functions.GetFieldID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jfieldID>(1); };
    functions.GetMethodID = [](JNIEnv *, jclass, const char *, const char *) { return reinterpret_cast<jmethodID>(1); };
    functions.ExceptionCheck = [](JNIEnv *) -> jboolean { return false; };
    JNIEnv env{&functions};
    std::unique_ptr<JniContext> jniContext(createVideoContext(&env, const_cast<AVCodec *>(codec), nullptr, 4));
    assert(jniContext);
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = nullptr;
    assert(packet);

    for (int pass = 0; pass < 2; pass++) {
        int frames = 0;
        int delayed = 0;
        int64_t previousPts = AV_NOPTS_VALUE;
        auto receive = [&]() {
            int result = jniContext->ReceiveFrame(&frame);
            if (result == 0) {
                assert(frame->width == 640 && frame->height == 360);
                const AVPixFmtDescriptor *format = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
                assert(format && format->comp[0].depth == depth);
                assert(frame->data[0] && frame->data[1] && frame->data[2]);
                assert(previousPts == AV_NOPTS_VALUE || frame->pts > previousPts);
                previousPts = frame->pts;
                frames++;
            }
            av_frame_free(&frame);
            return result;
        };
        int result;
        while ((result = av_read_frame(input, packet)) >= 0) {
            if (packet->stream_index == stream) {
                assert(jniContext->SendPacket(packet) == VIDEO_DECODER_SUCCESS);
                int received = receive();
                assert(received == 0 || received == AVERROR(EAGAIN));
            }
            av_packet_unref(packet);
        }
        assert(result == AVERROR_EOF);
        assert(jniContext->SendPacket(nullptr) == VIDEO_DECODER_SUCCESS);
        while ((result = receive()) == 0) delayed++;
        assert(result == AVERROR_EOF);
        assert(delayed > 0); // This must exercise frame threading, not low-delay mode.
        assert(frames == expectedFrames);
        printf("PASS: libdav1d %d-bit, %d frames (%d drained), %s\n", depth, frames, delayed, pass ? "after seek/flush" : "initial decode");
        if (pass == 0) {
            assert(av_seek_frame(input, stream, 0, AVSEEK_FLAG_BACKWARD) >= 0);
            assert(Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReset(
                    &env, nullptr, reinterpret_cast<jlong>(jniContext.get())) != 0);
        }
    }
    av_packet_free(&packet);
    avformat_close_input(&input);
}
