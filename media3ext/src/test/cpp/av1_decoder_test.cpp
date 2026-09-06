// Decode a real AV1 file twice to cover decoder discovery, draining and seek/flush.
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
    AVCodecContext *context = avcodec_alloc_context3(codec);
    assert(context);
    assert(avcodec_parameters_to_context(context, input->streams[stream]->codecpar) == 0);
    context->thread_count = 4;
    assert(avcodec_open2(context, codec, nullptr) == 0);
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    assert(packet && frame);

    for (int pass = 0; pass < 2; pass++) {
        int frames = 0;
        auto receive = [&]() {
            int result;
            while ((result = avcodec_receive_frame(context, frame)) == 0) {
                assert(frame->width == 640 && frame->height == 360);
                const AVPixFmtDescriptor *format = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
                assert(format && format->comp[0].depth == depth);
                assert(frame->data[0] && frame->data[1] && frame->data[2]);
                frames++;
                av_frame_unref(frame);
            }
            assert(result == AVERROR(EAGAIN) || result == AVERROR_EOF);
            return result;
        };
        int result;
        while ((result = av_read_frame(input, packet)) >= 0) {
            if (packet->stream_index == stream) {
                assert(avcodec_send_packet(context, packet) == 0);
                receive();
            }
            av_packet_unref(packet);
        }
        assert(result == AVERROR_EOF);
        assert(avcodec_send_packet(context, nullptr) == 0);
        assert(receive() == AVERROR_EOF);
        assert(frames == expectedFrames);
        printf("PASS: libdav1d %d-bit, %d frames, %s\n", depth, frames, pass ? "after seek/flush" : "initial decode");
        if (pass == 0) {
            assert(av_seek_frame(input, stream, 0, AVSEEK_FLAG_BACKWARD) >= 0);
            avcodec_flush_buffers(context);
        }
    }
    av_frame_free(&frame);
    av_packet_free(&packet);
    avcodec_free_context(&context);
    avformat_close_input(&input);
}
