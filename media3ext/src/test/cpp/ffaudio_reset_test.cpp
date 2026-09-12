// Exercise the production TrueHD reset and shared context release with real decoding.
#include "../../main/cpp/ffaudio.cpp"
extern "C" {
#include <libavformat/avformat.h>
}
#include <cassert>
#include <cstdio>
#include <vector>

int main(int argc, char **argv) {
    assert(argc == 2);
    AVFormatContext *input = nullptr;
    assert(avformat_open_input(&input, argv[1], nullptr, nullptr) == 0);
    assert(avformat_find_stream_info(input, nullptr) >= 0);
    const int stream = av_find_best_stream(input, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    assert(stream >= 0 && input->streams[stream]->codecpar->codec_id == AV_CODEC_ID_TRUEHD);
    std::vector<AVPacket *> packets;
    AVPacket *packet = av_packet_alloc();
    assert(packet);
    int result;
    while ((result = av_read_frame(input, packet)) >= 0) {
        if (packet->stream_index == stream) {
            packets.push_back(av_packet_clone(packet));
            assert(packets.back());
        }
        av_packet_unref(packet);
    }
    assert(result == AVERROR_EOF && !packets.empty());
    av_packet_free(&packet);
    avformat_close_input(&input);

    auto *codec = const_cast<AVCodec *>(avcodec_find_decoder(AV_CODEC_ID_TRUEHD));
    assert(codec);
    size_t pcm16Bytes = 0;
    for (bool outputFloat : {false, true}) {
        AVCodecContext *context = createContext(nullptr, codec, nullptr, outputFloat, -1, -1);
        assert(context);
        std::vector<uint8_t> expected;
        for (int pass = 0; pass <= 32; ++pass) {
            assert(context->request_sample_fmt == (outputFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16));
            assert(context->opaque == nullptr);
            std::vector<uint8_t> decoded;
            uint8_t output[131072];
            for (AVPacket *sample : packets) {
                const int size = decodePacket(context, sample, output, sizeof(output), {});
                assert(size >= 0);
                decoded.insert(decoded.end(), output, output + size);
            }
            assert(!decoded.empty() && context->opaque);
            assert(context->sample_rate == 48000 && context->ch_layout.nb_channels == 2);
            if (pass == 0) expected = decoded;
            else assert(decoded == expected);
            if (pass == 32) break;
            context = reinterpret_cast<AVCodecContext *>(
                    Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegReset(
                            nullptr, nullptr, reinterpret_cast<jlong>(context), nullptr));
            assert(context);
        }
        if (outputFloat) assert(expected.size() == pcm16Bytes * 2);
        else pcm16Bytes = expected.size();
        Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegRelease(
                nullptr, nullptr, reinterpret_cast<jlong>(context));
        printf("PASS: TrueHD %s, 32 resets, identical PCM (%zu bytes per decode)\n",
               outputFloat ? "float" : "16-bit", expected.size());
    }
    for (AVPacket *sample : packets) av_packet_free(&sample);
    assert(Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegReset(
            nullptr, nullptr, 0, nullptr) == 0);
}
