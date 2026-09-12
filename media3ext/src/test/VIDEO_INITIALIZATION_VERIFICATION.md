# Video initialization validation

Validated 2026-09-12 against NextLib base `b39ebaf` and Next Player `ef203f8361442788010c184384a1da7d63212add`.

The decoder now preserves all H.264/HEVC initialization entries, forwards MPEG sequence headers and a single AV1 configuration record, and passes positive `Format.width`/`height` to `AVCodecContext` before `avcodec_open2`. Null list entries and aggregate size overflow fail with `FfmpegDecoderException`; native allocation includes zero padding and checks JNI copy failures. Malformed-data and native-open failures stop the SimpleDecoder thread.

## Source conventions checked

Inspected Media3 1.11.0 source jars: `Format`, `AvcConfig`, `HevcConfig`, `BoxParser`, `MatroskaExtractor`, and `H262Reader`; and FFmpeg 9.0.1 `libdav1d.c` and `mpeg12dec.c`. H.264 uses a variable-length list of start-code-prefixed parameter sets, HEVC supplies a combined start-code stream, MPEG uses sequence headers, and AV1 supplies an `av1C` record that libdav1d recognizes. VP9 container metadata is not treated as a concatenated elementary stream. See [Media3 Format](https://developer.android.com/reference/androidx/media3/common/Format) and [Media3 AVC parser](https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/AvcConfig.java).

`FfmpegVideoRenderer.createDecoder` already passes the full `Format`. Its only constructor call now reaches the updated Java/JNI initializer; the native AV1 regression caller was updated too. Discovery still uses the existing MIME mapping and `avcodec_find_decoder_by_name`; the distributed decoder list is unchanged.

## Results

| Check | Result |
| --- | --- |
| FFmpeg setup, all four Android ABIs | Pass, fresh isolated build, FFmpeg 9.0.1 / dav1d 1.5.4 |
| `:media3ext:assembleDebug :media3ext:testDebugUnitTest :media3ext:lintDebug` | Pass; 15 JVM tests, zero failures/errors/skips |
| Java/JNI instrumentation in Next Player | Pass, 3 tests; H.264 empty/in-band, single combined, four entries with required PPS after an empty second entry; HEVC, AV1, MPEG-2; malformed headers; known/unknown/incorrect dimensions |
| Initial decode plus three flush/replays | Pass; 144 frames per H.264/HEVC pass and 24 per AV1/MPEG-2 pass; decoded size 320×180 |
| Native initialization regression | Pass; discovery, zero/negative/extreme dimensions, empty/truncated data, padded copy and injected JNI copy exception |
| Dimension-dependent `msmpeg4v3` | Pass using test-only FFmpeg configuration; unknown/partial dimensions fail opening, 320×180 opens and decodes through three resets |
| Existing native `ffvideo_test` | Pass; VP9 backpressure/alt-ref/reset, color conversion and surface fault checks |
| Existing AV1 native regression | 8-bit passes initial decode and flush; 10-bit fails as described below |
| Next Player `assembleDebug test ktlintCheck` | Pass; 205 JVM tests, zero failures/errors/skips; `ignoreFailures = false` in disposable host |
| Additional Next Player `lintDebug` | Fails in unchanged `core/ui`: 510 existing translation errors, 11 warnings; first issue `MissingTranslation` for `about_description` |
| Next Player explicit FFmpeg playback and seeks | Pass for both H.264 and HEVC; three seek targets per codec, fresh frames after each, screenshots inspected |

The instrumentation invokes Media3's actual MP4 extractor, then production `FfmpegVideoDecoder` construction, `decode`, JNI initialization/send/receive/reset/release. It uses one decode thread and fixtures without frame reordering; it does not add EOS draining. A deliberately incorrect 1×1 H.264 container hint still yields the 320×180 bitstream dimensions. MPEG-2 uses external sequence headers and low-delay encoding so every input produces a frame.

## Fixtures and environment

All fixtures are generated synthetic `testsrc2`, 320×180, 12 fps, 8-bit YUV420P, video only. The runner contains the exact generation commands. H.264 has no B frames and a 12-frame GOP; HEVC uses `hvc1`, no B frames, and a 12-frame GOP. The table lists container extradata sizes before Media3 unpacking.

| Fixture | Codec / profile | Frames | Container extradata bytes |
| --- | --- | ---: | ---: |
| `av1.mp4` | av1 / Main | 24 | 17 |
| `h264.mp4` | h264 / Constrained Baseline | 144 | 38 |
| `hevc.mp4` | hevc / Main | 144 | 2438 |
| `mpeg2.mp4` | mpeg2video / Main | 24 | 22 |

The UI fixtures repeat the H.264/HEVC clips ten times with `ffmpeg -stream_loop 9 -i <fixture> -c copy <playback.mp4>`: 120 seconds, 1,440 frames. Full fixture probes and SHA-256 values are in the evidence directory's `fixtures.json`.

Task-owned AVD: `nextlib-init-7907-api36`, explicit serial `emulator-5790`, port 5790, API 36 / Android 16, ARM64, 4 KiB pages, 720×1280 display (landscape playback). Fingerprint: `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`. Created from the installed system image with its entire AVD directory under `/private/tmp/nextlib-init-7907/avd`; no other emulator or physical device was used. Android CLI supplied documentation lookup, layout inspection and screenshots. The emulator binary was used for startup because this CLI's create/start commands do not expose an explicit unique AVD name and port.

NDK 25.2.9519653, CMake 3.22.1. Next Player used its JDK 17 launcher/Java target and repository-pinned Gradle daemon runtime. NextLib native source, FFmpeg source/build/output and `.cxx` directories were isolated in this worktree. Next Player was cloned to `/private/tmp/nextlib-init-7907/nextplayer`, its `AGENTS.md` was followed, and `/Users/anil/Developer/personal/nextplayer` remained clean and unmodified.

## Runnable commands

From the NextLib checkout, with the installed Android SDK/NDK and a task-owned ARM64 emulator:

```sh
export ANDROID_HOME=/Users/anil/Library/Android/sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.2.9519653"
export ANDROID_SERIAL=emulator-5790
./gradlew :ffmpegSetup --no-daemon --max-workers=4
./gradlew :media3ext:assembleDebug :media3ext:testDebugUnitTest :media3ext:lintDebug --no-daemon --max-workers=4
bash media3ext/src/test/cpp/run_ffvideo_initialization_test.sh
NEXTPLAYER_CHECKOUT=/private/tmp/nextlib-init-7907/nextplayer \
  bash media3ext/src/test/android/run_video_initialization_test.sh
```

The Java/JNI runner uses Next Player's existing AndroidJUnitRunner and dependencies in the disposable host. It generates fixtures, builds with `-PnextlibPath=<this checkout>`, installs only on the explicit serial, and checks `OK (3 tests)` because `am instrument` can exit zero on a failed test. It leaves host source/assets in that disposable checkout for inspection.

Next Player's required checks were run with:

```sh
./gradlew -PnextlibPath=/Users/anil/.codex/worktrees/7907/nextlib \
  assembleDebug test ktlintCheck --no-daemon --max-workers=4 --console=plain
```

For the dimension-dependent case, copy the same FFmpeg source into an independent temporary directory, run `make distclean` only in that copy, then configure/build a test-only shared `libavcodec.so`:

```sh
ndk="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
./configure --prefix=/private/tmp/nextlib-init-7907/dimension-codec --enable-cross-compile \
  --arch=aarch64 --cpu=armv8-a --target-os=android \
  --cc="$ndk/aarch64-linux-android21-clang" --cxx="$ndk/aarch64-linux-android21-clang++" \
  --ar="$ndk/llvm-ar" --nm="$ndk/llvm-nm" --ranlib="$ndk/llvm-ranlib" --strip="$ndk/llvm-strip" \
  --disable-everything --disable-programs --disable-doc --disable-debug --disable-symver \
  --disable-avformat --disable-avfilter --disable-avdevice --disable-swscale --disable-swresample \
  --enable-shared --disable-static --enable-decoder=h264,hevc,msmpeg4v3 \
  --extra-cflags='-O2 -fPIC' --extra-ldflags='-Wl,-z,max-page-size=16384'
make -j4
make install
# Back in NextLib:
FFMPEG_TEST_LIB_DIR=/private/tmp/nextlib-init-7907/dimension-codec/lib \
  bash media3ext/src/test/cpp/run_ffvideo_initialization_test.sh
```

`msmpeg4v3` is not distributed or added to the Java MIME mapping. Its dimension-free frame header proves initialization needs container dimensions before opening. The native test calls the production JNI entry point and real decoder; only Java metadata/arrays are stubbed. The test-only library is used solely by the native executable in a unique device directory; it is never installed into Next Player. The runner removes that directory on exit.

Existing native video/AV1 scripts were run through task-local copies with every `adb` command changed to `adb -s "$ANDROID_SERIAL"` and unique task directories plus cleanup. Those copies are retained in the evidence parent directory.

## Playback and provenance

For each playback fixture, opened a VIEW intent, used UI-tree-derived coordinates to choose Video → SW → “Bundled FFmpeg software decoder”, then sought to 10%, 70%, and 25% of the seek bar. The control's horizontal padding produces positions around 9, 85, and 28 seconds. Playback advanced and rendered after each seek. Captures show the expected moving color pattern, correct aspect ratio, and SW selection.

Logs confirm `requested=FFMPEG as FFMPEG: ffmpegLavc63.1.101-h264` and `ffmpegLavc63.1.101-hevc`, with “Rendered first frame with video=FFMPEG” after decoder selection and every seek. No playback decoder failure, crash or fallback was logged. The malformed-header regression's expected invalid-data log is kept separately distinguishable by PID/time.

The pulled installed `base.apk`, built ARM64 APK, and this NextLib worktree's `merged_native_libs` output matched byte-for-byte for `libmedia3ext.so`, `libavcodec.so`, `libavutil.so`, `libswscale.so`, and `libswresample.so`. Next Player debug packages the unstripped JNI output; it also matches the local CMake object-directory library. `libmedia3ext.so` SHA-256:

`650cbfb4b82c4b2200d3340ece7a463e0e05b91f8d0ed0e9953ed84b311aceaf`

Raw logs, fixture hashes, commands, installed APK, and screenshots are retained at [/private/tmp/nextlib-init-7907/evidence](/private/tmp/nextlib-init-7907/evidence). In particular: `provenance.txt`, `reusable-java-jni-runner.log`, `native-initialization.log`, `native-dimensions.log`, `nextplayer-required-checks.log`, `h264-playback.log`, `hevc-playback.log`, `ui-commands.txt`, `h264-seek1.png`, `h264-seek3.png`, `hevc-seek1.png`, and `hevc-seek3.png`.

## Coverage limits

The existing 640×360 10-bit AV1/film-grain native fixture crashes with SIGILL in dav1d's `put_8tap_sve2` at library offset `0x552438`. Rebuilding its test against unchanged `ffvideo.cpp` from `b39ebaf` reproduces the same failure on this emulator. Both failure logs are retained (`native-av1.log`, `native-av1-baseline.log`, `native-crash.log`). This broader AV1 regression is not a passing check; no decoder or CPU-dispatch changes were included.

Runtime validation covers this API 36 ARM64 emulator only; other ABIs were built, not executed. Physical devices, 16 KiB-page runtime, 10-bit HEVC, encrypted media and reordered/EOS output were not exercised. UI playback uses H.264/HEVC; MPEG-2, AV1 and the dimension-dependent fixture were verified through the focused decoder tests.

Cleanup complete: the task-owned emulator was stopped, its process exited, and its AVD directory was removed; fixture/build/log evidence is retained. No shared checkout or other task's device resources are modified.
