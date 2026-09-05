# Runtime decoder switching

`DecoderManager` selects video and audio decoders independently on the same `ExoPlayer`.

```kotlin
val decoderManager = DecoderManager()
val renderersFactory = NextRenderersFactory(context).setDecoderManager(decoderManager)
val player = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
decoderManager.attach(player) // Before preparing media; requires DefaultTrackSelector.

decoderManager.selectVideoDecoder(DecoderMode.FFMPEG)
decoderManager.selectAudioDecoder(DecoderMode.AUTO)

// Before releasing the player:
decoderManager.detach()
player.release()
```

| Mode | Eligible decoders |
| --- | --- |
| `AUTO` (default) | Hardware MediaCodec, software MediaCodec, unknown MediaCodec, then bundled FFmpeg |
| `HARDWARE` | Only MediaCodec decoders identified as hardware accelerated |
| `SOFTWARE` | Only MediaCodec decoders identified as software-only |
| `FFMPEG` | Only nextlib's FFmpeg renderer |

Pass `initialVideoMode` and `initialAudioMode` to the manager to change the initial choices.
`videoMode` and `audioMode` report requested modes. `activeVideoMode` and `activeAudioMode`
report initialized decoder categories, or `null` while unknown or disabled. During a switch,
the previous active category remains until the renderer is disabled or a new decoder initializes.
Unknown MediaCodec categories remain `null`; automatic selection is reported as the actual
category once identified. Audio passthrough does not initialize a decoder.

Use one manager/factory per player and call manager methods on the player's application thread.
Installing a manager enables extension renderers in normal priority order and MediaCodec
initialization fallback. Keep extensions enabled; `PREFER` changes automatic renderer priority.
Custom MediaCodec selectors are retained and their results are filtered by the selected mode.

Switching retains the player, playlist, position, and `playWhenReady`. Changes among `AUTO`,
`HARDWARE`, and `SOFTWARE` stop and prepare the player to release existing codecs; a stopped
or unprepared player stays idle. Switching to/from `FFMPEG` remaps tracks without stopping.
Choices persist across media items. Renderer capability gating is needed because Media3
[maps tracks before selecting them](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/MappingTrackSelector.java).

Applications own runtime error recovery, retry limits, and UI. `AUTO` permits FFmpeg when
MediaCodec cannot support a format; it does not recover from runtime decoder failures.
To retry after an error, select a fallback mode and call `player.prepare()`.

## Migration from the initial PR API

- Replace `null` selections with `DecoderMode.AUTO` and `APP_SOFTWARE` with `FFMPEG`.
- Replace `attach(player, trackSelector)` with `attach(player)`.
- Use `activeVideoMode` / `activeAudioMode` for the former initialized-mode properties;
  `videoMode` / `audioMode` now consistently report the requested selection.
