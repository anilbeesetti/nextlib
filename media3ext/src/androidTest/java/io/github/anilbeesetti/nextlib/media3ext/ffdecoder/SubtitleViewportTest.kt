package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class SubtitleViewportTest {
    @Test fun surfaceChangesResizeSubtitlesWithoutAnApplicationListener() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val script = context.assets.open("subtitles/font-embedded.ass").use { it.readBytes() }
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        for (mode in listOf("default", "ffmpeg-only", "switching")) {
            val manager = if (mode == "switching") DecoderManager() else null
            val factory = when (mode) {
                "ffmpeg-only" -> FFmpegOnlyRenderersFactory(context)
                "switching" -> NextRenderersFactory(context).setDecoderManager(checkNotNull(manager))
                else -> NextRenderersFactory(context)
            }
            val imageReader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2)
            val frame = Rect(0, 0, 640, 360)
            val callbacks = mutableListOf<SurfaceHolder.Callback>()
            // ExoPlayer receives the same holder callbacks as a SurfaceView, without a test Activity.
            val holder = Proxy.newProxyInstance(SurfaceHolder::class.java.classLoader, arrayOf(SurfaceHolder::class.java)) { _, method, args ->
                when (method.name) {
                    "getSurface" -> imageReader.surface
                    "getSurfaceFrame" -> frame
                    "addCallback" -> { callbacks += args!![0] as SurfaceHolder.Callback; null }
                    "removeCallback" -> { callbacks -= args!![0] as SurfaceHolder.Callback; null }
                    else -> error("Unexpected SurfaceHolder call: ${method.name}")
                }
            } as SurfaceHolder
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).setRenderersFactory(factory)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory())
                        .withFfmpegSubtitles(DefaultDataSource.Factory(context)))
                    .build()
                manager?.attach(player)
                manager?.selectVideoDecoder(DecoderMode.FFMPEG)
                player.setVideoSurfaceHolder(holder)
                player.setMediaItem(MediaItem.Builder().setUri("asset:///subtitles/video.mp4")
                    .setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse("asset:///subtitles/font-embedded.ass"))
                        .setMimeType(MimeTypes.TEXT_SSA).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
                    .build(), 2000)
                player.prepare()
            }
            try {
                FfmpegSubtitleDecoder(context, format, null).use { expectedDecoder ->
                    assertTrue(expectedDecoder.decode(ByteBuffer.allocateDirect(script.size).apply { put(script); flip() }, 0, 0))
                    fun checkBitmap(width: Int, height: Int) {
                        val expected = checkNotNull(expectedDecoder.render(2_000_000, width, height)?.single()?.bitmap)
                        try {
                            val deadline = SystemClock.elapsedRealtime() + 10_000
                            var matches = false
                            while (!matches && SystemClock.elapsedRealtime() < deadline) {
                                instrumentation.runOnMainSync {
                                    assertNull(player.playerError)
                                    matches = player.currentCues.cues.singleOrNull()?.bitmap?.sameAs(expected) == true
                                }
                                if (!matches) SystemClock.sleep(50)
                            }
                            assertTrue("$mode must render at ${width}x$height automatically", matches)
                        } finally { expected.recycle() }
                    }
                    checkBitmap(640, 360) // Surface attached before the subtitle renderer was enabled.
                    for ((width, height, expectedWidth, expectedHeight) in listOf(
                        listOf(540, 960, 540, 960), // Portrait resize while paused.
                        listOf(3840, 2160, 1920, 1080), // Raster work remains bounded.
                    )) {
                        instrumentation.runOnMainSync {
                            frame.set(0, 0, width, height)
                            callbacks.toList().forEach { it.surfaceChanged(holder, PixelFormat.RGBA_8888, width, height) }
                        }
                        checkBitmap(expectedWidth, expectedHeight)
                    }
                    instrumentation.runOnMainSync {
                        player.clearVideoSurface()
                        player.setVideoSurface(imageReader.surface) // Unknown dimensions must be ignored.
                    }
                    SystemClock.sleep(250)
                    instrumentation.runOnMainSync {
                        assertNull(player.playerError)
                        assertTrue(player.currentCues.cues.isNotEmpty())
                        player.setSubtitleViewportSize(960, 540)
                    }
                    checkBitmap(960, 540) // Explicit dimensions still work for a bare Surface.
                }
            } finally {
                instrumentation.runOnMainSync { manager?.detach(); player.release() }
                imageReader.close()
            }
        }
    }
}
