# FFmpeg playback rotation verification — 2026-09-12

Playback now applies Media3 `Format.rotationDegrees` clockwise exactly once in
both Surface and planar YUV output. Quarter turns report swapped output dimensions.
Equivalent right-angle values normalize safely; other angles fall back to zero.
The original `Format`, including pixel aspect metadata, remains attached to the
output buffer. Thumbnail code is unchanged.

## Source contract

Inspected `FfmpegVideoRenderer`, `FfmpegVideoDecoder`, `ffvideo.cpp`, Media3 1.11.0
`Format`, `VideoDecoderOutputBuffer`, `DecoderVideoRenderer`,
`VideoDecoderGLSurfaceView`, `MediaCodecVideoRenderer`, `PlayerView`, and Compose
`PresentationState`, plus Next Player's `PlayerContentFrame` and thumbnail decoder.
Next Player resolves Media3 1.11.1 in its composite build.

`Format` defines clockwise rotation. Software output buffers contain dimensions
and pixels, with no unapplied-rotation field; `DecoderVideoRenderer` reports those
buffer dimensions as `VideoSize`. Its inherited no-reuse policy recreates the
FFmpeg decoder on format changes. Next Player's SurfaceView and presentation
state size the already-oriented frame and apply no additional rotation.
MediaCodec supplies its own rotation when that renderer is selected.

The [public NDK window transform](https://developer.android.com/ndk/reference/group/a-native-window#anativewindow_setbufferstransform)
requires API 26; the library supports API 23. One conversion helper therefore
rotates converted RGBA or YUV planes, retaining the existing direct conversion
for zero rotation. It reuses a private scratch frame for rotated output. Color,
range, source strides, decoded AVFrame ownership, timestamps, decoder selection,
and reset/draining behavior retain their existing paths. No Surface transform
state is installed. Thumbnail retrievers already rotate their bitmaps and remain
independent.

## Build and automated results

Base: NextLib `1df6554`; `origin/main` was fetched and still matched this base.
Next Player `ef203f83` was cloned from the requested checkout into
`/tmp/nextlib-rotation-2627/nextplayer`. Its shared checkout was not edited.
Only test setup was changed in the clone: enforced `Test.ignoreFailures = false`,
the supplied integration test, and its Media3 session test dependency.

NDK 25.2.9519653, CMake 3.22.1, Gradle 9.7.1, repository-pinned JDK 21 daemon,
Java/Kotlin target 17. The existing FFmpeg 9.0.1 dependencies were copied into this
worktree from the completed FFmpeg build in `cc87/nextlib`; all NextLib JNI code
was compiled here. `-x ffmpegSetup` / `-x :nextlib:ffmpegSetup` below reuse only
those dependency binaries, not another worktree's JNI output.

Passed:

```sh
ANDROID_HOME=/Users/anil/Library/Android/sdk ./gradlew \
  :media3ext:assembleDebug :mediainfo:assembleDebug \
  :media3ext:testDebugUnitTest :media3ext:assembleDebugAndroidTest \
  :media3ext:lintDebug -x ffmpegSetup --max-workers=2 --console=plain

ANDROID_NDK_HOME=/Users/anil/Library/Android/sdk/ndk/25.2.9519653 \
ANDROID_SERIAL=emulator-5682 bash media3ext/src/test/cpp/run_ffvideo_test.sh

# In the disposable Next Player checkout:
ANDROID_HOME=/Users/anil/Library/Android/sdk ./gradlew \
  -PnextlibPath=/Users/anil/.codex/worktrees/2627/nextlib \
  assembleDebug test ktlintCheck :app:assembleDebugAndroidTest \
  -x :nextlib:ffmpegSetup --max-workers=2 --console=plain
```

- Both library debug AARs built for ARM64, ARMv7, x86, and x86_64.
- 11 NextLib JVM tests and 205 Next Player JVM tests passed, zero failures/skips.
- Library lint and Next Player Kotlin formatting passed.
- Native regression passed on API 34: 0/90/180/270 rotation for RGBA and YUV,
  13×7 dimensions and odd chroma, padded and negative source strides, 10-bit
  4:4:4/full-range conversion, plus existing color/cache, ownership/error, VP9
  retry and flush checks.
- Seven library instrumentation tests passed on API 34 and API 36. The final
  API 36 run covers 16 angle cases, including large positive/negative equivalent
  values, integer extremes, and non-right-angle fallback. It checks every active
  RGBA/YUV pixel, output dimensions, original metadata, timestamps, native frame
  release, alternating output modes, repeated flushes, and new ImageReader
  Surfaces. API 34 ran the same tests before the two large equivalent angles were
  added.
- The focused Next Player integration test passed in 44.329 seconds.

The additional `:feature:player:lintDebug` check failed with 18 errors, 8 warnings,
and one hint in existing app code, including `WrongConstant` at
`PlayerService.kt:594` and other existing SessionResult calls. No lint errors
were suppressed. The complete report is in the evidence bundle.

## Devices, fixtures, and displayed frames

Both emulators were created solely for this task using isolated AVD files under
`/tmp/nextlib-rotation-2627/avd`. They used port 5682 sequentially; every adb command
explicitly selected `emulator-5682`.

- `nextlib_rotation_2627_api34`: ARM64 Android 14/API 34 desktop system image;
  native and initial decoder instrumentation checks.
- `nextlib_rotation_2627_api36`: ARM64 Android 16/API 36 Google Play phone image,
  1080×1920 (1920×1080 in landscape), 420 dpi, 4 KB pages, SwiftShader;
  real app playback and final decoder instrumentation.
- API 36 fingerprint:
  `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`.

The checked-in [fixture generator](src/test/cpp/fixtures/generate_rotation_fixtures.sh)
creates H.264 constrained-baseline, YUV420P, 640×360, 24 fps, 300-second clips,
with no audio or B frames. All four files contain the same coded picture:
A/red top-left, B/green top-right, C/blue bottom-left, D/yellow bottom-right.
Only their MP4 display matrix differs. FFprobe JSON and SHA-256 values are saved
in the evidence bundle's `fixtures/` and `fixtures.txt`.

| Clockwise metadata | FFprobe matrix angle | Reported VideoSize | TL, TR, BL, BR | Result |
| --- | --- | --- | --- | --- |
| 0 | absent (0) | 640×360 | A, B, C, D | Pass |
| 90 | −90 | 360×640 | C, A, D, B | Pass |
| 180 | −180 | 640×360 | D, C, B, A | Pass |
| 270 | 90 | 360×640 | B, D, A, C | Pass |

Explicit FFmpeg video selection was verified through Next Player's decoder
selector (Video → SW / Bundled FFmpeg software decoder) and then through its
same production session command in the integration test. Successful-run logs
identify `ffmpegLavc63.1.101-h264`, hardware `c2.goldfish.h264.decoder`, and Android
software `c2.android.avc.decoder`.

For every angle, the integration test verifies `VideoSize`, active decoder,
paused seeks to 1.5, 17.1, 42.5, and 1.2 seconds, pause/resume, Activity recreation
with a new Surface, paused FFmpeg → hardware → FFmpeg → Android software → FFmpeg,
and playing FFmpeg → hardware → FFmpeg. Screenshots assert expected corner colors.
Saved labels were also visually inspected for all angles, with representative
recreation and hardware/software comparisons. Frames retain the expected
orientation and aspect ratio throughout. Each next fixture creates a new decoder
and exercises changed rotation metadata.

Both `MediaThumbnailRetriever.getFrameAtTime(1_000_000)` and `getFrameAtIndex(0)`
returned correctly rotated dimensions/corners for all four files. The time-based
thumbnails were visually inspected. There is no extra thumbnail rotation.

Next Player exposes only the Surface output. The YUV output is covered through
the real NextLib decoder/JNI instrumentation test; an app GL display was not
introduced. See the [integration test instructions](src/test/nextplayer/README.md).

## Exact local binary and evidence

The installed Next Player APK was pulled back from emulator-5682. Its ARM64
`libmedia3ext.so` is byte-identical to this worktree's unstripped debug JNI output:

```text
SHA-256 13800c3238b5f9baf9481cd3fde7bb6e6708fe797e84580821a2e90f43448dc0
ELF build ID 4c83462134e096a1fa6ad78819e7d033fad5c637
```

Persistent local evidence directory:
`/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation`.
It contains the 44 screenshots/thumbnails, real MP4 fixtures and metadata, native
and instrumentation results, Gradle logs, app lint report, successful decoder
logcat, binary hash, emulator identity/configuration, and UI action helper.
Playback screenshots: [0°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/ffmpeg-0.png), [90°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/ffmpeg-90.png), [180°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/ffmpeg-180.png), [270°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/ffmpeg-270.png).
Thumbnail screenshots: [0°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/thumbnail-0-0.png), [90°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/thumbnail-90-0.png), [180°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/thumbnail-180-0.png), [270°](/Users/anil/.codex/visualizations/2026/09/12/01a093f5-838e-70e3-93f6-7bf1e7ba802f/rotation-validation/rotation-evidence/thumbnail-270-0.png).

The disposable checkout remains at `/tmp/nextlib-rotation-2627/nextplayer` for
reproduction. Generated fixture media and APKs are not committed.

Runtime coverage is ARM64 on API 34 and 36; API 23–33 and physical devices were
not run. All four ABIs compiled against minimum API 23. Real app metadata fixtures
use MP4/H.264; other codecs/containers, HDR presentation, high-resolution rotation
performance, and pixel aspect correction are outside this validation.
The initial app test attempt encountered the Android CLI layout server holding
UiAutomation; stopping that task-owned service allowed the full test to pass.

Both task-owned emulators were stopped and their disposable AVD data removed.
Only configuration copies and validation evidence were retained.
