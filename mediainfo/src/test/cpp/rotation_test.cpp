#include <cassert>
#include <cstdio>

#include "../../main/cpp/media_thumbnail_retriever.cpp"

int main() {
    AVFormatContext *format = avformat_alloc_context();
    assert(format);
    AVStream *stream = avformat_new_stream(format, nullptr);
    assert(stream);
    assert(read_rotation_degrees(nullptr) == 0);
    assert(read_rotation_degrees(stream) == 0);

    av_dict_set(&stream->metadata, "rotate", "-90", 0);
    assert(read_rotation_degrees(stream) == 270);

    AVPacketSideData *matrix = av_packet_side_data_new(
            &stream->codecpar->coded_side_data, &stream->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX, 9 * sizeof(int32_t), 0);
    assert(matrix);
    av_display_rotation_set(reinterpret_cast<int32_t *>(matrix->data), 90);
    assert(read_rotation_degrees(stream) == 90);
    av_display_rotation_set(reinterpret_cast<int32_t *>(matrix->data), 180);
    assert(read_rotation_degrees(stream) == 180);

    // Truncated side data must not be read as a 3x3 display matrix.
    matrix->size = sizeof(int32_t);
    assert(read_rotation_degrees(stream) == 270);
    av_dict_set(&stream->metadata, "rotate", nullptr, 0);
    assert(read_rotation_degrees(stream) == 0);

    avformat_free_context(format);
    puts("Rotation metadata checks passed");
}
