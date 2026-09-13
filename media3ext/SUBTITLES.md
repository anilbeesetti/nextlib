# Native subtitles

`FfmpegTextRenderer` renders ASS/SSA with libass and PGS, VobSub and DVB with
FFmpeg. It produces positioned bitmap cues for Media3's existing `SubtitleView`.
SRT, WebVTT, TTML and MP4 timed text retain Media3's extraction-time parsing.

## Research and choice

Replacing every subtitle parser with FFmpeg would lose functionality. FFmpeg's
[WebVTT decoder](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/webvttdec.c)
still has unfinished cue-setting and markup handling. Its ASS decoder outputs
ASS text; [libass](https://github.com/libass/libass) supplies the actual styling,
shaping, drawings, positioning, animation and karaoke renderer.

Media3's [Canvas subtitle output](https://github.com/androidx/media/blob/release/libraries/ui/src/main/java/androidx/media3/ui/CanvasSubtitleOutput.java)
repositions vertical cues horizontally. Its existing
[`SubtitleView.VIEW_TYPE_WEB`](https://developer.android.com/reference/androidx/media3/ui/SubtitleView)
supports vertical layout and ruby while retaining canvas output for bitmap cues.
The NextPlayer patch uses WebView for vertical cues, ruby and text emphasis, and
canvas for ordinary subtitles. WebView treats an unset position anchor as a start
anchor, moving plain SRT to the right; canvas preserves default centering and the
user's typeface.

`FfmpegSubtitleExtractorsFactory` extends Media3's existing Matroska parsing with
[font attachment elements](https://www.matroska.org/technical/elements.html#Attachments).
It passes TTF, OTF and TTC data through format metadata to
[`ass_add_font`](https://github.com/libass/libass/blob/0.17.3/libass/ass.h), scoped to
one decoder. Fonts are deliberately excluded from codec initialization bytes:
MediaSession serializes those bytes in track bundles, which would exceed Binder's
transaction limit with ordinary font collections. ASS `[Fonts]` sections are also
enabled through `ass_set_extract_fonts`.

Media3 now normally parses subtitles during extraction. The source helper disables
that conversion only for formats handled by the native renderer. Globally enabling
the deprecated legacy path caused paused-seek regressions for ordinary text formats
in testing, so it is deliberately avoided. See
[DefaultMediaSourceFactory](https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/DefaultMediaSourceFactory.java).

The build reuses the [libass Android prefab](https://github.com/peerless2012/libass-android)
`io.github.peerless2012:ass:0.5.1` (libass 1.17.3), instead of introducing another
cross-compilation toolchain. Its AAR supplies libass and the shared C++ runtime;
nextlib excludes duplicate copies from its own AAR. FFmpeg's existing build enables
`pgssub`, `dvdsub` and `dvbsub` for all four ABIs.

## Integration

Configure the source and renderers together. Passing the same data source factory
preserves URI handling, headers and caches for external ASS files.

```kotlin
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.setSubtitleViewportSize
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.withFfmpegSubtitles

val dataSources = DefaultDataSource.Factory(context)
val sources = DefaultMediaSourceFactory(context, FfmpegSubtitleExtractorsFactory())
    .setDataSourceFactory(dataSources)
    .withFfmpegSubtitles(dataSources)
val player = ExoPlayer.Builder(context)
    .setRenderersFactory(NextRenderersFactory(context))
    .setMediaSourceFactory(sources)
    .build()
// Call from the ExoPlayer application thread when the viewport changes.
player.setSubtitleViewportSize(width, height)
```

Keep canvas output for ordinary cues. See the NextPlayer patch for selecting
WebView only when a cue needs vertical layout, ruby or text emphasis.
Lay out bitmap cues over the video surface using the same content scale, zoom
and pan. A fullscreen overlay stretches authored coordinates when the video is
letterboxed. The NextPlayer patch shares its video modifiers with bitmap output;
ordinary text keeps the existing fullscreen subtitle layout.

For external ASS/SSA, supply a normal `MediaItem.SubtitleConfiguration` with
`MimeTypes.TEXT_SSA`. The helper merges a seekable raw-script source with the main
media, preserves clipping and the original public media item, and reports the
script's event duration so re-selecting it can reload the sample. Media3's SSA
parser is reused only to read that duration; libass receives the original bytes.
Metadata-only item updates refresh the public timeline without rebuilding periods;
this prevents a reload loop with NextPlayer's track/artwork metadata updates.
If overriding load-error policy, set it on the returned factory so sidecars also
receive the policy.

`NextRenderersFactory` registers both subtitle renderers. Existing subtitle delay
and speed extensions update both, applying timing adjustments to media time rather
than Media3's large internal stream offset. Native state resets on seeks, stream
changes and track disabling. Output callbacks discard obsolete queued frames.
Rendering is bounded to 1920 × 1080; the default canvas is 1280 × 720.

## Reproduce

Boot a disposable emulator, then run from the repository root:

```sh
ANDROID_HOME=/path/to/sdk ANDROID_SERIAL=emulator-5582 \
  bash media3ext/src/test/subtitles/run_subtitle_test.sh
```

The runner generates short synthetic media using Python 3 and host FFmpeg,
builds the test APK, installs it on the explicit emulator and checks the
instrumentation result. Generated movies are ignored by Git. The small text
scripts and PGS packets are included; no downloaded or copyrighted media is needed.

The [NextPlayer patch](src/test/subtitles/nextplayer.patch) was tested against
`ef203f8361442788010c184384a1da7d63212add` using Media3 1.11.1. Apply it in an isolated
NextPlayer checkout, then build against this nextlib checkout:

```sh
git apply /path/to/nextlib/media3ext/src/test/subtitles/nextplayer.patch
./gradlew -PnextlibPath=/path/to/nextlib :app:assembleDebug
adb -s emulator-5582 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s emulator-5582 push /path/to/nextlib/media3ext/src/androidTest/assets/subtitles /sdcard/Movies/SubtitleQA
```

Open the generated media in NextPlayer. Use **Open local subtitle** for sidecars
when scoped storage prevents automatic sibling-file discovery. The real ExoPlayer
lives in NextPlayer's service, so the viewport update is sent there.

## Verification — 2026-09-12

Disposable ARM64 Android 17 emulator, API 37 (37.1 system image), 16 KiB pages,
720 × 1600 display, SwiftShader. Library builds use Media3 1.11.0. Other ABIs
were compiled and packaged, but were not executed on devices.

| Format | Tested transport | Rendering path | NextPlayer screenshot |
| --- | --- | --- | --- |
| ASS | Matroska and external `.ass` | libass bitmap | [Drawing and positioned text](src/test/subtitles/verification/ass-sidecar.png) |
| SSA | Matroska and external `.ssa` | libass bitmap | [Yellow style](src/test/subtitles/verification/ssa.png) |
| SRT | Matroska and external `.srt` | Media3 text | [Styles and multilingual text](src/test/subtitles/verification/srt.png) |
| WebVTT | External `.vtt` | Media3 text | [Vertical text and ruby](src/test/subtitles/verification/webvtt.png) |
| TTML | External `.ttml` | Media3 text | [Color and italics](src/test/subtitles/verification/ttml.png) |
| tx3g | MP4 | Media3 text | [Styles and multilingual text](src/test/subtitles/verification/tx3g.png) |
| PGS | Matroska | FFmpeg bitmap | [Synthetic bitmap](src/test/subtitles/verification/pgs.png) |
| VobSub | Matroska | FFmpeg bitmap | [Synthetic bitmap](src/test/subtitles/verification/vobsub.png) |
| DVB | MPEG-TS | FFmpeg bitmap | [Synthetic bitmap](src/test/subtitles/verification/dvb.png) |

The 12-case playback matrix checks renderer selection, cue display, clearing in
gaps, paused backward seeking, disabling and re-enabling subtitles. It explicitly
retains a 30-second back buffer to keep sparse subtitle packets available. Native
tests additionally check ASS drawings, movement, karaoke, overlap, UTF-16 input,
resize, large stream offsets, delay/speed, re-enabling without a format event,
malformed packets, compressed PGS and palette/geometry/clear behavior. The clipped
playback test checks that a longer ASS script cannot prevent video ending, even
with a subtitle delay and speed multiplier. Metadata replacement preserves both
the public sidecar configuration and the active cues.

NextPlayer's embedded ASS/SSA, SRT, PGS, VobSub, DVB and tx3g tracks were visually
inspected, as were external ASS, WebVTT and TTML. Sidecar tests used fresh
video/sidecar pairs copied into the disposable debug app's internal files with
`adb run-as`; tracks without a default flag were selected through the player UI.
Bitmap fixtures contain a colored rectangle to make palette and placement errors
obvious. These are synthetic decoder checks, not a corpus of commercial subtitles.

Validated build/check results:

- FFmpeg setup regression checks; all four native ABIs rebuilt.
- Library debug and release AARs; 11 existing JVM tests.
- [16 device tests](src/test/subtitles/verification/instrumentation.txt): nine
  subtitle tests (including the 12-case playback matrix) and seven existing native
  video tests; zero failures, 42.218 seconds.
- NextPlayer debug APKs; 205 JVM tests, with `ignoreFailures` overridden to false;
  `ktlintCheck` passed.

### Main merge and separate test app (2026-09-13)

Merged main at `2dc77cd` (Media3 1.11.1). Built NextPlayer `70f77531`
(0.18.0, version code 74) in an isolated copy with `nextplayer.patch`, then
[`nextplayer-subs.patch`](src/test/subtitles/nextplayer-subs.patch). The debug app
is named **Next player Subs**, package `dev.anilbeesetti.nextplayer.subs`, so it
can be installed alongside the existing app.

All 11 library JVM tests and [17 device tests](src/test/subtitles/verification/main-merge-instrumentation.txt)
passed on an Android 16 ARM64 emulator, including main's decoder lifecycle test.
NextPlayer's player formatting check and debug APK build passed. The ARM64 APK
was installed and launched successfully on the connected CPH2689 phone; subtitle
playback on that phone has not yet been checked.

### Font and alignment fixes (2026-09-13)

The separate test app now centers ordinary SRT cues on canvas. The screenshot
check fails on the previous APK (854.5 px center on a 1280 px viewport) and passes
on the corrected APK (639 px, expected 640 px):

```sh
python3 media3ext/src/test/subtitles/check_srt_alignment.py media3ext/src/test/subtitles/verification/srt-centered.png
```

An original 860-byte geometric font tests that ASS `[Fonts]` data and MKV attachments
produce the same bitmap as explicitly supplying that font. The playback matrix
also checks that the font survives seeking and track re-selection. The font
generator requires `fonttools` only when regenerating the checked-in font assets;
the normal fixture generator and emulator runner still need only Python and FFmpeg.

The supplied Death Note episode contains nine font attachments (1,565,484 bytes),
including Cabin, Dominican and DEATH FONT. Its attachments precede the first media
cluster. The corrected app was checked with this exact file on an Android 16 ARM64
disposable emulator; the opening subtitles displayed the authored Dominican face.
The user's episode and extracted fonts are not included in the repository.

Verification: all 18 device tests passed before the final test additions; the
expanded [11 subtitle tests](src/test/subtitles/verification/font-instrumentation.txt)
then passed, including the 13-case playback matrix. A separate
[exact-file check](src/test/subtitles/verification/sample-font-instrumentation.txt)
matched all nine extracted font payloads against FFmpeg's SHA-256 hashes and
verified that font data stays out of MediaSession track bundles. The 11 library
JVM tests, NextPlayer debug build and player formatting check also passed.

Screenshots: [SRT before](src/test/subtitles/verification/srt-offset.png),
[SRT centered](src/test/subtitles/verification/srt-centered.png),
[font fallback](src/test/subtitles/verification/font-fallback.png),
[attached font](src/test/subtitles/verification/font-attached.png).
[Vertical WebVTT and ruby](src/test/subtitles/verification/webvtt-canvas-switch.png)
were also checked after switching from canvas output; the fixture cue was extended
to the full clip for this visual check.

### ASS placement on wide displays (2026-09-13)

Reproduced the supplied episode's misplaced “Unemployed” label at 06:41.73 on a
1600 × 720 Android 16 (API 36) ARM64 disposable emulator. The video occupied a
centered 1280 × 720 area, while bitmap subtitles used all 1600 pixels of screen
width. Sharing the video's layout and transform places the label beside the
Japanese lettering and also corrects the name and incident labels.

The generated `alignment.mkv` has a cyan outline in its video around the ASS red
rectangle's authored position. Pause between 1 and 7 seconds with both rectangles
fully visible, capture the screen, then run:

```sh
python3 media3ext/src/test/subtitles/generate_fixtures.py /tmp/subtitle-fixtures
python3 media3ext/src/test/subtitles/check_bitmap_alignment.py /path/to/screenshot.png
```

The check fails on the [previous APK](src/test/subtitles/verification/bitmap-offset.png)
and passes with the fix in [fit mode](src/test/subtitles/verification/bitmap-aligned.png),
stretch, crop and original-size modes. SRT remains centered at 799 px on a 1600 px
viewport. All 18 player unit tests, player `ktlintCheck` and the debug APK build
passed. Pinch zoom and pan share the same modifiers but were not exercised in
this run. The user's episode and screenshots remain outside the repository.

## Limits

- Media3 drops FFmpeg-muxed Matroska `D_WEBVTT/SUBTITLES` tracks before renderer
  selection. The generated `styled.vtt.mkv` reproduces this and is not counted as
  passing. Use an external WebVTT file or a supported container/codec mapping.
- Media3 can satisfy an A/V backward seek from its buffer without recovering
  discarded sparse subtitle packets. Native renderer resets cannot recover packets
  the extractor does not resend. The matrix's retained back buffer makes its scope
  explicit; NextPlayer's buffering policy is not changed by the integration patch.
  NextPlayer also missed the current sparse DVB cue when its track was first
  selected after playback advanced; reopening with the track selected from the
  start rendered it correctly.
- Android system fonts provide fallback when a named font is absent. Use
  `FfmpegSubtitleExtractorsFactory` for Matroska font attachments; a custom
  `FfmpegTextRenderer` constructor also accepts an application-supplied fonts
  directory. Extraction accepts at most 64 fonts, 16 MiB per font and 32 MiB total.
  Fonts are loaded as their attachment elements are encountered; seeking ahead to
  attachments placed after media clusters is not implemented. A standalone ASS
  file must embed its fonts or have them supplied by the application.
- ASS bitmap styling is authored by the script; `SubtitleView` text font/size and
  embedded-style toggles cannot restyle rasterized ASS. Delay and speed still work.
  Applications must match the bitmap overlay to their video layout, as shown in
  the NextPlayer patch.
- Raw external ASS uses a single-period merged source. Multi-period manifests,
  server-side ads, DRM subtitles, live ASS pruning and standalone `.sup`/`.idx`
  extraction are outside the verified scope. ASS scripts and samples are limited
  to 16 MiB, and bitmap canvas dimensions to 4096.
- Emulator startup produced an input-focus ANR during MediaSession notification
  initialization. The captured main-thread stack did not contain subtitle decode
  code. Subsequent visual checks were repeated; this run is not a performance or
  physical-device certification.
- Two separate NextPlayer storage issues appeared during setup: a picked document
  URI lost read access, and `MediumStateEntity.toVideoState()` uses `uriString` as
  `path`, preventing later sibling discovery for cached `file://` items. These app
  issues are not fixed by a renderer. Fresh internal fixture pairs avoided those
  paths for the visual checks; document-picker persistence remains unverified.
