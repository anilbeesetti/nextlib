// Runs against the bundled FFmpeg and a real Android BufferQueue. No test framework required.
#include <android/native_window_jni.h>
#include <media/NdkImageReader.h>
#include <cassert>
#include <cstdio>
extern "C" {
#include <libavcodec/avcodec.h>
}

static int SendForTest(AVCodecContext *, const AVPacket *);
static int ReceiveForTest(AVCodecContext *, AVFrame *);
void LogErrorForTest(const char *, int);

static int invalidBuffer = 0;
static int LockForTest(ANativeWindow *window, ANativeWindow_Buffer *buffer, ARect *dirty) {
    int result = ANativeWindow_lock(window, buffer, dirty);
    if (!result) {
        switch (invalidBuffer) {
            case 1: buffer->bits = nullptr; break;
            case 2: buffer->format = WINDOW_FORMAT_RGB_565; break;
            case 3: buffer->width = 1; break;
            case 4: buffer->height = 1; break;
            case 5: buffer->stride = 1; break;
        }
    }
    return result;
}

// Keep real window/decoder operations; wrappers observe backpressure and inject
// lock metadata faults or a receive failure only in their dedicated tests.
#define ANativeWindow_lock LockForTest
#define avcodec_send_packet SendForTest
#define avcodec_receive_frame ReceiveForTest
#define logError LogErrorForTest
#include "../../main/cpp/ffvideo.cpp"
#undef ANativeWindow_lock
#undef avcodec_send_packet
#undef avcodec_receive_frame
#undef logError

void logError(const char *, int);
static bool sendBlocked = false;
static int emptyReceivesAfterBlockedSend = 0;
static int injectedReceiveError = 0;
static const char *lastErrorFunction = nullptr;

static int SendForTest(AVCodecContext *context, const AVPacket *packet) {
    int result = injectedReceiveError ? AVERROR(EAGAIN) : avcodec_send_packet(context, packet);
    sendBlocked = result == AVERROR(EAGAIN);
    return result;
}

static int ReceiveForTest(AVCodecContext *context, AVFrame *frame) {
    int result = injectedReceiveError ? injectedReceiveError : avcodec_receive_frame(context, frame);
    injectedReceiveError = 0;
    if (sendBlocked && result == AVERROR(EAGAIN)) emptyReceivesAfterBlockedSend++;
    return result;
}

void LogErrorForTest(const char *function, int error) {
    lastErrorFunction = function;
    logError(function, error);
}

static AVFrame *MakeFrame() {
    AVFrame *frame = av_frame_alloc();
    assert(frame);
    frame->width = 128;
    frame->height = 64;
    frame->format = AV_PIX_FMT_YUV420P;
    frame->colorspace = AVCOL_SPC_UNSPECIFIED;
    frame->color_range = AVCOL_RANGE_MPEG;
    assert(av_frame_get_buffer(frame, 32) == 0);
    return frame;
}

static void Fill(AVFrame *frame, int y, int u, int v) {
    const int values[] = {y, u, v};
    for (int plane = 0; plane < 3; plane++) {
        memset(frame->data[plane], values[plane], frame->linesize[plane] *
                (plane == 0 ? frame->height : frame->height / 2));
    }
}

static void CheckColorsAndRange() {
    AVFrame *source = MakeFrame();
    AVFrame *dest = MakeFrame();
    ScaleContext scale;
    // In particular, exercise YUV420P + JPEG range, not the deprecated YUVJ420P format.
    for (AVColorRange range : {AVCOL_RANGE_MPEG, AVCOL_RANGE_JPEG, AVCOL_RANGE_MPEG}) {
        source->color_range = range;
        for (int white : {0, 1}) {
            Fill(source, range == AVCOL_RANGE_JPEG ? (white ? 255 : 0) : (white ? 235 : 16), 128, 128);
            SwsContext *context = scale.Get(source, AV_PIX_FMT_YUV420P);
            assert(context && context == scale.Get(source, AV_PIX_FMT_YUV420P));
            assert(sws_scale(context, source->data, source->linesize, 0, source->height,
                    dest->data, dest->linesize) == source->height);
            assert(dest->data[0][source->width / 2] == (white ? 235 : 16));
        }
    }
    uint8_t rgba[128 * 64 * 4];
    uint8_t *planes[] = {rgba, nullptr, nullptr, nullptr};
    int strides[] = {128 * 4, 0, 0, 0};
    for (AVColorSpace matrix : {AVCOL_SPC_UNSPECIFIED, AVCOL_SPC_BT709, AVCOL_SPC_SMPTE170M,
                               AVCOL_SPC_BT709, AVCOL_SPC_UNSPECIFIED}) {
        source->colorspace = matrix;
        const bool bt601 = matrix == AVCOL_SPC_SMPTE170M;
        Fill(source, bt601 ? 81 : 63, bt601 ? 90 : 102, 240);
        SwsContext *context = scale.Get(source, AV_PIX_FMT_RGBA);
        assert(context);
        assert(sws_scale(context, source->data, source->linesize, 0, source->height,
                planes, strides) == source->height);
        assert(rgba[0] >= 250 && rgba[1] <= 4 && rgba[2] <= 4);
        assert(GetOutputColorspace(matrix) == (bt601 ? 1 : matrix == AVCOL_SPC_BT709 ? 2 : 0));
    }
    av_frame_free(&source);
    av_frame_free(&dest);
    puts("PASS: unknown/BT.709/BT.601 matrices and full/limited range cache transitions");
}

static void CheckInvalidWindowBuffers() {
    JNINativeInterface functions{};
    functions.GetLongField = [](JNIEnv *, jobject buffer, jfieldID) -> jlong {
        return reinterpret_cast<jlong>(buffer);
    };
    functions.IsSameObject = [](JNIEnv *, jobject a, jobject b) -> jboolean { return a == b; };
    functions.DeleteGlobalRef = [](JNIEnv *, jobject) {};
    JNIEnv env{&functions};
    AVFrame *frame = MakeFrame();
    Fill(frame, 63, 102, 240);
    auto render = Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegRenderFrame;
    for (int fault = 1; fault <= 5; fault++) {
        AImageReader *reader = nullptr;
        assert(AImageReader_new(frame->width, frame->height, AIMAGE_FORMAT_RGBA_8888, 2, &reader) == AMEDIA_OK);
        ANativeWindow *window = nullptr;
        assert(AImageReader_getWindow(reader, &window) == AMEDIA_OK);
        JniContext context;
        auto surface = reinterpret_cast<jobject>(window);
        context.surface = surface;
        context.native_window = window;
        ANativeWindow_acquire(window);
        invalidBuffer = fault;
        assert(render(&env, nullptr, reinterpret_cast<jlong>(&context), surface,
                reinterpret_cast<jobject>(frame), frame->width, frame->height) == VIDEO_DECODER_ERROR_OTHER);
        AImage *image = nullptr;
        assert(AImageReader_acquireNextImage(reader, &image) == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE);
        assert(context.native_window == nullptr);

        // Reuse the same Surface after the error: it must be unlocked and reconnectable.
        invalidBuffer = 0;
        context.surface = surface;
        context.native_window = window;
        ANativeWindow_acquire(window);
        assert(render(&env, nullptr, reinterpret_cast<jlong>(&context), surface,
                reinterpret_cast<jobject>(frame), frame->width, frame->height) == VIDEO_DECODER_SUCCESS);
        assert(AImageReader_acquireNextImage(reader, &image) == AMEDIA_OK);
        uint8_t *pixels = nullptr;
        int length = 0;
        assert(AImage_getPlaneData(image, 0, &pixels, &length) == AMEDIA_OK);
        assert(pixels[0] >= 250 && pixels[1] <= 4 && pixels[2] <= 4);
        AImage_delete(image);
        context.ReleaseSurface(&env);
        AImageReader_delete(reader);
    }
    av_frame_free(&frame);
    puts("PASS: five invalid buffer cases publish no image; same Surface renders after each error");
}

static void CheckVpDecoders() {
    for (AVCodecID id : {AV_CODEC_ID_VP8, AV_CODEC_ID_VP9}) {
        const char *name = id == AV_CODEC_ID_VP8 ? "vp8" : "vp9";
        const AVCodec *codec = avcodec_find_decoder_by_name(name);
        assert(codec && codec->id == id);
        // Both Media3's name lookup and MediaInfo's codec-ID lookup use the native decoder.
        assert(avcodec_find_decoder(id) == codec);
        AVCodecContext *context = avcodec_alloc_context3(codec);
        assert(context && avcodec_open2(context, codec, nullptr) == 0);
        avcodec_free_context(&context);
    }
    assert(!avcodec_find_decoder_by_name("libvpx"));
    assert(!avcodec_find_decoder_by_name("libvpx-vp9"));
    puts("PASS: built-in VP8/VP9 decoders are available and libvpx decoders are absent");
}

static void CheckVp9PacketBackpressure(const char *filename, int frameCount, int width, int height) {
    JniContext context;
    const AVCodec *codec = avcodec_find_decoder_by_name("vp9");
    context.codecContext = avcodec_alloc_context3(codec);
    assert(context.codecContext);
    context.codecContext->thread_count = 4;
    assert(avcodec_open2(context.codecContext, codec, nullptr) == 0);

    FILE *input = fopen(filename, "rb");
    assert(input);
    for (int pass = 0; pass < 2; pass++) {
        assert(fseek(input, 32, SEEK_SET) == 0); // IVF file header.
        for (int index = 0; index < frameCount; index++) {
            uint8_t header[12];
            assert(fread(header, 1, sizeof(header), input) == sizeof(header));
            const int size = header[0] | (header[1] << 8) | (header[2] << 16) | (header[3] << 24);
            AVPacket *packet = av_packet_alloc();
            assert(packet && av_new_packet(packet, size) == 0);
            assert(fread(packet->data, 1, size, input) == static_cast<size_t>(size));
            packet->pts = index;
            // Deliberately withhold output to force send_packet(EAGAIN).
            assert(context.SendPacket(packet) == 0);
            av_packet_free(&packet);
        }
        assert(!context.pendingFrames.empty());
        if (pass == 0) {
            // Seeking must discard held output and let the same decoder start again.
            assert(Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegReset(
                    nullptr, nullptr, reinterpret_cast<jlong>(&context)) != 0);
            assert(context.pendingFrames.empty());
            continue;
        }
        int frames = 0;
        // Consume the bitstream filter's pending input before signalling EOF.
        for (bool draining : {false, true}) {
            if (draining) assert(context.SendPacket(nullptr) == 0);
            while (true) {
                AVFrame *frame = nullptr;
                int result = context.ReceiveFrame(&frame);
                if (result == (draining ? AVERROR_EOF : AVERROR(EAGAIN))) {
                    av_frame_free(&frame);
                    break;
                }
                assert(result == 0);
                assert(frame->pts == frames && frame->width == width && frame->height == height);
                assert(frame->format == AV_PIX_FMT_YUV420P);
                frames++;
                av_frame_free(&frame);
            }
        }
        assert(frames == frameCount);
        assert(context.pendingFrames.empty());
    }
    fclose(input);
    printf("PASS: %s packet backpressure loses no frames; reset clears pending output\n", filename);
}

static void CheckPacketErrorOrigins() {
    JNINativeInterface functions{};
    functions.GetDirectBufferAddress = [](JNIEnv *, jobject buffer) -> void * { return buffer; };
    functions.GetDirectBufferCapacity = [](JNIEnv *, jobject) -> jlong {
        return 1 + AV_INPUT_BUFFER_PADDING_SIZE;
    };
    JNIEnv env{&functions};
    JniContext context;
    uint8_t bytes[1 + AV_INPUT_BUFFER_PADDING_SIZE]{};
    injectedReceiveError = AVERROR_INVALIDDATA;
    lastErrorFunction = nullptr;
    // The send is rejected before touching this input; decoding earlier data fails.
    int result = Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegSendPacket(
            &env, nullptr, reinterpret_cast<jlong>(&context), reinterpret_cast<jobject>(bytes), 1, 42);
    assert(result == VIDEO_DECODER_ERROR_OTHER);
    assert(lastErrorFunction && strcmp(lastErrorFunction, "avcodec_receive_frame") == 0);
    assert(context.pendingFrames.empty());
    puts("PASS: receive errors retain their origin and cannot skip unaccepted input");

    const AVCodec *codec = avcodec_find_decoder_by_name("vp9");
    context.codecContext = avcodec_alloc_context3(codec);
    assert(context.codecContext && avcodec_open2(context.codecContext, codec, nullptr) == 0);
    lastErrorFunction = nullptr;
    // A malformed current packet is still skippable, unlike a receive failure.
    result = Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegVideoDecoder_ffmpegSendPacket(
            &env, nullptr, reinterpret_cast<jlong>(&context), reinterpret_cast<jobject>(bytes), 1, 43);
    assert(result == VIDEO_DECODER_ERROR_INVALID_DATA);
    assert(lastErrorFunction && strcmp(lastErrorFunction, "avcodec_send_packet") == 0);
    puts("PASS: invalid input retains its send error classification");
}

int main() {
    CheckPacketErrorOrigins();
    CheckVp9PacketBackpressure("vp9.ivf", 24, 64, 48);
    int emptyReceivesBefore = emptyReceivesAfterBlockedSend;
    CheckVp9PacketBackpressure("vp9-altref.ivf", 72, 128, 96);
    assert(emptyReceivesAfterBlockedSend > emptyReceivesBefore);
    puts("PASS: alt-ref hidden frames exercise send-EAGAIN followed by receive-EAGAIN");
    CheckVpDecoders();
    CheckColorsAndRange();
    CheckInvalidWindowBuffers();
}
