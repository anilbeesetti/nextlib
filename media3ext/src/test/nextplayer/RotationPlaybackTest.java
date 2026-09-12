package dev.anilbeesetti.nextplayer;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity;
import io.github.anilbeesetti.nextlib.mediainfo.MediaThumbnailRetriever;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/** Copy into Next Player's app/src/androidTest/java/dev/anilbeesetti/nextplayer. */
public class RotationPlaybackTest {
    private static final int[][] CORNERS = {{0, 1, 2, 3}, {2, 0, 3, 1}, {3, 2, 1, 0}, {1, 3, 0, 2}};
    private final File evidence = new File(InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getExternalFilesDir(null), "rotation-evidence");

    @Test
    public void metadataPlaybackLifecycleAndThumbnails() throws Exception {
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        for (int rotation : new int[] {0, 90, 180, 270}) {
            File fixture = new File("/sdcard/Movies/rotation-" + rotation + ".mp4");
            assertTrue(fixture.isFile());
            try (MediaThumbnailRetriever retriever = new MediaThumbnailRetriever()) {
                retriever.setDataSource(fixture.getPath());
                for (int index = 0; index < 2; index++) {
                    Bitmap thumbnail = index == 0 ? retriever.getFrameAtTime(1000000) : retriever.getFrameAtIndex(0);
                    assertNotNull(thumbnail);
                    assertEquals(rotation % 180 == 0 ? 640 : 360, thumbnail.getWidth());
                    assertEquals(rotation % 180 == 0 ? 360 : 640, thumbnail.getHeight());
                    checkCorners(thumbnail, new Rect(0, 0, thumbnail.getWidth(), thumbnail.getHeight()), rotation);
                    save(thumbnail, "thumbnail-" + rotation + "-" + index);
                    thumbnail.recycle();
                }
            }
            Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), PlayerActivity.class)
                    .setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(fixture));
            try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
                await(() -> state(scenario, rotation, null));
                onPlayer(scenario, MediaController::pause);
                select(scenario, "FFMPEG");
                await(() -> state(scenario, rotation, "FFMPEG"));
                capture(scenario, rotation, "ffmpeg");
                for (long position : new long[] {1500, 17100, 42500, 1200}) {
                    onPlayer(scenario, player -> player.seekTo(position));
                    await(() -> state(scenario, rotation, "FFMPEG") && position(scenario, position));
                    capture(scenario, rotation, "seek-" + position);
                }
                onPlayer(scenario, MediaController::play);
                await(() -> playing(scenario));
                onPlayer(scenario, MediaController::pause);
                // Destroy/recreate the Activity and its Surface while retaining the playback service.
                scenario.recreate();
                await(() -> state(scenario, rotation, "FFMPEG"));
                capture(scenario, rotation, "recreated");
                for (String mode : new String[] {"HARDWARE", "FFMPEG", "SOFTWARE", "FFMPEG"}) {
                    select(scenario, mode);
                    await(() -> state(scenario, rotation, mode));
                    capture(scenario, rotation, mode.toLowerCase());
                }
                onPlayer(scenario, MediaController::play);
                await(() -> playing(scenario));
                select(scenario, "HARDWARE");
                await(() -> state(scenario, rotation, "HARDWARE") && playing(scenario));
                select(scenario, "FFMPEG");
                await(() -> state(scenario, rotation, "FFMPEG") && playing(scenario));
                onPlayer(scenario, MediaController::pause);
                capture(scenario, rotation, "playing-transitions");
            }
        }
    }

    private static MediaController controller(PlayerActivity activity) {
        try {
            Field field = PlayerActivity.class.getDeclaredField("mediaController");
            field.setAccessible(true);
            return (MediaController) field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void onPlayer(ActivityScenario<PlayerActivity> scenario, java.util.function.Consumer<MediaController> action) {
        scenario.onActivity(activity -> action.accept(controller(activity)));
    }

    private static boolean state(ActivityScenario<PlayerActivity> scenario, int rotation, String mode) {
        boolean[] ready = {false};
        scenario.onActivity(activity -> {
            MediaController player = controller(activity);
            if (player == null) return;
            assertNull(player.getPlayerError());
            int w = rotation % 180 == 0 ? 640 : 360;
            int h = rotation % 180 == 0 ? 360 : 640;
            ready[0] = player.getPlaybackState() == Player.STATE_READY && player.getVideoSize().width == w
                    && player.getVideoSize().height == h && (mode == null || mode.equals(player.getSessionExtras().getString("video_decoder_mode")));
        });
        return ready[0];
    }

    private static boolean position(ActivityScenario<PlayerActivity> scenario, long expected) {
        boolean[] result = {false};
        onPlayer(scenario, p -> result[0] = !p.getPlayWhenReady() && Math.abs(p.getCurrentPosition() - expected) < 100);
        return result[0];
    }

    private static boolean playing(ActivityScenario<PlayerActivity> scenario) {
        boolean[] result = {false};
        onPlayer(scenario, p -> result[0] = p.isPlaying());
        return result[0];
    }

    private static void select(ActivityScenario<PlayerActivity> scenario, String mode) throws Exception {
        AtomicReference<Future<SessionResult>> result = new AtomicReference<>();
        onPlayer(scenario, player -> {
            Bundle args = new Bundle(); args.putString("video_decoder_mode", mode);
            result.set(player.sendCustomCommand(new SessionCommand("SET_VIDEO_DECODER_MODE", Bundle.EMPTY), args));
        });
        assertEquals(SessionResult.RESULT_SUCCESS, result.get().get(10, TimeUnit.SECONDS).resultCode);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean()) {
            assertTrue("Playback condition timed out", SystemClock.elapsedRealtime() < deadline);
            SystemClock.sleep(50);
        }
    }

    private void capture(ActivityScenario<PlayerActivity> scenario, int rotation, String stage) throws Exception {
        // Allow the compositor to latch the frame after seek or decoder initialization.
        SystemClock.sleep(500);
        Rect bounds = new Rect();
        scenario.onActivity(activity -> {
            SurfaceView surface = findSurface(activity.getWindow().getDecorView());
            assertNotNull(surface);
            assertTrue(surface.getGlobalVisibleRect(bounds));
        });
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        save(screenshot, stage + "-" + rotation);
        checkCorners(screenshot, bounds, rotation);
        screenshot.recycle();
        System.out.println("PASS " + rotation + " " + stage + " displayed=" + bounds);
    }

    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void checkCorners(Bitmap bitmap, Rect bounds, int rotation) {
        for (int corner = 0; corner < 4; corner++) {
            int x = bounds.left + bounds.width() * (corner % 2 == 0 ? 1 : 7) / 8;
            int y = bounds.top + bounds.height() * (corner < 2 ? 16 : 84) / 100;
            int pixel = bitmap.getPixel(x, y);
            int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
            int color = r > b + 50 && g > b + 50 ? 3 : r > g && r > b ? 0 : g > b ? 1 : 2;
            assertTrue("Corner is black", Math.max(r, Math.max(g, b)) > 80);
            assertEquals("Rotation " + rotation + " corner " + corner, CORNERS[rotation / 90][corner], color);
        }
    }

    private void save(Bitmap bitmap, String name) throws Exception {
        try (FileOutputStream file = new FileOutputStream(new File(evidence, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, file));
        }
    }
}
