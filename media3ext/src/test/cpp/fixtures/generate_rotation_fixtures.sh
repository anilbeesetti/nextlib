#!/usr/bin/env bash
# Generate identical coded frames; only the MP4 clockwise display rotation differs.
set -euo pipefail
out=${1:?Pass an output directory}
mkdir -p "$out"
# A tiny bitmap font keeps fixture generation independent of FFmpeg drawtext/fonts.
python3 - "$out/frame.ppm" <<'PYFRAME'
import sys
width, height = 640, 360
colors = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
glyphs = ['01110100011000111111100011000110001',
          '11110100011000111110100011000111110',
          '01111100001000010000100001000001111',
          '11110100011000110001100011000111110']
pixels = bytearray()
for y in range(height):
    for x in range(width):
        quadrant = (y // 180) * 2 + x // 320
        gx, gy = (x % 320 - 120) // 16, (y % 180 - 34) // 16
        label = 0 <= gx < 5 and 0 <= gy < 7 and glyphs[quadrant][gy * 5 + gx] == '1'
        pixels.extend(((255, 255, 255) if quadrant in (0, 2) else (0, 0, 0))
                      if label else colors[quadrant])
with open(sys.argv[1], 'wb') as image:
    image.write(b'P6\n640 360\n255\n' + pixels)
PYFRAME
ffmpeg -v error -y -loop 1 -framerate 24 -i "$out/frame.ppm" -t 300 \
    -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 24 -bf 0 -an "$out/rotation-0.mp4"
for degrees in 90 180 270; do
    # FFmpeg's display-matrix angle is counterclockwise; Media3 Format is clockwise.
    ffmpeg -v error -y -display_rotation "-$degrees" -i "$out/rotation-0.mp4" -c copy \
        "$out/rotation-$degrees.mp4"
done
for degrees in 0 90 180 270; do
    ffprobe -v error -show_streams -show_format -of json "$out/rotation-$degrees.mp4" > "$out/rotation-$degrees.json"
done
