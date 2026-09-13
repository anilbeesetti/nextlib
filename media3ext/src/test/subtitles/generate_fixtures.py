#!/usr/bin/env python3
"""Generate synthetic subtitle fixtures and short NextPlayer clips; requires host ffmpeg."""
import pathlib
import struct
import subprocess
import sys

out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).parents[2] / 'androidTest/assets/subtitles'
out.mkdir(parents=True, exist_ok=True)

ass = r'''[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Roboto,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,30,30,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:07.00,Default,,0,0,0,,{\an7\pos(100,100)\1c&H00FF00&}ASS positioned green
Dialogue: 1,0:00:01.00,0:00:07.00,Default,,0,0,0,,{\an7\pos(100,200)\1c&H0000FF&\bord0\shad0\p1}m 0 0 l 160 0 160 40 0 40
Dialogue: 0,0:00:01.00,0:00:07.00,Default,,0,0,0,,{\an2\move(250,500,1000,500)}ASS moving text
Dialogue: 0,0:00:08.00,0:00:14.00,Default,,0,0,0,,{\k100}Ka{\k100}ra{\k100}o{\k100}ke\NEnglish — हिन्दी — العربية — 日本語
Dialogue: 0,0:00:16.00,0:00:21.00,Default,,0,0,0,,{\fad(1000,1000)\i1}ASS fade and italic
'''
(out / 'styled.ass').write_text(ass)
(out / 'legacy.ssa').write_text('''[Script Info]
ScriptType: v4.00
PlayResX: 1280
PlayResY: 720
[V4 Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, AlphaLevel, Encoding
Style: Default,Roboto,48,65535,255,0,0,0,0,1,2,1,2,30,30,40,0,1
[Events]
Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: Marked=0,0:00:01.00,0:00:07.00,Default,,0,0,0,,SSA yellow subtitle
Dialogue: Marked=0,0:00:08.00,0:00:14.00,Default,,0,0,0,,SSA second cue
''')
(out / 'basic.srt').write_text('''1
00:00:01,000 --> 00:00:07,000
SRT <b>bold</b> and <i>italic</i>
English — हिन्दी — العربية — 日本語

2
00:00:08,000 --> 00:00:14,000
SRT second cue

3
00:00:16,000 --> 00:00:21,000
SRT final cue
''')
(out / 'styled.vtt').write_text('''WEBVTT

STYLE
::cue(.green) { color: lime; font-style: italic; }

first
00:01.000 --> 00:07.000 line:20% position:10% align:start size:80%
<c.green>WebVTT green positioned</c>
<b>bold</b> &amp; <u>underlined</u>

00:02.000 --> 00:06.000 line:70% position:50% align:center
WebVTT overlapping cue

00:08.000 --> 00:14.000
WebVTT English — हिन्दी — العربية — 日本語

00:16.000 --> 00:21.000 vertical:rl line:20% position:20% size:70%
WebVTT vertical <ruby>字幕<rt>subtitles</rt></ruby>
''')
(out / 'basic.ttml').write_text('''<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling">
<head><styling><style xml:id="green" tts:color="lime" tts:fontStyle="italic"/></styling></head>
<body><div><p begin="1s" end="7s" style="green">TTML green italic<br/>Second line</p>
<p begin="8s" end="14s">TTML second cue</p></div></body></tt>
''')

def segment(kind, payload):
    return bytes([kind]) + struct.pack('>H', len(payload)) + payload

def pcs(number, visible):
    return segment(0x16, struct.pack('>HHBHBBBB', 1280, 720, 0x10, number, 0x80 if number == 0 else 0, 0, 0, int(visible)) +
                   (struct.pack('>HBBHH', 0, 0, 0, 100, 200) if visible else b''))

rle = (bytes([1]) * 160 + b'\0\0') * 40
display = pcs(0, True)
display += segment(0x17, struct.pack('>BBHHHH', 1, 0, 0, 0, 1280, 720))
display += segment(0x14, bytes([0, 0, 0, 16, 128, 128, 0, 1, 81, 240, 90, 255]))
display += segment(0x15, struct.pack('>HBB', 0, 0, 0xC0) + (len(rle) + 4).to_bytes(3, 'big') + struct.pack('>HH', 160, 40) + rle)
display += segment(0x80, b'')
clear = pcs(1, False) + segment(0x80, b'')
(out / 'pgs-display.bin').write_bytes(display)
(out / 'pgs-clear.bin').write_bytes(clear)

sup = bytearray()
for time, packet in [(1, display), (7, clear), (8, display), (14, clear)]:
    pos = 0
    while pos < len(packet):
        size = int.from_bytes(packet[pos + 1:pos + 3], 'big') + 3
        sup += b'PG' + struct.pack('>II', time * 90000, 0) + packet[pos:pos + size]
        pos += size
(out / 'bitmap.sup').write_bytes(sup)

def ffmpeg(*args):
    subprocess.run(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error', *map(str, args)], check=True)

ffmpeg('-f', 'lavfi', '-i', 'color=c=0x182536:s=640x360:r=24:d=24', '-f', 'lavfi', '-i',
       'sine=frequency=440:sample_rate=48000:duration=24', '-c:v', 'libx264', '-preset', 'ultrafast',
       '-crf', '30', '-c:a', 'aac', '-b:a', '32k', out / 'video.mp4')
for file, codec in [('styled.ass', 'copy'), ('legacy.ssa', 'ass'), ('basic.srt', 'copy'), ('styled.vtt', 'copy'), ('bitmap.sup', 'copy')]:
    ffmpeg('-i', out / 'video.mp4', '-i', out / file, '-map', '0', '-map', '1:0', '-c', 'copy', '-c:s', codec,
           '-metadata:s:s:0', 'language=eng', '-disposition:s:0', 'default', out / (file + '.mkv'))
# A cyan video outline surrounds the ASS red rectangle when both use the same viewport.
ffmpeg('-i', out / 'video.mp4', '-i', out / 'styled.ass', '-map', '0', '-map', '1:0',
       '-vf', 'drawbox=x=49:y=99:w=82:h=22:color=cyan:t=2', '-c:v', 'libx264', '-preset', 'ultrafast',
       '-crf', '18', '-c:a', 'copy', '-c:s', 'copy', '-metadata:s:s:0', 'language=eng',
       '-disposition:s:0', 'default', out / 'alignment.mkv')
font_assets = pathlib.Path(__file__).parents[2] / 'androidTest/assets/subtitles'
for name in ['font.ass', 'font-embedded.ass', 'shapes.ttf']:
    (out / name).write_bytes((font_assets / name).read_bytes())
ffmpeg('-i', out / 'video.mp4', '-i', out / 'font.ass', '-map', '0', '-map', '1:0', '-c', 'copy',
       '-metadata:s:s:0', 'language=eng', '-disposition:s:0', 'default', '-attach', out / 'shapes.ttf',
       '-metadata:s:t:0', 'mimetype=application/x-truetype-font', out / 'font.ass.mkv')
ffmpeg('-i', out / 'video.mp4', '-i', out / 'basic.srt', '-map', '0', '-map', '1:0', '-c', 'copy', '-c:s', 'mov_text',
       '-metadata:s:s:0', 'language=eng', '-disposition:s:0', 'default', out / 'tx3g.mp4')
ffmpeg('-i', out / 'video.mp4', '-fix_sub_duration', '-i', out / 'bitmap.sup', '-map', '0', '-map', '1:0', '-c', 'copy', '-c:s', 'dvdsub',
       '-metadata:s:s:0', 'language=eng', '-disposition:s:0', 'default', out / 'vobsub.mkv')
ffmpeg('-i', out / 'video.mp4', '-fix_sub_duration', '-i', out / 'bitmap.sup', '-map', '0', '-map', '1:0', '-c:v', 'copy', '-c:a', 'copy',
       '-c:s', 'dvbsub', '-metadata:s:s:0', 'language=eng', out / 'dvb.ts')
print(f'Wrote subtitle fixtures to {out}')
