package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.IndexSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Keeps native subtitle packets intact and retains Media3 parsing for all other formats.
 * Pass the same [dataSourceFactory] used by this factory so ASS sidecars share authentication,
 * caching and URI support. Call this after configuring the [DefaultMediaSourceFactory].
 */
@UnstableApi
fun DefaultMediaSourceFactory.withFfmpegSubtitles(dataSourceFactory: DataSource.Factory): MediaSource.Factory {
    val parsers = DefaultSubtitleParserFactory()
    setSubtitleParserFactory(object : SubtitleParser.Factory by parsers {
        override fun supportsFormat(format: Format): Boolean =
            !usesNativeSubtitles(format.sampleMimeType) && parsers.supportsFormat(format)
    })
    return FfmpegSubtitleSourceFactory(this, dataSourceFactory)
}

@UnstableApi
internal fun usesNativeSubtitles(mime: String?): Boolean = when (mime) {
    MimeTypes.TEXT_SSA -> FfmpegLibrary.isAvailable()
    MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS -> FfmpegLibrary.supportsFormat(mime)
    else -> false
}

@UnstableApi
private class FfmpegSubtitleSourceFactory(
    val delegate: DefaultMediaSourceFactory,
    val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory by delegate {
    private var loadErrorPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        delegate.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        loadErrorPolicy = policy
        delegate.setLoadErrorHandlingPolicy(policy)
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        val native = subtitles.filter { it.mimeType == MimeTypes.TEXT_SSA && usesNativeSubtitles(it.mimeType) }
        if (native.isEmpty()) return delegate.createMediaSource(mediaItem)
        val primary = mediaItem.buildUpon().setSubtitleConfigurations(subtitles - native.toSet())
            .setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET).build()
        val sources = mutableListOf(delegate.createMediaSource(primary))
        for (subtitle in native) {
            val format = Format.Builder().setId(subtitle.id).setSampleMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language).setLabel(subtitle.label).setSelectionFlags(subtitle.selectionFlags)
                .setRoleFlags(subtitle.roleFlags).setSubsampleOffsetUs(0).build()
            val factory = ProgressiveMediaSource.Factory(dataSourceFactory, ExtractorsFactory { arrayOf(AssFileExtractor(format)) })
            loadErrorPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }
            sources += factory.createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        // Preserve the original sidecar configurations in the public player timeline.
        val merged = MergingMediaSource(*sources.toTypedArray())
        val source = if (mediaItem.clippingConfiguration == MediaItem.ClippingConfiguration.UNSET) merged else
            ClippingMediaSource.Builder(merged).setClippingConfiguration(mediaItem.clippingConfiguration).build()
        return object : WrappingMediaSource(source) {
            private var currentItem = mediaItem
            private var childTimeline: Timeline? = null
            override fun getMediaItem() = currentItem
            override fun canUpdateMediaItem(updated: MediaItem): Boolean =
                updated.localConfiguration == currentItem.localConfiguration &&
                    updated.clippingConfiguration == currentItem.clippingConfiguration &&
                    updated.liveConfiguration == currentItem.liveConfiguration

            override fun updateMediaItem(updated: MediaItem) {
                // Metadata-only updates must not recreate periods (NextPlayer updates track metadata).
                currentItem = updated
                childTimeline?.let { onChildSourceInfoRefreshed(it) }
            }

            override fun onChildSourceInfoRefreshed(timeline: Timeline) {
                childTimeline = timeline
                val item = currentItem
                refreshSourceInfo(object : ForwardingTimeline(timeline) {
                    override fun getWindow(index: Int, window: Timeline.Window, projectionUs: Long): Timeline.Window =
                        super.getWindow(index, window, projectionUs).apply { this.mediaItem = item }
                })
            }
        }
    }
}

/** One seekable sample containing the script; libass owns its event timeline. */
@UnstableApi
private class AssFileExtractor(val format: Format) : Extractor {
    private lateinit var track: TrackOutput
    private lateinit var output: ExtractorOutput
    private val script = ByteArrayOutputStream()
    private var bytesRead = 0
    override fun sniff(input: ExtractorInput) = true
    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_TEXT)
        track.format(format)
        output.endTracks()
        output.seekMap(IndexSeekMap(longArrayOf(0), longArrayOf(0), C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (input.length > MAX_SCRIPT_BYTES) throw IOException("ASS script exceeds 16 MiB")
        val count = track.sampleData({ bytes, offset, length ->
            input.read(bytes, offset, length).also { if (it > 0) script.write(bytes, offset, it) }
        }, minOf(8192, MAX_SCRIPT_BYTES + 1 - bytesRead), true)
        if (count == C.RESULT_END_OF_INPUT) {
            // Without an explicit duration ProgressiveMediaPeriod treats this single sample as
            // 10 ms long, and won't reload it when a subtitle track is re-enabled after that.
            // Reuse Media3 only to read event times; send the untouched script to libass.
            val bytes = script.toByteArray()
            val subtitle = SsaParser().parseToLegacySubtitle(bytes, 0, bytes.size)
            val duration = if (subtitle.eventTimeCount == 0) 0 else subtitle.getEventTime(subtitle.eventTimeCount - 1)
            output.seekMap(IndexSeekMap(longArrayOf(0), longArrayOf(0), duration))
            track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, bytesRead, 0, null)
            return Extractor.RESULT_END_OF_INPUT
        }
        bytesRead += count
        if (bytesRead > MAX_SCRIPT_BYTES) throw IOException("ASS script exceeds 16 MiB")
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        bytesRead = 0; script.reset()
    }
    override fun release() = Unit

    companion object { private const val MAX_SCRIPT_BYTES = 16 * 1024 * 1024 }
}
