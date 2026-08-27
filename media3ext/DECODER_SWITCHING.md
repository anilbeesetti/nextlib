# Runtime Decoder Switching

`DecoderManager` lets an application change video and audio decoder categories without replacing
its `ExoPlayer`. `NextRenderersFactory` creates every required MediaCodec and FFmpeg renderer once,
and the manager controls the renderer set after the application attaches them.

## Decoder modes

The same `DecoderMode` values are available for video and audio. Each track type is selected
independently.

| Mode | Behavior |
| --- | --- |
| `null` | Automatically prefers hardware MediaCodec, then software MediaCodec, then nextlib FFmpeg |
| `HARDWARE` | Uses only MediaCodec decoders that Media3 identifies as hardware accelerated |
| `SOFTWARE` | Uses only MediaCodec decoders that Media3 identifies as software-only |
| `APP_SOFTWARE` | Uses only the FFmpeg renderer bundled with nextlib |

`SOFTWARE` and `APP_SOFTWARE` are different decoder paths. `SOFTWARE` uses Android's MediaCodec
software decoders; `APP_SOFTWARE` uses nextlib's FFmpeg implementation.

In automatic mode, the system renderer is evaluated before the FFmpeg renderer. MediaCodec
candidates are ordered as known hardware, known software-only, then codecs whose acceleration
category is unknown.

## Setup

Create the manager and pass it to the factory before building the player. Attach the player and
track selector to the manager before preparing media:

```kotlin
val trackSelector = DefaultTrackSelector(applicationContext)
val decoderManager = DecoderManager()
val renderersFactory = NextRenderersFactory(applicationContext)
    .setDecoderManager(decoderManager)

val player = ExoPlayer.Builder(applicationContext)
    .setRenderersFactory(renderersFactory)
    .setTrackSelector(trackSelector)
    .build()

decoderManager.attach(player, trackSelector)
```

A factory instance creates one renderer set and must not be shared by multiple players. Call manager
methods on the player's application thread. Detach the manager before releasing the player:

```kotlin
decoderManager.detach()
player.release()
```

## Selecting decoders

Video and audio changes do not affect each other:

```kotlin
decoderManager.selectVideoDecoder(DecoderMode.APP_SOFTWARE)
decoderManager.selectAudioDecoder(DecoderMode.SOFTWARE)
```

Applications that want the same category for both tracks should call both methods. Mixed choices do
not require a special fallback setting; for example, FFmpeg video with automatic audio is:

```kotlin
decoderManager.selectVideoDecoder(DecoderMode.APP_SOFTWARE)
decoderManager.selectAudioDecoder(null)
```

`decoderManager.videoMode` and `decoderManager.audioMode` report the decoder modes currently
initialized by the player. They are `null` before initialization and while switching. The manager
updates them from Media3 analytics callbacks, including when automatic selection is enabled.
Selecting before `attach` fails with a clear lifecycle error.

## Switching mechanics

Mode changes preserve the player instance, playlist, current position, and `playWhenReady` value.

Changing among automatic, `HARDWARE`, and `SOFTWARE` can change which MediaCodec instances are
eligible. The manager stops and prepares the same player for these transitions so an active codec
from the previous category is released. Changes between MediaCodec and `APP_SOFTWARE` normally
remap the affected track to a different renderer without stopping the player.

The selected modes remain active until the application changes them. Nextlib does not reset decoder
modes when the media item changes.

## Error handling

`DecoderManager` does not listen for decoder errors, inspect unsupported tracks, choose fallback
modes, or expose recovery state. The application owns those decisions and can call
`selectVideoDecoder` or `selectAudioDecoder` when it wants to retry another mode.

Automatic mode allows Media3 to select FFmpeg when the MediaCodec renderer does not support a
format, and MediaCodec can try its eligible decoder candidates. Nextlib does not automatically
recover from a runtime decoder failure after playback has started.

When retrying after a player error, the application should select the fallback mode and prepare the
player. It remains responsible for dialogs, retry limits, analytics, and terminal error handling.
