"""Run with python3 ffmpeg/test_setup.py; no SDK downloads or native compilation."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tarfile


def executable(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('#!/bin/bash\nset -eu\n' + body)
    path.chmod(0o755)


with tempfile.TemporaryDirectory(prefix='nextlib setup ') as temp:
    root = Path(temp)
    ffmpeg = root / 'ffmpeg'
    ffmpeg.mkdir()
    shutil.copy(Path(__file__).with_name('setup.sh'), ffmpeg)
    for name in ('mbedtls-3.4.1', 'ffmpeg-6.0', 'dav1d-1.5.4'):
        (ffmpeg / 'sources' / name).mkdir(parents=True)
    # Partial previous builds must not suppress a retry.
    (ffmpeg / 'build').mkdir()
    (ffmpeg / 'output').mkdir()
    sdk = root / 'android sdk'
    host = 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
    clang = sdk / 'ndk/test-ndk/toolchains/llvm/prebuilt' / host / 'bin/clang'
    cmake = sdk / 'cmake/test-cmake/bin/cmake'
    cli = root / 'android cli'
    log = root / 'cli.log'
    executable(cli, 'printf "%s\\n" "$@" > "$CLI_LOG"\nexit 19\n')
    executable(root / 'bin/pkg-config', 'exit 0\n')
    for tool in ('meson', 'ninja', 'nasm'):
        executable(root / 'bin' / tool, 'exit 0\n')
    env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'], ANDROID_HOME=str(sdk), ANDROID_CLI=str(cli),
               ANDROID_NDK_VERSION='test-ndk', ANDROID_CMAKE_VERSION='test-cmake',
               CLI_LOG=str(log))

    def run():
        return subprocess.run(['bash', str(ffmpeg / 'setup.sh')], env=env,
                              capture_output=True, text=True)

    assert run().returncode == 19, 'CLI install failures must propagate'
    assert log.read_text().splitlines() == [f'--sdk={sdk}', 'sdk', 'install',
                                           'ndk/test-ndk', 'cmake/test-cmake']
    executable(clang, 'exit 0\n')
    assert run().returncode == 19
    assert log.read_text().splitlines()[-1:] == ['cmake/test-cmake']
    assert 'ndk/test-ndk' not in log.read_text().splitlines()
    executable(cli, 'exit 0\n')
    result = run()
    assert result.returncode != 0 and 'installation is incomplete' in result.stderr
    # Stop at the first native build step; installed tools must avoid the CLI.
    executable(cmake, 'echo native-build-reached >&2\nexit 42\n')
    log.unlink()
    result = run()
    assert result.returncode == 42 and 'native-build-reached' in result.stderr, result
    assert not log.exists()
    source = ffmpeg / 'sources/mbedtls-3.4.1'
    shutil.rmtree(source)
    executable(root / 'bin/curl', 'exit 22\n')
    result = run()
    assert result.returncode == 22 and not source.exists()
    assert not list((ffmpeg / 'sources').glob('.download.*'))
    archive = root / 'source.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        directory = tarfile.TarInfo('mbedtls-3.4.1')
        directory.type = tarfile.DIRTYPE
        directory.mode = 0o755
        tar.addfile(directory)
    env['SOURCE_ARCHIVE'] = str(archive)
    executable(root / 'bin/curl', 'cp "$SOURCE_ARCHIVE" "${@: -1}"\n')
    result = run()
    assert result.returncode == 42 and source.is_dir(), result
    assert not list((ffmpeg / 'sources').glob('.download.*'))

    # Exercise dav1d's cross-build and FFmpeg's target-only dependency discovery.
    executable(cmake, 'exit 0\n')
    executable(root / 'bin/make', 'exit 0\n')
    executable(root / 'bin/meson', '''
printf '%s\\n' "$@" > "$2.args"
''')
    executable(ffmpeg / 'sources/ffmpeg-6.0/configure', '''
prefix=${1#--prefix=}
mkdir -p "$prefix/lib" "$prefix/include"
touch "$prefix/lib/libavcodec.so" "$prefix/include/avcodec.h"
printf '%s\\n' "$@" > "$prefix/configure.args"
printf '%s\\n' "$PKG_CONFIG_PATH" "$PKG_CONFIG_LIBDIR" > "$prefix/pkgconfig.env"
''')
    env['PKG_CONFIG_PATH'] = '/host/libraries/must/not/be/used'
    result = run()
    assert result.returncode == 0, result
    for abi, toolchain, cpu in (
        ('x86', 'i686-linux-android', 'x86'),
        ('x86_64', 'x86_64-linux-android', 'x86_64'),
        ('armeabi-v7a', 'armv7a-linux-androideabi', 'arm'),
        ('arm64-v8a', 'aarch64-linux-android', 'aarch64'),
    ):
        cross = (ffmpeg / f'build/dav1d/{abi}.meson').read_text()
        assert f'{toolchain}21-clang' in cross and f"cpu_family = '{cpu}'" in cross
        assert 'needs_exe_wrapper = true' in cross
        args = (ffmpeg / f'build/dav1d/{abi}.args').read_text().splitlines()
        for option in ('--default-library=static', '-Db_staticpic=true',
                       '-Denable_tools=false', '-Denable_tests=false', '--libdir=lib'):
            assert option in args, (abi, option)
        args = (ffmpeg / f'build/{abi}/configure.args').read_text().splitlines()
        for option in ('--enable-libdav1d', '--enable-decoder=libdav1d', '--enable-decoder=vp8',
                       '--enable-decoder=vp9', '--pkg-config-flags=--static'):
            assert option in args, (abi, option)
        assert '--enable-libvpx' not in args
        assert (ffmpeg / f'build/{abi}/pkgconfig.env').read_text().splitlines() == [
            '', str(ffmpeg / f'build/external/{abi}/lib/pkgconfig')]
    executable(root / 'bin/ninja', 'exit 43\n')
    assert run().returncode == 43, 'dav1d build failures must stop the build'

    cmake.unlink()
    env['ANDROID_CLI'] = str(root / 'missing-cli')
    result = run()
    assert result.returncode != 0 and 'Install Android CLI' in result.stderr
    env.pop('ANDROID_HOME')
    env.pop('ANDROID_SDK_ROOT', None)
    result = run()
    assert result.returncode != 0 and 'Set ANDROID_HOME' in result.stderr

print('Setup checks passed')
