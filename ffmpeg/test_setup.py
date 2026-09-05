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
    for name in ('mbedtls-3.4.1', 'libvpx-1.13.0', 'ffmpeg-6.0'):
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
    cmake.unlink()
    env['ANDROID_CLI'] = str(root / 'missing-cli')
    result = run()
    assert result.returncode != 0 and 'Install Android CLI' in result.stderr
    env.pop('ANDROID_HOME')
    env.pop('ANDROID_SDK_ROOT', None)
    result = run()
    assert result.returncode != 0 and 'Set ANDROID_HOME' in result.stderr

print('Setup checks passed')
