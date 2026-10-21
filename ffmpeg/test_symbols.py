"""Check release AARs after assembleRelease; requires GNU or LLVM readelf."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
from zipfile import ZipFile


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--readelf', default='readelf')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
abis = ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
libraries = {
    'media3ext': ('media3ext', 'avcodec', 'avutil', 'swresample', 'swscale'),
    'mediainfo': ('mediainfo', 'avcodec', 'avformat', 'avutil', 'swscale'),
}
shared_libraries = {}
checked = 0

with tempfile.TemporaryDirectory(prefix='nextlib-symbols-') as temp:
    for module, names in libraries.items():
        aar = root / module / f'build/outputs/aar/{module}-release.aar'
        expected = {f'jni/{abi}/lib{name}.so' for abi in abis for name in names}
        with ZipFile(aar) as archive:
            actual = {name for name in archive.namelist() if name.endswith('.so')}
            assert actual == expected, (aar, 'Unexpected native libraries', actual ^ expected)
            for name in sorted(expected):
                binary = archive.read(name)
                if name in shared_libraries:
                    assert binary == shared_libraries[name], (name, 'AARs contain different binaries')
                shared_libraries[name] = binary
                library = Path(temp) / 'library.so'
                library.write_bytes(binary)
                metadata = subprocess.check_output(
                    [args.readelf, '--wide', '--sections', '--notes', str(library)], text=True)
                for section in ('.symtab', '.debug_info', '.debug_line'):
                    assert re.search(r'\s' + re.escape(section) + r'\s', metadata), (
                        aar, name, f'Missing {section}; native debug information was stripped')
                assert re.search(r'Build ID: [0-9a-f]+', metadata), (aar, name, 'Missing ELF build ID')
                checked += 1

print(f'Native symbol checks passed: {checked} libraries across both release AARs and all four ABIs')
