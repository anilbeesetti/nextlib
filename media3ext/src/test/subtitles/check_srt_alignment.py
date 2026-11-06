#!/usr/bin/env python3
"""Check basic.srt.mkv's first cue in a landscape NextPlayer screenshot (controls visible)."""
import pathlib
import struct
import subprocess
import sys

png = pathlib.Path(sys.argv[1])
width, height = struct.unpack('>II', png.read_bytes()[16:24])
# The first subtitle line is below the seek bar and above the bottom controls.
top, bottom = int(height * .84), int(height * .865)
pixels = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(png), '-vf',
                                  f'crop={width}:{bottom - top}:0:{top}',
                                  '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-'])
xs = [(i // 3) % width for i in range(0, len(pixels), 3)
      if min(pixels[i:i + 3]) > 150]
assert xs, 'No subtitle text in the fixture region'
center = (min(xs) + max(xs)) / 2
assert abs(center - width / 2) < width * .025, f'SRT center {center:.1f}px; viewport center {width / 2:.1f}px'
print(f'PASS: SRT center {center:.1f}px; viewport center {width / 2:.1f}px')
