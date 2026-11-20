package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import java.io.IOException

/** FFmpeg container extraction feeding the existing Media3 audio/video renderers. */
@UnstableApi
class FfmpegMediaSourceFactory(private val dataSources: DataSource.Factory) {
    fun createMediaSource(item: MediaItem): MediaSource =
        createMediaSource(item, SubtitleRenderingMode.FFMPEG)

    /** Applies [subtitleRenderingMode] to embedded and sidecar subtitles in this source. */
    fun createMediaSource(item: MediaItem, subtitleRenderingMode: SubtitleRenderingMode): MediaSource {
        require(item.localConfiguration?.drmConfiguration == null) { "FFmpeg extraction does not support DRM" }
        // Media3 1.11 creates the period's DataSource immediately before its extractor, on one thread.
        val pending = ThreadLocal<ReopeningDataSource>()
        val factory = DataSource.Factory {
            check(pending.get() == null) { "Unconsumed FFmpeg source reader" }
            ReopeningDataSource(dataSources.createDataSource()).also(pending::set)
        }
        val subtitles = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val primary = item.buildUpon().setSubtitleConfigurations(emptyList())
            .setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET).build()
        val video = ProgressiveMediaSource.Factory(factory, ProgressiveMediaExtractor.Factory { _ ->
            val source = checkNotNull(pending.get()) { "FFmpeg extractor requires its loader's reader" }
            pending.remove()
            FfmpegProgressiveMediaExtractor(source, subtitleRenderingMode)
        }).createMediaSource(primary)
        val sources = mutableListOf<MediaSource>(video)
        val parsers = DefaultSubtitleParserFactory()
        for (subtitle in subtitles) {
            val format = Format.Builder().setId(subtitle.id).setSampleMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language).setLabel(subtitle.label).setSelectionFlags(subtitle.selectionFlags)
                .setRoleFlags(subtitle.roleFlags).setSubsampleOffsetUs(0).build()
            val extractors = ExtractorsFactory {
                val extractor = if (subtitleRenderingMode == SubtitleRenderingMode.FFMPEG &&
                    format.sampleMimeType == MimeTypes.TEXT_SSA
                ) {
                    AssFileExtractor(format)
                } else {
                    SubtitleExtractor(parsers.create(format), format)
                }
                arrayOf(extractor)
            }
            sources += ProgressiveMediaSource.Factory(dataSources, extractors)
                .createMediaSource(MediaItem.fromUri(subtitle.uri))
        }
        val merged = if (sources.size == 1) video else MergingMediaSource(*sources.toTypedArray())
        val clipped = if (item.clippingConfiguration == MediaItem.ClippingConfiguration.UNSET) merged
        else ClippingMediaSource.Builder(merged).setClippingConfiguration(item.clippingConfiguration).build()
        return clipped.withPublicMediaItem(item)
    }
}

/** Native seeks reopen the loader's same reader, preserving headers, URI grants and request bounds. */
@UnstableApi
internal class ReopeningDataSource(private val delegate: DataSource) : DataSource {
    private lateinit var request: DataSpec
    private var end = C.LENGTH_UNSET.toLong()
    var position = 0L
        private set
    var length = C.LENGTH_UNSET.toLong()
        private set
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        request = dataSpec
        position = dataSpec.position
        opened = true
        val remaining = delegate.open(dataSpec)
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else Math.addExact(dataSpec.position, dataSpec.length)
        length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else Math.addExact(position, remaining)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (length == 0) 0 else if (end != C.LENGTH_UNSET.toLong() && position == end) C.RESULT_END_OF_INPUT
        else delegate.read(buffer, offset, length).also { if (it > 0) position += it }

    fun seek(offset: Long): Long {
        if (offset < request.position || (end != C.LENGTH_UNSET.toLong() && offset > end)) {
            throw IOException("FFmpeg seek outside request: $offset")
        }
        if (offset == position) return position
        close()
        position = offset
        if (end != C.LENGTH_UNSET.toLong() && offset == end) return position
        val spec = request.buildUpon().setPosition(offset).setLength(
            if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - offset,
        ).build()
        opened = true
        delegate.open(spec)
        return position
    }

    override fun close() { if (opened) { opened = false; delegate.close() } }
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
}
