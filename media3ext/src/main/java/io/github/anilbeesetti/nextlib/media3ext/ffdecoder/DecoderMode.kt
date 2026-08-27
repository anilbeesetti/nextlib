package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

/** Selects which decoder category is eligible for a video or audio track. */
enum class DecoderMode {
    /** Uses only MediaCodec decoders that Media3 identifies as hardware accelerated. */
    HARDWARE,

    /** Uses only MediaCodec decoders that Media3 identifies as software-only. */
    SOFTWARE,

    /** Uses only the FFmpeg renderer bundled with nextlib. */
    APP_SOFTWARE,
}
