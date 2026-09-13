package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real extractors, media sources, renderer selection and seek/track lifecycle on an Android device. */
@UnstableApi
class SubtitlePlaybackTest {
    @Test fun clippedVideoEndsEvenWhenAssScriptContinues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val ended = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<PlaybackException>()
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context)
                .setRenderersFactory(NextRenderersFactory(context))
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory())
                    .withFfmpegSubtitles(androidx.media3.datasource.DefaultDataSource.Factory(context)))
                .build()
            player.subtitleDelayMilliseconds = 1000
            player.subtitleSpeed = 0.5f
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.countDown() }
                override fun onPlayerError(e: PlaybackException) { error.set(e); ended.countDown() }
            })
            player.setMediaItem(MediaItem.Builder().setUri("asset:///subtitles/video.mp4")
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(1000).setEndPositionMs(3000).build())
                .setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///subtitles/styled.ass"))
                    .setMimeType(MimeTypes.TEXT_SSA).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
                .build())
            player.prepare()
            player.play()
        }
        try {
            assertTrue("Subtitle events after the clip must not block playback ending", ended.await(10, TimeUnit.SECONDS))
            assertNull(error.get())
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test fun commonEmbeddedAndSidecarSubtitles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val cases = listOf(
            Triple("styled.ass.mkv", null, true),
            Triple("font.ass.mkv", null, true),
            Triple("legacy.ssa.mkv", null, true),
            Triple("basic.srt.mkv", null, false),
            // FFmpeg writes D_WEBVTT/SUBTITLES, which Media3's MatroskaExtractor discards.
            // Retain that generated file as a reproducer; exercise WebVTT as a sidecar below.
            Triple("bitmap.sup.mkv", null, true),
            Triple("vobsub.mkv", null, true),
            Triple("dvb.ts", null, true),
            Triple("tx3g.mp4", null, false),
            Triple("styled.ass", MimeTypes.TEXT_SSA, true),
            Triple("legacy.ssa", MimeTypes.TEXT_SSA, true),
            Triple("basic.srt", MimeTypes.APPLICATION_SUBRIP, false),
            Triple("styled.vtt", MimeTypes.TEXT_VTT, false),
            Triple("basic.ttml", MimeTypes.APPLICATION_TTML, false),
        )
        val failures = mutableListOf<String>()
        val selectedFile = InstrumentationRegistry.getArguments().getString("subtitle")
        for ((file, mime, bitmap) in cases.filter { selectedFile == null || it.first == selectedFile }) {
            var expectedFontBitmap: android.graphics.Bitmap? = null
            val outputs = LinkedBlockingQueue<List<Cue>>()
            var error: PlaybackException? = null
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context)
                    // Keep sparse subtitle packets available while testing decoder reset and re-selection.
                    .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBackBuffer(30_000, true).build())
                    .setRenderersFactory(NextRenderersFactory(context))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory()).withFfmpegSubtitles(androidx.media3.datasource.DefaultDataSource.Factory(context)))
                    .build()
                player.addListener(object : Player.Listener {
                    override fun onCues(cueGroup: CueGroup) { outputs.offer(cueGroup.cues) }
                    override fun onPlayerError(e: PlaybackException) { error = e; outputs.offer(emptyList()) }
                })
                val item = MediaItem.Builder().setUri("asset:///subtitles/${if (mime == null) file else "video.mp4"}")
                if (mime != null) item.setSubtitleConfigurations(listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///subtitles/$file"))
                        .setMimeType(mime).setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
                ))
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setPreferredTextLanguage("en").build()
                player.setMediaItem(item.build())
                player.prepare()
                player.play()
            }
            fun awaitCues(empty: Boolean): List<Cue> {
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    error?.let { throw AssertionError("$file playback failed", it) }
                    val cues = outputs.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (cues.isEmpty() == empty) return cues
                }
                var state = ""
                instrumentation.runOnMainSync {
                    state = "position=${player.currentPosition}, state=${player.playbackState}, seekable=${player.isCurrentMediaItemSeekable}, textSelected=${player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)}"
                }
                throw AssertionError("$file timed out waiting for ${if (empty) "clear" else "subtitle"}; $state")
            }
            fun awaitReady() {
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = player.playbackState == Player.STATE_READY }
                    if (ready) {
                        SystemClock.sleep(200)
                        return
                    }
                    SystemClock.sleep(20)
                }
                throw AssertionError("$file did not finish seeking")
            }
            try {
                val cues = awaitCues(false)
                assertEquals("$file chose the wrong renderer", bitmap, cues.first().bitmap != null)
                if (file == "font.ass.mkv") {
                    val directory = java.io.File(context.cacheDir, "playback-fonts").apply { mkdirs() }
                    val font = java.io.File(directory, "shapes.ttf")
                    try {
                        font.writeBytes(context.assets.open("subtitles/shapes.ttf").use { it.readBytes() })
                        val script = context.assets.open("subtitles/font.ass").use { it.readBytes() }
                        val format = androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
                        FfmpegSubtitleDecoder(context, format, directory).use { decoder ->
                            val input = java.nio.ByteBuffer.allocateDirect(script.size).apply { put(script); flip() }
                            assertTrue(decoder.decode(input, 0, 0))
                            val expected = decoder.render(2_000_000, 1280, 720)!!.single().bitmap!!
                            expectedFontBitmap = expected
                            assertTrue("MKV attachment must render identically to the supplied original font", expected.sameAs(cues.single().bitmap!!))
                        }
                    } finally {
                        font.delete()
                        directory.delete()
                    }
                }
                instrumentation.runOnMainSync {
                    player.pause()
                    if (file == "font.ass.mkv") {
                        val format = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_TEXT }.getTrackFormat(0)
                        assertEquals("Fonts must not travel as codec initialization bytes", 2, format.initializationData.size)
                        assertNotNull(format.metadata)
                        assertNull("Fonts must not enter MediaSession track bundles", androidx.media3.common.Format.fromBundle(format.toBundle()).metadata)
                    }
                    val item = checkNotNull(player.currentMediaItem)
                    assertEquals("$file lost its public sidecar configuration", if (mime == null) 0 else 1, item.localConfiguration?.subtitleConfigurations?.size)
                    val updated = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle("updated").build()).build()
                    val sources = DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory()).withFfmpegSubtitles(androidx.media3.datasource.DefaultDataSource.Factory(context))
                    assertTrue("$file metadata updates must preserve the source", sources.createMediaSource(item).canUpdateMediaItem(updated))
                    player.replaceMediaItem(0, updated)
                }
                awaitReady()
                instrumentation.runOnMainSync {
                    assertEquals("updated", player.currentMediaItem?.mediaMetadata?.title)
                    assertFalse("$file metadata update lost cues", player.currentCues.cues.isEmpty())
                }
                outputs.clear()
                val gap = if (file in listOf("dvb.ts", "bitmap.sup.mkv", "vobsub.mkv")) 6500L else 7500L
                instrumentation.runOnMainSync { player.seekTo(gap) }
                awaitReady()
                instrumentation.runOnMainSync { assertTrue("$file must clear in the cue gap", player.currentCues.cues.isEmpty()) }
                outputs.clear()
                instrumentation.runOnMainSync { player.seekTo(2500) }
                awaitReady()
                val restored = awaitCues(false)
                expectedFontBitmap?.let { assertTrue("Seeking must preserve the attached font", it.sameAs(restored.single().bitmap!!)) }
                outputs.clear()
                instrumentation.runOnMainSync {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
                awaitCues(true)
                outputs.clear()
                instrumentation.runOnMainSync {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                    player.seekTo(2500)
                }
                val reselected = awaitCues(false)
                expectedFontBitmap?.let { assertTrue("Re-selection must preserve the attached font", it.sameAs(reselected.single().bitmap!!)) }
                Log.i("SubtitleQA", "PASS $file: ${if (bitmap) "native bitmap" else "Media3 text"}, seek, disable, re-enable")
            } catch (failure: AssertionError) {
                failures += failure.message ?: file
            } finally {
                instrumentation.runOnMainSync { player.release() }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
