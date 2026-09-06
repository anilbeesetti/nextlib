`vp9.ivf` is a synthetic 24-frame, 64×48 VP9 clip for packet retry and flush regression tests.
Generate it with:

```sh
ffmpeg -f lavfi -i "testsrc2=size=64x48:rate=24:duration=1" -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -g 12 -an vp9.ivf
```
