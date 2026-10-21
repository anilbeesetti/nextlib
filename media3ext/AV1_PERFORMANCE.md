# AV1 performance investigation — Next Player #2025

Issue: <https://github.com/anilbeesetti/nextplayer/issues/2025>

## Finding and change

Next Player v0.18.0 selects `FfmpegVideoRenderer` / `libdav1d` on an Android 8
target without a platform AV1 decoder. Nextlib already builds dav1d 1.5.4 with
assembly enabled for all four ABIs. The problem is not a missing AV1 decoder.

Nextlib set `AV_CODEC_FLAG_LOW_DELAY` for AV1. FFmpeg translates this to dav1d's
`max_frame_delay = 1`, disabling parallel decoding of different frames. That
avoided lost output in Media3's `SimpleDecoder`, which assumes one output per
input and emits EOS without asking the codec to drain. Removing the flag alone
would lose the final delayed frames and filter seek output against the wrong PTS.

The video decoder now uses a buffer-pool worker adapted from Media3's
`SimpleDecoder`. It tolerates input without immediate output, retains the EOS
input until every delayed frame has been received, and applies seek filtering
to decoded frame timestamps. Output-pool capacity still provides backpressure.
Flush discards queued/draining output and resets native state on the next input.
The existing native packet retry and frame ownership logic is retained.

## Reproduction limits

The issue provides no media sample, resolution, frame rate, bit depth, or bitrate.
The Honor 6X / Kirin 655 and its VLC comparison were unavailable. This identifies
and fixes a reproducible throughput limitation in nextlib; it does not establish
that every AV1 file will play smoothly on that device or match VLC.

The Android target used here is a disposable x86_64 Android 8.0 / API 26 emulator,
four virtual CPUs, SwiftShader, **without KVM**. It is useful for decoder selection,
playback and correctness. Its software CPU emulation is not a physical-device
performance measurement. Android throughput numbers are not used as a speedup claim.

A focused emulator Simpleperf capture of 180 720p 8-bit frames, decode plus
RGBA conversion, recorded 14,255 CPU-clock samples with zero lost samples.
Self samples grouped by library were 76.72% in `libavcodec.so` (which contains
dav1d) and 10.02% in `libswscale.so`. The bundled FFmpeg libraries are stripped,
so this capture cannot identify individual decoder/assembly functions. The
emulator kernel supports frame-pointer sampling but not DWARF callchains.
These sampled CPU shares locate work; they do not measure physical-device
latency, scheduler waits, or the user's VLC comparison.

## Repeatable performance measurement

The committed native benchmark exercises production `JniContext::SendPacket`,
`ReceiveFrame`, and optionally `ScaleContext::Convert` to RGBA. The comparison
reopens the decoder with the previous low-delay flag; everything else is shared.
It drains both configurations fully and reports frame counts, elapsed time,
throughput and conversion time. It does not include Surface/BufferQueue costs.

Controlled host result: x86_64 AMD EPYC 9V74, four-CPU quota, four decoder
threads, FFmpeg 9.0.1 and dav1d 1.5.4 built from the pinned sources, seven
alternating runs per configuration, emulator paused and builds finished.
JNI metadata lookup was stubbed; no Android Surface calls were used. All runs
decoded all 180 frames. Median throughput:

| Input | Work | Previous low-delay | Frame threading | Change |
| --- | --- | ---: | ---: | ---: |
| 720p 8-bit | Decode | 439.6 fps | 615.6 fps | +40.1% |
| 720p 8-bit | Decode + RGBA | 393.1 fps | 622.9 fps | +58.5% |
| 720p 10-bit | Decode | 375.4 fps | 544.8 fps | +45.1% |
| 720p 10-bit | Decode + RGBA | 189.0 fps | 288.7 fps | +52.8% |

These are native throughput measurements on this host, not Android playback
frame rates. Frame threading also lets decoding overlap conversion. High-bit-depth
RGBA conversion remains substantial: its median accumulated conversion time was
464.5 ms out of 623.5 ms elapsed in the new 10-bit configuration. Conversion and
decoding overlap, so their times cannot be added to derive elapsed time.

Generate synthetic 720p, 30 fps, 180-frame film-grain input (FFmpeg 7.1.5 /
SVT-AV1 2.3.0 was used):

```sh
for depth in 8 10; do
  pix=yuv420p
  [ "$depth" = 8 ] || pix=yuv420p10le
  ffmpeg -f lavfi -i 'testsrc2=size=1280x720:rate=30' -frames:v 180 \
    -c:v libsvtav1 -preset 10 -crf 35 -pix_fmt "$pix" \
    -svtav1-params 'lp=4:film-grain=8' "av1-720p${depth}.ivf"
done
ANDROID_NDK_HOME=/path/to/ndk/25.2.9519653 ANDROID_SERIAL=your-device \
  bash media3ext/src/test/cpp/run_av1_benchmark.sh av1-720p8.ivf 7 4
```

Use a physical device for device performance conclusions. The runner detects
ARM64, ARMv7, x86 or x86_64, alternates comparison order, and tests decode-only
and decode-plus-RGBA conversion. It removes its temporary device files afterwards.

## Correctness checks

- Native AV1 regression: all 48 frames for each 640×360 8-bit/10-bit film-grain
  clip, ordered timestamps, two delayed frames drained at EOS, and the same
  result after seek/reset.
- Android AV1 regression: clips shorter than frame delay, 24-frame 8-bit/10-bit
  clips, one output buffer, EOS ordering, PTS-based seek filtering, flush/replay,
  native frame release, and both Surface and planar YUV output.
- Worker unit tests: delayed output, EOS backpressure, seek filtering, flush
  while an output is held, and a flush racing with active decoding.
- Existing native VP9 packet retry/hidden alt-ref, error classification,
  colors/range, rotation, invalid-window-buffer, and renderer lifecycle tests.

```sh
python3 ffmpeg/test_setup.py
./gradlew assembleDebug assembleRelease test --max-workers=4
./gradlew :media3ext:connectedDebugAndroidTest --max-workers=4
ANDROID_NDK_HOME=/path/to/ndk/25.2.9519653 ANDROID_SERIAL=your-device \
  bash media3ext/src/test/cpp/run_av1_decoder_test.sh
```

Next Player integration uses `-PnextlibPath=../nextlib`. Its current AGP 9.4.1
differs from nextlib's 9.4.0, so validation uses this temporary init script
without changing either repository's dependency declarations:

```groovy
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.versionCatalogs
        .maybeCreate('libs').version('agp', '9.4.1')
}
gradle.projectsEvaluated {
    gradle.rootProject.allprojects {
        tasks.withType(Test).configureEach { ignoreFailures = false }
    }
}
```

```sh
./gradlew -PnextlibPath=../nextlib -I /tmp/av1-validation.gradle \
  assembleDebug test ktlintCheck --max-workers=4
```

Validation results: all four Nextlib release ABIs built; 15 library JVM tests,
9 Android instrumentation tests, and 320 Next Player JVM tests passed with
zero failures/errors/skips. Next Player `ktlintCheck` passed. The installed
debug APK's x86_64 JNI library matched the local Nextlib output byte-for-byte.

In the composite app on API 26, 40-second 640×360 / 24 fps 8-bit and 10-bit
AV1 film-grain clips with AAC audio selected
`ffmpegLavc63.1.101-libdav1d` automatically and rendered moving frames.
Paused forward/backward seek positions were 16,101 / 11,101 ms for 8-bit and
15,641 / 10,641 ms for 10-bit. The settled 10-bit forward-seek screenshot
displayed frame 376 at 15.667 seconds, matching the requested position.
Playback logs contained no FFmpeg send/receive or playback errors; the crash
buffer was empty. This verifies integration, not Honor 6X performance.
