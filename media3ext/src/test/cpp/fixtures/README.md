`vp9.ivf` is a synthetic 24-frame, 64×48 VP9 clip for packet retry and flush regression tests.
Generate it with:

```sh
ffmpeg -f lavfi -i "testsrc2=size=64x48:rate=24:duration=1" -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -g 12 -an vp9.ivf
```

`vp9-altref.ivf` contains 72 display frames at 128×96, including six alt-ref
superframes. With frame threading and queued output, it exercises a receive
returning EAGAIN immediately after a send returned EAGAIN. Generate it in two
passes (libvpx enables alternate reference frames only in two-pass mode):

```sh
ffmpeg -f lavfi -i "testsrc2=size=128x96:rate=24:duration=3" -c:v libvpx-vp9 -deadline good -cpu-used 4 -lag-in-frames 25 -auto-alt-ref 1 -g 48 -b:v 150k -pass 1 -passlogfile /tmp/nextlib-altref -an -f null /dev/null
ffmpeg -f lavfi -i "testsrc2=size=128x96:rate=24:duration=3" -c:v libvpx-vp9 -deadline good -cpu-used 4 -lag-in-frames 25 -auto-alt-ref 1 -g 48 -b:v 150k -pass 2 -passlogfile /tmp/nextlib-altref -an vp9-altref.ivf
```
# AV1 fixtures

`av1-8bit.ivf` and `av1-10bit.ivf` contain 24 synthetic 64×64 frames at 24 fps,
including film grain. Generated with FFmpeg 7.1.5 / SVT-AV1 2.3.0:

```sh
for depth in 8 10; do
  pix=yuv420p
  [ "$depth" = 8 ] || pix=yuv420p10le
  ffmpeg -f lavfi -i 'testsrc2=size=64x64:rate=24' -frames:v 24 \
    -c:v libsvtav1 -preset 10 -crf 36 -pix_fmt "$pix" \
    -svtav1-params 'lp=2:film-grain=8' "av1-${depth}bit.ivf"
done
```

These exercise delayed output, timestamps, EOS, seeking and native frame ownership
in `FfmpegAv1DecoderTest`. No third-party media is included.

`dolby-vision-profile7.mkv` uses one second of FFmpeg `testsrc2` at 96×64 and 10 fps,
encoded with libx265 Main 10, limited-range BT.2020 non-constant luminance, and PQ.
The Matroska track carries synthetic Dolby Vision configuration version 1.0,
profile 7, level 6, and compatibility ID 6. It contains only the generated HEVC
base-layer pixels. The muxer was given mastering primaries R(35400,14600),
G(8500,39850), B(6550,2300), white(15635,16450) in units of 1/50000, mastering
luminance 0.005–1000 cd/m², MaxCLL 1000 and MaxFALL 400.

`truehd-laced.mka` contains 300 ms of a generated 440 Hz sine wave encoded as
48 kHz TrueHD. It was muxed into three Matroska SimpleBlocks with Xiph lacing
of 120 access units each, anchored at 0, 100, and 200 ms. There are no per-frame
timestamps within each lace.

`mpeg2-top.m2v`, `mpeg2-bottom.m2v`, and `mpeg2-progressive.m2v` are single-frame
synthetic packets for the YUV field-order test. Low-delay encoding lets the
decoder return each frame before end of stream.

```sh
for field in top bottom; do
  ffmpeg -f lavfi -i 'testsrc2=size=32x32:rate=50' -vf "tinterlace=interleave_$field" \
    -frames:v 1 -c:v mpeg2video -g 1 -flags +ildct+ilme+low_delay -f mpeg2video "mpeg2-$field.m2v"
done
ffmpeg -f lavfi -i 'testsrc2=size=32x32:rate=25' -frames:v 1 -c:v mpeg2video \
  -g 1 -flags +low_delay -f mpeg2video mpeg2-progressive.m2v
```
