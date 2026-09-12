#!/usr/bin/env python3
"""Generate short reordered streams and Java/JNI packet + reference-frame assets.

Requires host ffmpeg/ffprobe with libx264, libx265, and libsvtav1.
Existing vp9*.ivf fixtures are generated using the commands in README.md.
"""
import hashlib
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parent
MEDIA = ROOT.parents[4] / 'build' / 'video-fixtures'
MEDIA.mkdir(parents=True, exist_ok=True)


def run(*args):
    return subprocess.check_output(args)


def save(name, packets, times, raw, width, height):
    frame_size = width * height * 3 // 2
    assert len(raw) == frame_size * len(times)
    data = bytearray(struct.pack('>i', len(packets)))
    for time_us, packet in packets:
        data += struct.pack('>qi', time_us, len(packet)) + packet
    (ROOT / (name + '.packets')).write_bytes(data)
    (ROOT / (name + '.frames')).write_text(''.join(
        f'{time_us} {hashlib.sha256(raw[i * frame_size:(i + 1) * frame_size]).hexdigest()}\n'
        for i, time_us in enumerate(times)
    ))


def raw_frames(path):
    return run('ffmpeg', '-v', 'error', '-i', str(path), '-fps_mode', 'passthrough',
               '-pix_fmt', 'yuv420p', '-f', 'rawvideo', '-')


codecs = {
    'h264': ['libx264', '-preset', 'fast', '-bf', '3', '-g', '12'],
    'hevc': ['libx265', '-preset', 'fast', '-x265-params',
             'pools=1:frame-threads=1:bframes=3:keyint=12:log-level=error'],
    'mpeg2': ['mpeg2video', '-bf', '2', '-g', '12'],
}
for codec, options in codecs.items():
    for count in (3, 48):
        name = f'{codec}-{count}'
        path = MEDIA / (name + '.ts')
        run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=160x96:rate=12',
            '-frames:v', str(count), '-c:v', *options, '-pix_fmt', 'yuv420p', '-an', '-f', 'mpegts', str(path))
        probe = json.loads(run('ffprobe', '-v', 'error', '-show_packets', '-show_data',
                               '-show_frames', '-of', 'json', str(path)))['packets_and_frames']
        packets = [p for p in probe if p['type'] == 'packet']
        frames = [f for f in probe if f['type'] == 'frame']
        base = min(round(float(p['pts_time']) * 1_000_000) for p in packets)
        inputs = []
        for packet in packets:
            data = b''.join(bytes.fromhex(line.split(':', 1)[1].split('  ')[0].strip())
                            for line in packet['data'].strip().splitlines())
            assert len(data) == int(packet['size'])
            inputs.append((round(float(packet['pts_time']) * 1_000_000) - base, data))
        times = [round(float(f['best_effort_timestamp_time']) * 1_000_000) - base for f in frames]
        assert len(times) == count
        save(name, inputs, times, raw_frames(path), 160, 96)

run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=160x96:rate=12',
    '-frames:v', '12', '-c:v', 'libsvtav1', '-preset', '12', '-svtav1-params', 'lp=2',
    '-an', str(MEDIA / 'av1.ivf'))
for name in ('av1', 'vp9', 'vp9-altref'):
    path = (MEDIA if name == 'av1' else ROOT) / (name + '.ivf')
    data = path.read_bytes()
    width, height, numerator, denominator = struct.unpack_from('<HHII', data, 12)
    packets = []
    position = 32
    while position < len(data):
        size, timestamp = struct.unpack_from('<IQ', data, position)
        position += 12
        packets.append((round(timestamp * denominator * 1_000_000 / numerator), data[position:position + size]))
        position += size
    save(name, packets, [p[0] for p in packets], raw_frames(path), width, height)
    if name == 'vp9':
        # Two visible keyframes in one superframe. Both inherit the packet's PTS.
        pieces = [packets[i][1] for i in (0, 12)]
        marker = 0xc9
        payload = b''.join(pieces) + bytes([marker]) + b''.join(
            struct.pack('<H', len(p)) for p in pieces) + bytes([marker])
        header = bytearray(data[:32])
        struct.pack_into('<I', header, 24, 1)
        multiple = MEDIA / 'vp9-multiple.ivf'
        multiple.write_bytes(header + struct.pack('<IQ', len(payload), 0) + payload)
        reference = json.loads(run('ffprobe', '-v', 'error', '-show_frames', '-of', 'json', str(multiple)))
        times = [round(float(f['pts_time']) * 1_000_000) for f in reference['frames']]
        assert times == [0, 0]
        save('vp9-multiple', [(0, payload)], times, raw_frames(multiple), width, height)
