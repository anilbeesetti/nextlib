package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.util.UnstableApi

/** Selects how a source prepares subtitles for playback. */
@UnstableApi
enum class SubtitleRenderingMode {
    /** Use FFmpeg and libass for supported formats and Media3 for other subtitles. */
    FFMPEG,

    /** Use Media3 subtitle parsers and emit cues for the Media3 text renderer. */
    MEDIA3,
}
