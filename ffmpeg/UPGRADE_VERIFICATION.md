# FFmpeg and mbedTLS upgrade verification — 2026-09-06

NextLib implementation: `a22355c` on `codex/update-ffmpeg-mbedtls`.

- [FFmpeg 9.0.1](https://ffmpeg.org/download.html) replaces 6.0.
- [mbedTLS 3.6.7](https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7)
  replaces 3.4.1. It stays on the 3.6 LTS branch and requires no FFmpeg patch.
- dav1d remains at 1.5.4.
- The build uses the complete mbedTLS release archive, static PIC libraries,
  NASM for FFmpeg x86 assembly, and Clang's `x86-64` CPU spelling.
- Rotation metadata now comes from `AVCodecParameters::coded_side_data`,
  replacing FFmpeg's removed `av_stream_get_side_data` API.

## Library checks

Passed with JDK 17, NDK 25.2.9519653, and CMake 3.22.1:

```sh
python3 ffmpeg/test_setup.py
bash -n ffmpeg/setup.sh mediainfo/src/test/cpp/run_rotation_test.sh
./gradlew assembleDebug assembleRelease test
```

Both modules built debug and release AARs for ARM64, ARMv7, x86, and x86_64.
All 11 JVM tests passed. Every ABI's five FFmpeg shared libraries has at least
16 KB ELF LOAD alignment. The binaries contain FFmpeg 9.0.1, mbedTLS 3.6.7,
and dav1d 1.5.4, with no dynamic mbedTLS or dav1d dependency.

## Emulator checks

Disposable Pixel 6a profile, ARM64, Android 17 / API 37, Android 37.1 system
image with 16 KB pages, 720×1600 display, SwiftShader rendering.

```sh
export ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653
export ANDROID_SERIAL=emulator-5584
bash media3ext/src/test/cpp/run_av1_decoder_test.sh
bash media3ext/src/test/cpp/run_ffvideo_test.sh
bash mediainfo/src/test/cpp/run_rotation_test.sh
```

- AV1 8-bit and 10-bit film-grain clips each produced all 48 frames before
  and after seek/flush, with no delayed frames at EOS.
- VP9 packet retries, hidden alt-ref frames, pending-output reset, decoder
  discovery, color conversion, and invalid surface buffer regressions passed.
- Rotation tests passed for absent metadata, negative rotate tags, display
  matrix precedence, 90°/180° matrices, and truncated matrix fallback.
- An ARM64 `avio_open2`/`avio_read` smoke test fetched a known payload three
  times from a local HTTPS server with certificate verification enabled and
  a generated trusted CA. A fourth connection using an unrelated CA failed
  as expected. This passed separately with the server restricted to TLS 1.2
  and TLS 1.3. The probe also asserted `av_version_info()` equals `9.0.1`.

Runtime coverage is ARM64 on this emulator; the other ABIs were built and
their ELF files inspected.

## Next Player integration

Next Player `dbc2a15f` passed `assembleDebug test ktlintCheck` with
`-PnextlibPath=/path/to/nextlib/.worktrees/ffmpeg-mbedtls-update` and a temporary
Gradle init script (`-I /path/to/validation.gradle`):

```groovy
settingsEvaluated { settings ->
    if (settings.rootDir.name == 'nextplayer') {
        settings.dependencyResolutionManagement.versionCatalogs.maybeCreate('libs')
            .version('androidGradlePlugin', '9.4.0')
    }
}
gradle.projectsEvaluated {
    gradle.rootProject.allprojects {
        tasks.withType(Test).configureEach { ignoreFailures = false }
    }
}
```

The temporary override aligns Next Player's AGP 9.3.1 with NextLib's existing
AGP 9.4.0 for composite-build validation; no Next Player source changes were made.
All 167 Next Player tests passed with no failures, errors, or skips. The FFmpeg
libraries in every ABI APK match this worktree's libraries byte-for-byte.

On the disposable emulator:

- The 40-second 10-bit AV1 clip rendered correctly after selecting **Video → SW**.
  Logs confirmed `ffmpegLavc63.1.101-libdav1d` and a rendered first frame.
- Selecting **Audio → SW** initialized `ffmpegLavc63.1.101-aac`; playback
  advanced and paused normally. Audio output was checked through decoder and
  playback state, not by listening to the headless emulator.
- Seeking while paused reached 29,173 ms and displayed the corresponding frame.
- H.264 playback initialized `ffmpegLavc63.1.101-h264` and rendered moving frames.
- A native probe using the production thumbnail rotation reader opened a real
  MP4 carrying a 90° counterclockwise display matrix and correctly returned
  270° clockwise. Its metadata was independently checked with host `ffprobe`.
- Playback screenshots were visually inspected. App logs contained no playback
  or packet send/receive errors, and the final crash buffer was empty.

### Observed playback limitations

The existing FFmpeg surface renderer does not apply the rotated MP4's display
matrix to playback. Its Java and C++ rendering code is unchanged by this upgrade;
the API migration above covers media-info and thumbnail rotation metadata.
Also, this emulator's Android software AV1 decoder displayed corrupted 10-bit
output before switching to FFmpeg; FFmpeg's dav1d output rendered correctly.
