package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

@UnstableApi
public class FfmpegVideoRendererTest {
    private static final Format VP9 = new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(64).setHeight(48).build();

    private static FfmpegVideoRenderer renderer() {
        return new FfmpegVideoRenderer(0, null, null, 0, 1, 1, 1);
    }

    @Test
    public void failedInitializationDoesNotLeaveDecoderThreads() {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        for (Format format : new Format[] {new Format.Builder().build(),
                VP9.buildUpon().setSampleMimeType("video/unsupported").build(),
                VP9.buildUpon().setSampleMimeType(MimeTypes.VIDEO_AV1)
                        .setInitializationData(List.of(new byte[] {1}, new byte[] {2})).build()}) {
            assertThrows(Throwable.class, () -> new FfmpegVideoDecoder(1, 1, 1024, 1, format));
        }
        // A negative allocation fails after the native decoder has been opened.
        assertThrows(RuntimeException.class,
                () -> new FfmpegVideoDecoder(1, 1, Integer.MIN_VALUE, 1, VP9));
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            assertFalse("Leaked decoder thread after constructor failure",
                    !before.contains(thread) && thread.isAlive()
                            && thread.getName().equals("ExoPlayer:SimpleDecoder"));
        }
    }

    @Test
    public void bitstreamDimensionsOverrideContainerHintsAcrossFlush() throws Exception {
        for (int[] dimensions : new int[][] {{64, 48}, {-1, -1}, {320, 180}}) {
            Format format = VP9.buildUpon().setWidth(dimensions[0]).setHeight(dimensions[1]).build();
            FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(1, 1, 1024, 1, format);
            decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
            try {
                for (int pass = 0; pass < 4; pass++) {
                    decoder.flush();
                    VideoDecoderOutputBuffer output = decodeKeyFrame(decoder, format, pass * 1000L);
                    assertEquals(64, output.width);
                    assertEquals(48, output.height);
                    output.release();
                }
            } finally {
                decoder.release();
            }
        }
    }

    @Test
    public void capabilitiesRejectMissingUnsupportedAndEncryptedFormats() {
        assertTrue("Tests require the bundled native decoder", FfmpegLibrary.isAvailable());
        FfmpegVideoRenderer renderer = renderer();
        assertSupport(renderer, new Format.Builder().build(), C.FORMAT_UNSUPPORTED_TYPE);
        assertSupport(renderer, new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(),
                C.FORMAT_UNSUPPORTED_TYPE);
        assertSupport(renderer, new Format.Builder().setSampleMimeType("video/unsupported").build(),
                C.FORMAT_UNSUPPORTED_SUBTYPE);
        assertSupport(renderer, VP9, C.FORMAT_HANDLED);
        for (int cryptoType : new int[] {C.CRYPTO_TYPE_UNSUPPORTED, C.CRYPTO_TYPE_FRAMEWORK,
                C.CRYPTO_TYPE_CUSTOM_BASE}) {
            Format encrypted = VP9.buildUpon().setCryptoType(cryptoType).build();
            assertNull(encrypted.drmInitData);
            assertSupport(renderer, encrypted, C.FORMAT_UNSUPPORTED_DRM);
        }
        Format drm = VP9.buildUpon().setDrmInitData(new DrmInitData(
                new DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", new byte[] {1}))).build();
        assertEquals(C.CRYPTO_TYPE_UNSUPPORTED, drm.cryptoType);
        assertSupport(renderer, drm, C.FORMAT_UNSUPPORTED_DRM);
    }

    @Test
    public void adaptationIsNotSeamlessAndKeepsInheritedNoReuse() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        int capabilities = renderer.supportsFormat(VP9);
        assertEquals(RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
                RendererCapabilities.getAdaptiveSupport(capabilities));
        assertEquals(RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                RendererCapabilities.getTunnelingSupport(capabilities));
        Method reuse = DecoderVideoRenderer.class.getDeclaredMethod(
                "canReuseDecoder", String.class, Format.class, Format.class);
        reuse.setAccessible(true);
        for (Format next : new Format[] {VP9, VP9.buildUpon().setRotationDegrees(90).build(), VP9.buildUpon().setWidth(128).setHeight(96).build(),
                VP9.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H264).build()}) {
            DecoderReuseEvaluation result = (DecoderReuseEvaluation) reuse.invoke(
                    renderer, "ffmpeg-vp9", VP9, next);
            assertEquals(DecoderReuseEvaluation.REUSE_RESULT_NO, result.result);
        }
        for (Method declared : FfmpegVideoRenderer.class.getDeclaredMethods()) {
            assertNotEquals("canReuseDecoder", declared.getName());
        }
    }

    @Test
    public void missingDecoderStillReleasesOutputExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        VideoDecoderOutputBuffer output = new VideoDecoderOutputBuffer(b -> releases.incrementAndGet());
        FfmpegDecoderException error = assertThrows(FfmpegDecoderException.class,
                () -> renderer().renderOutputBufferToSurface(output, null));
        assertTrue(error.getMessage().contains("decoder is not initialized"));
        assertEquals(1, releases.get());
    }

    @Test
    public void renderFailureReturnsSingleBufferAndCleanupDoesNotFreeFrameAgain() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        VideoDecoderOutputBuffer output = null;
        try {
            for (int i = 0; i < 12; i++) {
                output = decodeKeyFrame(decoder);
                assertNotEquals(0, output.decoderPrivate);
                output.mode = C.VIDEO_OUTPUT_MODE_YUV;
                VideoDecoderOutputBuffer failed = output;
                assertThrows(FfmpegDecoderException.class,
                        () -> renderer.renderOutputBufferToSurface(failed, null));
                assertEquals(0, output.decoderPrivate);
            }
        } finally {
            decoder.release();
        }
        assertNotNull(output);
        assertEquals(0, output.decoderPrivate);
    }

    @Test
    public void nativeSurfaceFailureReleasesFrame() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        try (ImageReader images = ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2)) {
            Surface invalid = images.getSurface();
            invalid.release();
            VideoDecoderOutputBuffer output = decodeKeyFrame(decoder);
            assertNotEquals(0, output.decoderPrivate);
            assertThrows(FfmpegDecoderException.class,
                    () -> renderer.renderOutputBufferToSurface(output, invalid));
            assertEquals(0, output.decoderPrivate);
        } finally {
            decoder.release();
        }
    }

    @Test
    public void successfulRenderAndDecoderFirstCleanupReleaseFrames() throws Exception {
        FfmpegVideoRenderer renderer = renderer();
        FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(VP9, null);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
        try (ImageReader images = ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2)) {
            try {
                VideoDecoderOutputBuffer rendered = decodeKeyFrame(decoder);
                renderer.renderOutputBufferToSurface(rendered, images.getSurface());
                assertEquals(0, rendered.decoderPrivate);
                try (Image image = images.acquireNextImage()) {
                    assertNotNull("Rendering must publish an image", image);
                }
                VideoDecoderOutputBuffer held = decodeKeyFrame(decoder);
                assertNotEquals(0, held.decoderPrivate);
                decoder.release();
                assertEquals(0, held.decoderPrivate);
                held.release();
                assertEquals(0, held.decoderPrivate);
            } finally {
                decoder.release();
            }
        }
    }

    @Test
    public void rotationReachesSurfaceAndYuvPixelsAcrossFlushAndSurfaceRecreation() throws Exception {
        FfmpegVideoRenderer baselineRenderer = renderer();
        FfmpegVideoDecoder baseline = (FfmpegVideoDecoder) baselineRenderer.createDecoder(VP9, null);
        byte[][] yuv;
        byte[] rgba;
        try {
            baseline.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
            VideoDecoderOutputBuffer output = decodeKeyFrame(baseline);
            yuv = yuvPixels(output);
            output.release();
            baseline.flush();
            baseline.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
            rgba = surfacePixels(baselineRenderer, decodeKeyFrame(baseline));
        } finally {
            baseline.release();
        }
        // Explicit expectations also cover negative/overflow-prone values and the no-rotation fallback.
        int[][] cases = {{0, 0}, {90, 90}, {180, 180}, {270, 270}, {-90, 270},
                {-180, 180}, {-270, 90}, {450, 90}, {360, 0}, {-360, 0},
                {45, 0}, {-45, 0}, {Integer.MIN_VALUE, 0}, {Integer.MAX_VALUE, 0},
                {2147483610, 90}, {-2147483610, 270}};
        for (int[] rotation : cases) {
            Format format = VP9.buildUpon().setRotationDegrees(rotation[0])
                    .setPixelWidthHeightRatio(1.25f).build();
            FfmpegVideoRenderer renderer = renderer();
            FfmpegVideoDecoder decoder = (FfmpegVideoDecoder) renderer.createDecoder(format, null);
            boolean quarter = rotation[1] == 90 || rotation[1] == 270;
            try {
                for (int pass = 0; pass < 4; pass++) {
                    decoder.flush();
                    boolean surface = pass % 2 == 1;
                    decoder.setOutputMode(surface ? C.VIDEO_OUTPUT_MODE_SURFACE_YUV : C.VIDEO_OUTPUT_MODE_YUV);
                    long timeUs = 123000L * (pass + 1);
                    VideoDecoderOutputBuffer output = decodeKeyFrame(decoder, format, timeUs);
                    assertEquals(timeUs, output.timeUs);
                    assertSame(format, output.format);
                    assertEquals(1.25f, output.format.pixelWidthHeightRatio, 0);
                    assertEquals(quarter ? 48 : 64, output.width);
                    assertEquals(quarter ? 64 : 48, output.height);
                    if (surface) {
                        assertNotEquals(0, output.decoderPrivate);
                        assertArrayEquals("Surface rotation " + rotation[0],
                                rotate(rgba, 64, 48, 4, rotation[1]), surfacePixels(renderer, output));
                    } else {
                        assertEquals(0, output.decoderPrivate);
                        byte[][] actual = yuvPixels(output);
                        for (int plane = 0; plane < 3; plane++) {
                            assertArrayEquals("YUV rotation " + rotation[0] + " plane " + plane,
                                    rotate(yuv[plane], plane == 0 ? 64 : 32,
                                            plane == 0 ? 48 : 24, 1, rotation[1]), actual[plane]);
                        }
                        output.release();
                    }
                    assertEquals(0, output.decoderPrivate);
                }
            } finally {
                decoder.release();
            }
        }
    }

    private static byte[][] yuvPixels(VideoDecoderOutputBuffer output) {
        byte[][] pixels = new byte[3][];
        for (int plane = 0; plane < 3; plane++) {
            int width = plane == 0 ? output.width : (output.width + 1) / 2;
            int height = plane == 0 ? output.height : (output.height + 1) / 2;
            pixels[plane] = new byte[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[plane][y * width + x] = output.yuvPlanes[plane].get(y * output.yuvStrides[plane] + x);
                }
            }
        }
        return pixels;
    }

    private static byte[] surfacePixels(FfmpegVideoRenderer renderer, VideoDecoderOutputBuffer output)
            throws Exception {
        int width = output.width;
        int height = output.height;
        // Each call creates a new Surface; the decoder must release the old CPU producer.
        try (ImageReader images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)) {
            renderer.renderOutputBufferToSurface(output, images.getSurface());
            try (Image image = images.acquireNextImage()) {
                assertNotNull(image);
                assertEquals(width, image.getWidth());
                assertEquals(height, image.getHeight());
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buffer = plane.getBuffer();
                byte[] pixels = new byte[width * height * 4];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        for (int c = 0; c < 4; c++) {
                            pixels[(y * width + x) * 4 + c] =
                                    buffer.get(y * plane.getRowStride() + x * plane.getPixelStride() + c);
                        }
                    }
                }
                return pixels;
            }
        }
    }

    private static byte[] rotate(byte[] source, int width, int height, int pixelSize, int rotation) {
        boolean quarter = rotation == 90 || rotation == 270;
        int dw = quarter ? height : width;
        int dh = quarter ? width : height;
        byte[] expected = new byte[source.length];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = rotation == 90 ? y : rotation == 180 ? width - 1 - x :
                        rotation == 270 ? width - 1 - y : x;
                int sy = rotation == 90 ? height - 1 - x : rotation == 180 ? height - 1 - y :
                        rotation == 270 ? x : y;
                System.arraycopy(source, (sy * width + sx) * pixelSize,
                        expected, (y * dw + x) * pixelSize, pixelSize);
            }
        }
        return expected;
    }

    private static void assertSupport(FfmpegVideoRenderer renderer, Format format, int expected) {
        assertEquals(expected, RendererCapabilities.getFormatSupport(renderer.supportsFormat(format)));
    }

    private static VideoDecoderOutputBuffer decodeKeyFrame(FfmpegVideoDecoder decoder) throws Exception {
        return decodeKeyFrame(decoder, VP9, 0);
    }

    private static VideoDecoderOutputBuffer decodeKeyFrame(FfmpegVideoDecoder decoder, Format format, long timeUs) throws Exception {
        byte[] packet;
        try (DataInputStream asset = new DataInputStream(InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open("vp9.ivf"))) {
            asset.skipBytes(32);
            int size = Integer.reverseBytes(asset.readInt());
            asset.skipBytes(8);
            packet = new byte[size];
            asset.readFully(packet);
        }
        DecoderInputBuffer input = decoder.dequeueInputBuffer();
        assertNotNull(input);
        input.ensureSpaceForWrite(packet.length);
        input.data.put(packet);
        input.timeUs = timeUs;
        input.format = format;
        input.flip();
        decoder.queueInputBuffer(input);
        long deadline = SystemClock.elapsedRealtime() + 5000;
        VideoDecoderOutputBuffer output;
        while ((output = decoder.dequeueOutputBuffer()) == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(5);
        }
        assertNotNull("Output pool starved or decoder failed to produce a frame", output);
        return output;
    }
}
