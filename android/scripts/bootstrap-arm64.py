#!/usr/bin/env python3
"""Project-local toolchain for Ubuntu 24.04 ARM64; no system installation."""
import gzip
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import urllib.request

assert platform.machine() == 'aarch64', 'Use a native JDK/SDK on other hosts; see README'
root = Path(__file__).resolve().parents[2]
tools = root / '.tools'
downloads = tools / 'downloads'
downloads.mkdir(parents=True, exist_ok=True)

def download(name, url, digest=None):
    dest = downloads / name
    if not dest.exists():
        urllib.request.urlretrieve(url, dest)
    if digest:
        assert hashlib.sha256(dest.read_bytes()).hexdigest() == digest, name + ' checksum mismatch'
    return dest

def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)

jdk = download('jdk.tar.gz', 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz', '457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41')
gradle = download('gradle.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip', 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6')
sdk = download('sdk.zip', 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip', '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee')
if not (tools / 'jdk/bin/java').exists():
    (tools / 'jdk').mkdir(exist_ok=True)
    run('tar', '-xzf', str(jdk), '-C', str(tools / 'jdk'), '--strip-components=1')
if not (tools / 'gradle-8.11.1/bin/gradle').exists():
    run('unzip', '-q', str(gradle), '-d', str(tools))
cmd = tools / 'sdk/cmdline-tools'
if not (cmd / 'latest/bin/sdkmanager').exists():
    cmd.mkdir(parents=True, exist_ok=True)
    run('unzip', '-q', str(sdk), '-d', str(cmd))
    (cmd / 'cmdline-tools').rename(cmd / 'latest')
env = dict(os.environ, JAVA_HOME=str(tools / 'jdk'))
manager = str(cmd / 'latest/bin/sdkmanager')
print('Accepting Android SDK licenses for this local build toolchain.')
subprocess.run([manager, '--sdk_root=' + str(tools / 'sdk'), '--licenses'], input='y\n' * 100, text=True, env=env, check=True)
run(manager, '--sdk_root=' + str(tools / 'sdk'), 'platforms;android-36', 'build-tools;35.0.0', env=env)
# apt download uses the host's authenticated package metadata; never apt install.
run('apt', 'download', 'qemu-user', 'libcapstone4', 'liburing2', cwd=downloads)
for pattern in ('qemu-user*.deb', 'libcapstone4*.deb', 'liburing2*.deb'):
    for deb in downloads.glob(pattern):
        run('dpkg-deb', '-x', str(deb), str(tools / 'qemu'))
index = download('amd64-packages.gz', 'https://archive.ubuntu.com/ubuntu/dists/noble/main/binary-amd64/Packages.gz')
for block in gzip.open(index, 'rt').read().split('\n\n'):
    fields = dict(line.split(': ', 1) for line in block.splitlines() if ': ' in line and not line.startswith(' '))
    if fields.get('Package') in ('libc6', 'libgcc-s1', 'libstdc++6', 'zlib1g'):
        deb = download(fields['Package'] + '-amd64.deb', 'https://archive.ubuntu.com/ubuntu/' + fields['Filename'], fields['SHA256'])
        run('dpkg-deb', '-x', str(deb), str(tools / 'amd64'))
for name in ('lib', 'lib64'):
    path = tools / 'amd64' / name
    if not path.exists():
        path.symlink_to('usr/' + name)
launcher = tools / 'aapt2'
launcher.write_text('''#!/bin/sh
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export LD_LIBRARY_PATH="$root/qemu/usr/lib/aarch64-linux-gnu"
exec "$root/qemu/usr/bin/qemu-x86_64" -L "$root/amd64" "$root/sdk/build-tools/35.0.0/aapt2" "$@"
''')
launcher.chmod(0o755)
run(str(launcher), 'version')
print('Ready: android/scripts/build-debug.sh')
