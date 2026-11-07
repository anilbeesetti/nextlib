# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![Maven Central](https://img.shields.io/maven-central/v/io.github.anilbeesetti/nextlib-media3ext.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8 and VP9 (FFmpeg built-in decoders), AV1 (dav1d)
- **Subtitles**: ASS/SSA (libass), PGS, VobSub and DVB (FFmpeg); other text formats retain Media3 parsing.

## Setup
Kotlin DSL:

```kotlin
dependencies {
    implementation("io.github.anilbeesetti:nextlib-media3ext:INSERT_VERSION_HERE") // To add media3 software decoders and extensions
    implementation("io.github.anilbeesetti:nextlib-mediainfo:INSERT_VERSION_HERE") // To get media info through ffmpeg
}
```

Groovy DSL:

```gradle
dependencies {
    implementation "io.github.anilbeesetti:nextlib-media3ext:INSERT_VERSION_HERE" // To add media3 software decoders and extensions
    implementation "io.github.anilbeesetti:nextlib-mediainfo:INSERT_VERSION_HERE" // To get media info through ffmpeg
}
```

## Basic usage

Use `NextRenderersFactory` with extension renderers enabled to make the bundled FFmpeg decoders
available to Media3. The factory retains `DefaultRenderersFactory`'s default of `OFF`:

```kotlin
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

// OFF: Do not add FFmpeg audio/video extension renderers.
// ON: Enable them at normal priority, after platform renderers.
// PREFER: Give FFmpeg extension renderers priority.
val renderersFactory = NextRenderersFactory(applicationContext)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```

This controls renderer priority and capability fallback: `ON` allows FFmpeg when platform
renderers cannot support a format. It does not automatically recover from runtime decoder failures;
applications own error recovery.

## Subtitles

Configure the media source and renderers together. Native subtitle routing is independent
of the audio/video extension renderer priority.

```kotlin
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles

val dataSources = DefaultDataSource.Factory(context)
val sources = DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory())
    .setDataSourceFactory(dataSources)
    .withFfmpegSubtitles(dataSources)
val player = ExoPlayer.Builder(context)
    .setRenderersFactory(NextRenderersFactory(context))
    .setMediaSourceFactory(sources)
    .build()
```

Pass the same data source factory to both calls so external subtitles share URI support,
authentication headers and caches. Call `withFfmpegSubtitles` after configuring the default
factory; set a custom load-error policy on the returned factory to apply it to sidecars too.
To retain other container settings, pass a configured `DefaultExtractorsFactory` to
`FfmpegSubtitleExtractorsFactory(delegate, matroskaFlags)`. Set Matroska flags explicitly
in the second argument because its extractor is replaced. Configure a custom subtitle
parser through `setSubtitleParserFactory` on the wrapper.
Attach the player to Media3's `PlayerView`, or forward its cues to a `SubtitleView`.

ASS/SSA uses libass for styles, drawings, positioning, animation and karaoke. PGS,
VobSub and DVB use FFmpeg bitmap decoding. SRT, WebVTT, TTML and MP4 timed text retain
Media3 parsing. The extractor also supports WebM `D_WEBVTT/SUBTITLES` and
`D_WEBVTT/CAPTIONS` tracks. Keep Media3's default canvas output for ordinary text;
applications needing vertical text or ruby can select `SubtitleView.VIEW_TYPE_WEB`.

For an external ASS/SSA file, add a regular subtitle configuration to the video item:

```kotlin
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
    .setMimeType(MimeTypes.TEXT_SSA)
    .setLanguage("en")
    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    .build()
player.setMediaItem(
    MediaItem.Builder()
        .setUri(videoUri)
        .setSubtitleConfigurations(listOf(subtitle))
        .build()
)
player.prepare()
player.play()
```

Native cues are already rasterized. Match their overlay bounds, aspect ratio, zoom and
pan to the video, preserving authored placement rather than allowing subtitle dragging.
`SubtitleView` text size, typeface and embedded-style controls cannot restyle these bitmaps.
Ordinary text cues can retain the application's existing positioning and styling controls.

`NextRenderersFactory` automatically forwards `SurfaceView`/`TextureView` dimensions.
For a bare `Surface` or a custom renderer factory, use the
`io.github.anilbeesetti.nextlib.media3ext.ffdecoder.setSubtitleViewportSize` extension
as `player.setSubtitleViewportSize(width, height)`. Rendering is capped at 1920 × 1080;
unknown dimensions retain the last usable canvas (initially 1280 × 720).

The existing timing extensions apply to native and Media3 subtitles:

```kotlin
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed

player.subtitleDelayMilliseconds = 500L
player.subtitleSpeed = 1.002f
```

Matroska font attachments are loaded through `FfmpegSubtitleExtractorsFactory`.
Embedded ASS `[Fonts]` sections are supported, with Android system fonts as fallback.
Custom renderer integrations can supply a fonts directory to `FfmpegTextRenderer`.
Attachment extraction accepts up to 64 fonts, 16 MiB per font and 32 MiB total;
attachments after media clusters are loaded only when encountered.

External ASS currently uses a single-period merged source. Multi-period manifests,
server-side ads, DRM subtitles, live ASS pruning and standalone `.sup`/`.idx` files
are outside the verified scope. ASS scripts/samples are limited to 16 MiB and bitmap
canvas dimensions to 4096. VobSub without codec-private dimensions assumes a 720 × 576
PAL canvas; provide explicit dimensions for other layouts. Sparse bitmap packets discarded from the playback buffer
cannot be recovered by the renderer alone; selecting a DVB track after playback has
advanced may require waiting for the next cue or reopening with the track selected.

To run the subtitle regressions on an already booted disposable emulator (requires
host FFmpeg and Python 3):

```sh
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5554 \
  bash media3ext/src/test/subtitles/run_subtitle_test.sh
```

The runner generates synthetic media, builds and installs the test APK, and verifies
the instrumentation result. Generated movies are ignored by Git. Tests cover native
rendering, Media3 fallback, fonts, viewport changes, timing, seeks and track selection.

## Runtime decoder switching

Create a `DecoderManager` alongside `NextRenderersFactory` when the application needs to select
video and audio decoders while keeping the same `ExoPlayer` instance:

```kotlin
val decoderManager = DecoderManager()
val renderersFactory = NextRenderersFactory(applicationContext)
    .setDecoderManager(decoderManager)
val player = ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()

decoderManager.attach(player)
decoderManager.selectVideoDecoder(DecoderMode.HARDWARE)
decoderManager.selectAudioDecoder(DecoderMode.AUTO)

decoderManager.detach()
player.release()
```

Installing a `DecoderManager` enables extension renderers at normal priority (`ON`) and MediaCodec
initialization fallback. Video and audio are selected independently. See
[`media3ext/DECODER_SWITCHING.md`](media3ext/DECODER_SWITCHING.md) for mode behavior, lifecycle, and
application-owned error handling.

## Building from source

Use macOS or Linux (WSL on Windows), JDK 17+, `make`, `curl`, `tar`, and
`pkg-config`, Python 3, Meson 1.6+, Ninja, gperf, and NASM 2.14+
(`brew install meson ninja nasm gperf` on macOS). On Ubuntu, install
`ninja-build nasm gperf python3-venv`, then install Meson in a virtual environment
and add that environment's `bin` directory to PATH; CI pins Meson 1.12.0.
NASM supplies x86 assembly for FFmpeg, dav1d and libass. Install [Android CLI](https://developer.android.com/tools/agents/android-cli/download)
and put `android` on PATH, or set `ANDROID_CLI` to its executable path.
Set `sdk.dir` in `local.properties` or export `ANDROID_HOME` to your Android SDK.

```sh
./gradlew assembleRelease
```

Both modules depend on one `:ffmpegSetup` task, which installs missing NDK/CMake
packages with Android CLI and builds the four supported ABIs before CMake runs.
NDK and CMake versions come from `gradle/libs.versions.toml`. Existing SDK tools
are reused; Android CLI is only needed when a package is missing. Complete any
SDK license prompts during initial installation before running a headless build.

The build pins FFmpeg 9.0.1, mbedTLS 3.6.7 (the 3.6 LTS branch), and dav1d 1.5.4.
mbedTLS is built from its complete release archive and linked statically for
HTTPS/TLS support. The build statically links dav1d into FFmpeg's `libavcodec.so`
for every ABI, with assembly optimizations and both 8-bit and high-bit-depth AV1
support. AV1 uses the `libdav1d` decoder in Media3 and in media-info/thumbnail lookups.

Subtitle rendering builds upstream libass 0.17.5 directly as `libass.so` for each
ABI. FreeType 2.14.1, FriBidi 1.0.16, HarfBuzz 14.4.0, Fontconfig 2.16.0,
Expat 2.8.4 and libunibreak 7.0 are linked statically into it. The build installs
headers and pkg-config metadata under `ffmpeg/build/external/<abi>` and publishes
libass headers/binaries under `ffmpeg/output`. Dependency license notices are
packaged in the media3ext AAR's `assets/native-dependencies` directory.

Gradle tracks the setup script, tool versions, and generated output so unchanged
builds skip FFmpeg. To force rebuilding it:

```sh
./gradlew :ffmpegSetup --rerun-tasks
```

For standalone use, export `ANDROID_HOME` and run `bash ffmpeg/setup.sh` (always
rebuilds). Run `python3 ffmpeg/test_setup.py` for setup regression checks without
SDK downloads or native compilation.
After `assembleRelease`, run `python3 ffmpeg/test_native_package.py` with
`ANDROID_HOME` set to check the AARs' native dependencies, exports and 16 KiB alignment.

To verify AV1 decoding and seeking on a disposable Android target, build
the library and run the native regression test (requires host FFmpeg with the
`libsvtav1` encoder):

```sh
ANDROID_NDK_HOME=/path/to/sdk/ndk/25.2.9519653 ANDROID_SERIAL=emulator-5554 \
  bash media3ext/src/test/cpp/run_av1_decoder_test.sh
```

This checks decoder discovery by both name and codec ID, 8-bit and 10-bit output,
film-grain decoding, frame timestamps, draining delayed frames before EOS,
and decoding again after a seek/flush. AV1 retains dav1d's frame threading;
the video decoder handles outputs that arrive after their corresponding input.

See [AV1 performance investigation](media3ext/AV1_PERFORMANCE.md) for the
Next Player #2025 investigation and a repeatable Android decoder benchmark.

See [AV1 verification](media3ext/AV1_VERIFICATION.md) for the Next Player playback
checks and tested device coverage.

See [FFmpeg and mbedTLS upgrade verification](ffmpeg/UPGRADE_VERIFICATION.md) for
the current dependency build, TLS, rotation metadata, and playback checks.
