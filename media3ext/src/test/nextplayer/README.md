# Playback rotation integration check

`RotationPlaybackTest.java` runs against Next Player's actual `PlayerActivity`,
`MediaController`, decoder selection commands, and SurfaceView. It is a test
source to copy into a disposable Next Player checkout, not a NextLib runtime dependency.

1. Make an isolated Next Player checkout and read its `AGENTS.md`.
2. Copy `RotationPlaybackTest.java` into
   `app/src/androidTest/java/dev/anilbeesetti/nextplayer/`.
3. Add `androidTestImplementation(libs.androidx.media3.session)` to that checkout's
   app dependencies. Set Gradle `Test.ignoreFailures = false` there.
4. Generate fixtures with
   `bash <nextlib>/media3ext/src/test/cpp/fixtures/generate_rotation_fixtures.sh <fixture-dir>`.
   This requires Python 3 and FFmpeg with libx264 and `-display_rotation` (tested with 9.0.1).
5. Create a task-owned ARM64 emulator and set `ANDROID_SERIAL` to its explicit serial.
   Push the four MP4 files into `/sdcard/Movies/` using `adb -s "$ANDROID_SERIAL"`.
6. Build `assembleDebug test ktlintCheck :app:assembleDebugAndroidTest` with
   `-PnextlibPath=<nextlib>` and install the app and instrumentation APKs explicitly
   on that emulator. Grant the app `android.permission.READ_MEDIA_VIDEO`.
7. Stop any layout-inspection instrumentation **on that emulator** before this test;
   it needs exclusive UiAutomation access for screenshots.
8. Run and enforce the result:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.anilbeesetti.nextplayer.RotationPlaybackTest \
  dev.anilbeesetti.nextplayer.debug.test/androidx.test.runner.AndroidJUnitRunner > rotation-test.log
python3 - <<'PY'
from pathlib import Path
assert 'OK (1 test)' in Path('rotation-test.log').read_text()
PY
```

Pull `/sdcard/Android/data/dev.anilbeesetti.nextplayer.debug/files/rotation-evidence/`
with the same explicit serial. The test checks reported `VideoSize`, paused seek
positions, playback state, active decoder mode, and screenshot corner colors.
Review the saved labels visually as well. It uses four corner colors, with letters
A (red), B (green), C (blue), and D (yellow), in a 640×360 coded frame.

| Clockwise rotation | Display size | TL, TR, BL, BR |
| --- | --- | --- |
| 0 | 640×360 | A, B, C, D |
| 90 | 360×640 | C, A, D, B |
| 180 | 640×360 | D, C, B, A |
| 270 | 360×640 | B, D, A, C |

Next Player exposes the Surface path. NextLib's
`FfmpegVideoRendererTest.rotationReachesSurfaceAndYuvPixelsAcrossFlushAndSurfaceRecreation`
exercises both JNI output paths directly with a real decoder and ImageReader,
checking every active pixel and original metadata. `run_ffvideo_test.sh` also
covers odd dimensions, negative/padded source strides, and 10-bit conversion.
