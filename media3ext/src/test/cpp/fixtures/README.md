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
