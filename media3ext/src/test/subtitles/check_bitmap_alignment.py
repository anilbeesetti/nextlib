#!/usr/bin/env python3
"""Check alignment.mkv at 1–7 seconds: the ASS rectangle must match its cyan video outline."""
import pathlib
import struct
import subprocess
import sys

png = pathlib.Path(sys.argv[1])
width, height = struct.unpack('>II', png.read_bytes()[16:24])
pixels = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(png),
                                  '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-'])
red, cyan = [], []
for i in range(0, len(pixels), 3):
    r, g, b = pixels[i:i + 3]
    point = (i // 3 % width, i // 3 // width)
    if r > 80 and r > 2 * max(g, b):
        red.append(point)
    if min(g, b) > 80 and min(g, b) > 2 * r:
        cyan.append(point)

assert red and cyan, 'Both the red ASS rectangle and cyan video outline must be visible'
for axis in (0, 1):
    low, high = min(p[axis] for p in red), max(p[axis] for p in red)
    target_low, target_high = min(p[axis] for p in cyan), max(p[axis] for p in cyan)
    assert abs((low + high - target_low - target_high) / 2) <= 3, f'Axis {axis}: subtitle is displaced from video'
    assert .8 < (high - low) / (target_high - target_low) < 1.05, f'Axis {axis}: subtitle scale differs from video'
print(f'PASS: bitmap matches video outline on {width} × {height} screenshot')
