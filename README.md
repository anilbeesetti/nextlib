# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![](https://jitpack.io/v/anilbeesetti/nextlib.svg)](https://jitpack.io/#anilbeesetti/nextlib)

NextLib is a library for adding ffmpeg codecs to exoplayer.

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8, VP9

## Usage

NextLib is available at JitPack's Maven repo.

If you're using Gradle, you could add NextLib as a dependency with the following steps:

1. Add `maven { url 'https://jitpack.io' }` to the `repositories` in your `build.gradle`.
2. Add `implementation 'com.github.anilbeesetti:nextlib:INSERT_VERSION_HERE'` to the `dependencies` in your `build.gradle`. Replace `INSERT_VERSION_HERE` with the [latest release](https://github.com/anilbeesetti/nextlib/releases/latest).

To use Ffmpeg decoders in your app, Add `NextRenderersFactory` to `ExoPlayer`
```kotlin
val renderersFactory = NextRenderersFactory(applicationContext)
    .setUseExperimentalRenderers(true) // At this point in time, video decoders are experimental so you need enable experimental ffmpeg video renderers

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```
