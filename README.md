# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![Maven Central](https://img.shields.io/maven-central/v/io.github.anilbeesetti/nextlib-media3ext.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8, VP9 (using FFmpeg’s built-in decoders)

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

Use `NextRenderersFactory` as a drop-in `DefaultRenderersFactory` replacement to make the bundled
FFmpeg decoders available to Media3:

```kotlin
val renderersFactory = NextRenderersFactory(applicationContext)

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```

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

Video and audio are selected independently. See
[`media3ext/DECODER_SWITCHING.md`](media3ext/DECODER_SWITCHING.md) for mode behavior, lifecycle, and
application-owned error handling.

## Building from source

Use macOS or Linux (WSL on Windows), JDK 17+, `make`, `curl`, `tar`, and
`pkg-config`. On Apple Silicon, also install native `yasm` (`brew install yasm`)
for the x86 ABIs; the pinned NDK bundles an Intel-only assembler. Install [Android CLI](https://developer.android.com/tools/agents/android-cli/download)
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

Gradle tracks the setup script, tool versions, and generated output so unchanged
builds skip FFmpeg. To force rebuilding it:

```sh
./gradlew :ffmpegSetup --rerun-tasks
```

For standalone use, export `ANDROID_HOME` and run `bash ffmpeg/setup.sh` (always
rebuilds). Run `python3 ffmpeg/test_setup.py` for setup regression checks without
SDK downloads or native compilation.
