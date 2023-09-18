package io.github.anilbeesetti.nextlib.mediainfo

data class MediaInfo(
    val format: String,
    val duration: Long,
    val videoStream: VideoStream?,
    val audioStreams: List<AudioStream>,
    val subtitleStreams: List<SubtitleStream>
)
