"""Check built release AARs using ANDROID_HOME (or ANDROID_NDK_HOME); no emulator needed."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r'^ndk = "([^"]+)"', (root / 'gradle/libs.versions.toml').read_text(), re.M)[1]
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
ndk = Path(os.environ.get('ANDROID_NDK_HOME', sdk / 'ndk' / version))
host = 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
assert (tools / 'llvm-readelf').is_file(), 'Set ANDROID_HOME or ANDROID_NDK_HOME to the build toolchain'
abis = {'x86', 'x86_64', 'armeabi-v7a', 'arm64-v8a'}
system_libraries = {'libc.so', 'libm.so', 'libdl.so', 'libz.so', 'liblog.so', 'libandroid.so', 'libjnigraphics.so'}

with tempfile.TemporaryDirectory() as temporary:
    library = Path(temporary) / 'lib.so'
    for module in ('media3ext', 'mediainfo'):
        with zipfile.ZipFile(root / f'{module}/build/outputs/aar/{module}-release.aar') as aar:
            names = aar.namelist()
            packaged = {(n.split('/')[1], Path(n).name) for n in names if n.endswith('.so')}
            assert {n.split('/')[1] for n in names if n.endswith('.so')} == abis
            if module == 'media3ext':
                assert all(f'jni/{abi}/libass.so' in names for abi in abis)
                assert len([n for n in names if n.startswith('assets/native-dependencies/') and not n.endswith('/')]) >= 7
            for name in (n for n in names if n.endswith('.so')):
                library.write_bytes(aar.read(name))
                elf = subprocess.check_output([tools / 'llvm-readelf', '-l', '-d', library], text=True)
                assert 'TEXTREL' not in elf, name
                needed = re.findall(r'\(NEEDED\).*?\[([^]]+)\]', elf)
                missing = {dep for dep in needed if dep not in system_libraries and (name.split('/')[1], dep) not in packaged}
                assert not missing, (name, missing)
                assert all(int(line.split()[-1], 16) >= 16384 for line in elf.splitlines()
                           if line.strip().startswith('LOAD')), f'{name}: requires 16 KiB alignment'
                if name.endswith('/libass.so'):
                    assert '[libass.so]' in elf, f'{name}: incorrect SONAME'
                    assert set(needed) <= {'libc.so', 'libm.so', 'libz.so', 'libdl.so'}, needed
                    symbols = subprocess.check_output([tools / 'llvm-nm', '-D', '--defined-only', library], text=True)
                    exported = {line.split()[-1] for line in symbols.splitlines()}
                    assert {'ass_library_init', 'ass_add_font', 'ass_render_frame'} <= exported, name
                    assert all(symbol.startswith('ass_') for symbol in exported), name
        print(f'{module}: all four ABIs packaged with 16 KiB-compatible native libraries')
