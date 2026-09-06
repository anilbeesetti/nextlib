# dav1d AV1 verification — 2026-09-06

Tested Nextlib `ceabc33` on `codex/dav1d` against Next Player `5b5747c7` using
Next Player's `nextlibPath` composite-build override. Next Player required no
source or published dependency changes.

## Implementation

- dav1d 1.5.4 is built with assembly and both bit-depth implementations for
  ARM64, ARMv7, x86, and x86_64, then statically linked into `libavcodec.so`.
- `video/av01` selects `libdav1d`; native lookup by AV1 codec ID also finds it.
- AV1 uses `AV_CODEC_FLAG_LOW_DELAY` to match Media3 `SimpleDecoder`, which
  receives one frame per sample and does not drain the decoder at EOS.

The low-delay setting matters: a probe matching the old send/receive loop
produced 839 frames from a 960-sample clip, with 119 rejected packets and two
frames still buffered at EOS. Low-delay mode produced all 960 frames with zero
rejected packets and zero delayed frames for both 8-bit and 10-bit clips.
The committed native regression exercises production decoder initialization;
it failed before the fix and passes after it.

## Build and automated checks

Passed in the Nextlib worktree:

```sh
python3 ffmpeg/test_setup.py
./gradlew assembleDebug assembleRelease test
ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653 ANDROID_SERIAL=emulator-5582 \
  bash media3ext/src/test/cpp/run_av1_decoder_test.sh
ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653 ANDROID_SERIAL=emulator-5582 \
  bash media3ext/src/test/cpp/run_ffvideo_test.sh
```

- 11 JVM tests passed, including AV1 MIME-to-decoder selection.
- Native AV1 tests decoded all 48 frames in each 8-bit and 10-bit film-grain
  fixture, then all 48 again after seek/flush. Each sample produced one frame;
  no output remained at EOS.
- Existing color matrix/range and invalid-surface-buffer regression tests passed.
- All four `libavcodec.so` files contain dav1d 1.5.4, have no dynamic
  `libdav1d.so` dependency, and use at least 16 KB ELF LOAD alignment.

Next Player's `assembleDebug`, `test`, and `ktlintCheck` passed with
`-PnextlibPath=/path/to/nextlib/.worktrees/dav1d`. The test run enforced
`Test.ignoreFailures = false` through a temporary Gradle init script:

```groovy
gradle.projectsEvaluated {
    gradle.rootProject.allprojects {
        tasks.withType(Test).configureEach { ignoreFailures = false }
    }
}
```

The run used `-x :dav1d:ffmpegSetup` to reuse the separately verified native
build above. A normal composite build can omit that exclusion. All 155 Next
Player tests passed. The final APK's FFmpeg libraries match the worktree's
libraries byte-for-byte for every ABI; the ARM64 JNI library's ELF build ID
also matches the worktree build.

## Next Player emulator flow

Disposable ARM64 Pixel 6a profile, Android 17 / API 37, Android 37.1 system
image with 16 KB pages, 720×1600 display, SwiftShader rendering.
Fixtures: 40-second MP4s, 640×360 at 24 fps, AV1 Main profile, 8-bit and 10-bit
4:2:0 with film grain, AAC audio. Generated with the host SVT-AV1 encoder.

- Both clips first used Android software AV1, then switched through the UI's
  **SW** option to `ffmpegLavc60.3.100-libdav1d`. Logs explicitly reported
  `requested=FFMPEG as FFMPEG`.
- Both clips showed correct colors and moving frames with the session in
  `PLAYING` state. Screenshots were inspected visually.
- The 8-bit clip sought backward to 9,518 ms. Switching back to Android's AV1
  decoder preserved `PAUSED` state and exactly 9,518 ms.
- The 10-bit clip sought forward to 32,464 ms and rendered the corresponding
  frame while paused.
- AAC remained on `c2.android.aac.decoder`; selecting a new clip reset the
  video choice to automatic selection.
- The final app log contained no send/receive packet errors or playback errors.
  The final crash buffer was empty.

Runtime coverage is ARM64 on this emulator. Other ABIs were built and inspected;
physical-device performance, HDR output, and 12-bit media were not tested.
