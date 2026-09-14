#!/usr/bin/env python3
"""Regenerate the original geometric font and ASS assets; requires fonttools."""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

out = Path(__file__).parents[2] / 'androidTest/assets/subtitles'
font = FontBuilder(1000, isTTF=True)
font.setupGlyphOrder(['.notdef', 'space', 'A', 'B', 'C'])
font.setupCharacterMap({32: 'space', 65: 'A', 66: 'B', 67: 'C'})
glyphs = {}
for name, points in {
    '.notdef': [], 'space': [],
    'A': [(50, 0), (950, 0), (950, 700), (50, 700)],
    'B': [(50, 0), (950, 0), (500, 700)],
    'C': [(50, 300), (500, 0), (950, 300), (500, 700)],
}.items():
    pen = TTGlyphPen(None)
    if points:
        pen.moveTo(points[0])
        for point in points[1:]:
            pen.lineTo(point)
        pen.closePath()
    glyphs[name] = pen.glyph()
font.setupGlyf(glyphs)
font.setupHorizontalMetrics({name: (1000, 50) for name in glyphs})
font.setupHorizontalHeader(ascent=800, descent=-200)
font.setupNameTable({'familyName': 'Nextlib Test Shapes', 'styleName': 'Regular',
                    'uniqueFontIdentifier': 'NextlibTestShapes', 'fullName': 'Nextlib Test Shapes',
                    'psName': 'NextlibTestShapes'})
font.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
font.setupPost()
font.setupMaxp()
font.font['head'].created = font.font['head'].modified = 2082844800
font.save(out / 'shapes.ttf')

header = (out / 'styled.ass').read_text().split('Dialogue:')[0]
header = header.replace('Default,Roboto,48', 'Default,Nextlib Test Shapes,100')
script = header + ''.join(f'Dialogue: 0,0:00:{start:02}.00,0:00:{end:02}.00,Default,,0,0,0,,ABC\n'
                          for start, end in [(1, 7), (8, 14), (16, 21)])
(out / 'font.ass').write_text(script)
# ASS font payloads use six-bit values offset by 33, without padding characters.
data = (out / 'shapes.ttf').read_bytes()
encoded = ''
for offset in range(0, len(data), 3):
    chunk = data[offset:offset + 3]
    value = int.from_bytes(chunk.ljust(3, b'\0'), 'big')
    encoded += ''.join(chr(((value >> shift) & 63) + 33) for shift in (18, 12, 6, 0))[:len(chunk) + 1]
(out / 'font-embedded.ass').write_text(script + '\n[Fonts]\nfontname: shapes.ttf\n' +
                                     '\n'.join(encoded[i:i + 80] for i in range(0, len(encoded), 80)) + '\n')
