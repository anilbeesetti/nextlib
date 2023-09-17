package io.github.anilbeesetti.nextlib.mediainfo

data class MediaInfo(
    val format: String,
    val duration: Long,
    val videoStream: VideoStream?,
    val audioStreams: List<AudioStream>,
    val subtitleStreams: List<SubtitleStream>
)


data class VideoStream(
    val index: Int,
    val title: String?,
    val codecName: String,
    val language: String?,
    val disposition: Int,
    val bitRate: Long,
    val frameRate: Double,
    val frameWidth: Int,
    val frameHeight: Int
)

data class AudioStream(
    val index: Int,
    val title: String?,
    val codecName: String,
    val language: String?,
    val disposition: Int,
    val bitRate: Long,
    val sampleFormat: String?,
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: String?
)

data class SubtitleStream(
    val index: Int,
    val title: String?,
    val codecName: String,
    val language: String?,
    val disposition: Int
)