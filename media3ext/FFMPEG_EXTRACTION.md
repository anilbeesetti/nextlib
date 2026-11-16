# FFmpeg progressive extraction

`FfmpegMediaSourceFactory(dataSources).createMediaSource(item)` probes containers with
libavformat and feeds compressed samples into Media3. Keep using `NextRenderersFactory`
and `DecoderManager`; extraction does not select the renderer. `DecoderMode.SYSTEM`
allows both hardware and software Android MediaCodec implementations.

The first target is surveillance recordings with an IMKH prefix and H.264 in MPEG-PS,
which Media3's standard extractors reject. Other enabled self-contained containers are
MP4/MOV, Matroska/WebM, MPEG-TS, AVI, ASF, FLV, Ogg, WAV, MP3, AAC, FLAC, H.264 and HEVC.
Unsupported audio/video codecs fail explicitly instead of silently dropping tracks.
DRM, adaptive manifests and HDR belong on the application's standard Media3 path.
HDR is rejected until its static/dynamic metadata can be preserved and tested.

## Loading contract

The factory pairs each period's reader with its extractor using Media3 1.11.1's
DataSource-before-extractor creation order. Missing or unconsumed pairings fail fast.
FFmpeg reads through the loader's supplied DataReader; synchronous native seeks reopen
that same DataSource, retaining the original DataSpec's headers and range end. No second
reader or native networking is used. Seeking is time-based inside libavformat; the
Media3 seek map requests a byte-zero restart, then FFmpeg finds the relevant keyframe.
The physical position includes AVIO prefetch. Read/probe failures are fatal parser errors:
partial native packet state is not reused by automatic I/O retries. Interrupted reads
preserve their Java exception and cancellation is checked at read/seek boundaries.

Codec initialization and length-prefixed H.264/HEVC packets are normalized for both
MediaCodec and NextLib's software renderers. SDR color information, rotation, pixel
aspect ratio, track language and default/forced flags are retained. ASS/font attachments,
bitmap subtitles, SubRip packets and external subtitle sources reuse the existing text
renderers. AVIO buffers, packets and native handles are owned by the media period and
released with its extractor.

## Verification, 2026-11-16

Android 37 / API 37 ARM64 emulator (16 KB pages): original 307 MB recording renders
through `c2.goldfish.h264.decoder`, seeks forward/backward and renders again after each
system -> FFmpeg -> system switch. Next Player's MediaSession integration also passes
automatic source fallback, paused seeking and continued SW playback. Synthetic Opus,
Vorbis and FLAC tracks produce audio output. SourceReaderTest verifies preserved request
headers, bounded seeks, EOF and out-of-range rejection; JVM tests cover NAL framing and
Vorbis private data. Native execution was not tested on a physical device; emulator
codec classification does not establish physical hardware acceleration.
