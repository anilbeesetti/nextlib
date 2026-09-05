# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![Maven Central](https://img.shields.io/maven-central/v/io.github.anilbeesetti/nextlib-media3ext.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8, VP9

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
