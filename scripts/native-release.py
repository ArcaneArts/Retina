"""Content-addressed native release build, independent of Minecraft and release versions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    'aarch64-apple-darwin': ('macos-aarch64', 'libretina_worldgen.dylib'),
    'x86_64-pc-windows-msvc': ('windows-x86_64', 'retina_worldgen.dll'),
    'x86_64-unknown-linux-gnu': ('linux-x86_64', 'libretina_worldgen.so'),
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def output(*command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def fingerprint(target):
    files = [ROOT / 'native/Cargo.toml', ROOT / 'native/Cargo.lock', Path(__file__).resolve()]
    files += [p for directory in ('native/src', '.cargo') for p in (ROOT / directory).rglob('*') if p.is_file()]
    if (ROOT / 'native/build.rs').exists():
        files.append(ROOT / 'native/build.rs')
    toolchain = {'rustc': output('rustc', '-Vv'), 'cargo': output('cargo', '-V')}
    if target.endswith('darwin'):
        toolchain.update(clang=output('clang', '--version'), sdk=output('xcrun', '--show-sdk-version'))
    elif target.endswith('linux-gnu'):
        toolchain.update(zig=output(sys.executable, '-m', 'ziglang', 'version'), zigbuild=output('cargo', 'zigbuild', '--version'))
    else:
        compiler = subprocess.run(['cl'], capture_output=True, text=True)
        toolchain['msvc'] = compiler.stdout + compiler.stderr
    flags = {key: value for key, value in os.environ.items()
             if key.startswith(('CARGO_ENCODED_RUSTFLAGS', 'RUSTFLAGS', 'CFLAGS', 'CXXFLAGS',
                                'MACOSX_DEPLOYMENT_TARGET', 'SDKROOT', 'CC_', 'CXX_', 'Image'))
             or key in ('CC', 'CXX', 'AR', 'VCToolsVersion', 'WindowsSDKVersion')}
    # Paths in an SDKROOT are toolchain inputs on this host, not checkout paths.
    inputs = {'target': target, 'toolchain': toolchain, 'environment': flags,
              'files': {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(files)}}
    return {'key': digest(json.dumps(inputs, sort_keys=True).encode()), 'inputs': inputs}


def paths(target):
    platform, library = TARGETS[target]
    directory = ROOT / 'build/release-native' / target
    return directory, directory / 'natives' / platform / library, directory / 'manifests' / (target + '.json')


def build(target):
    command = ['cargo', 'zigbuild' if target.endswith('linux-gnu') else 'build',
               '--release', '--locked', '--lib', '--target',
               target + ('.2.17' if target.endswith('linux-gnu') else ''),
               '--target-dir', str(ROOT / 'build/native-target')]
    if target.endswith('windows-msvc'):
        command += ['--config', f'target.{target}.rustflags=["-C","target-feature=+crt-static"]']
    subprocess.run(command, cwd=ROOT / 'native', check=True)
    _, library, manifest = paths(target)
    library.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / 'build/native-target' / target / 'release' / library.name, library)
    record = fingerprint(target)
    record.update(sha256=digest(library.read_bytes()), bytes=library.stat().st_size,
                  resource=f'natives/{TARGETS[target][0]}/{library.name}')
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(record, sort_keys=True, indent=2) + '\n')


def verify(target):
    _, library, manifest = paths(target)
    record = json.loads(manifest.read_text())
    current = fingerprint(target)
    if record['key'] != current['key'] or record['inputs'] != current['inputs']:
        raise ValueError(f'{target}: native inputs differ from the cached build')
    if record['sha256'] != digest(library.read_bytes()) or record['bytes'] != library.stat().st_size:
        raise ValueError(f'{target}: native bytes differ from their build manifest')
    print(json.dumps({'target': target, 'key': record['key'], 'sha256': record['sha256'], 'bytes': record['bytes']}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['fingerprint', 'build', 'verify'])
    parser.add_argument('target', choices=TARGETS)
    args = parser.parse_args()
    if args.operation == 'fingerprint':
        record = fingerprint(args.target)
        print(record['key'])
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
                stream.write('key=' + record['key'] + '\n')
    elif args.operation == 'build':
        build(args.target)
    else:
        verify(args.target)
