# NextLib

[![Build nextlib](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml/badge.svg)](https://github.com/anilbeesetti/nextlib/actions/workflows/build.yaml) [![](https://jitpack.io/v/anilbeesetti/nextlib.svg)](https://jitpack.io/#anilbeesetti/nextlib)

NextLib is a library for adding ffmpeg codecs to [Media3](https://github.com/androidx/media).

## Currently supported decoders
- **Audio**: Vorbis, Opus, Flac, Alac, pcm_mulaw, pcm_alaw, MP3, Amrnb, Amrwb, AAC, AC3, EAC3, dca, mlp, truehd
- **Video**: H.264, HEVC, VP8, VP9

## Setup

NextLib is available at JitPack's Maven repo.

First, you have to add the jitpack's maven repo to your build.gradle

Kotlin DSL:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

Groovy DSL:

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}
```

Now, you have to add the dependency to nextlib in your build.gradle

Kotlin DSL:

```kotlin
dependencies {
    implementation("com.github.anilbeesetti.nextlib:nextlib-media3ext:INSERT_VERSION_HERE") // To add media3 software decoders and extensions
    implementation("com.github.anilbeesetti.nextlib:nextlib-mediainfo:INSERT_VERSION_HERE") // To get media info through ffmpeg
}
```

Groovy DSL:

```gradle
dependencies {
    implementation "com.github.anilbeesetti.nextlib:nextlib-media3ext:INSERT_VERSION_HERE" // To add media3 software decoders and extensions
    implementation "com.github.anilbeesetti.nextlib:nextlib-mediainfo:INSERT_VERSION_HERE" // To get media info through ffmpeg
}
```

## Usage

To use Ffmpeg decoders in your app, Add `NextRenderersFactory` (is one to one compatible with DefaultRenderersFactory) to `ExoPlayer`
```kotlin
val renderersFactory = NextRenderersFactory(applicationContext) 

ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .build()
```
