#!/usr/bin/env python3
"""Install the checksum-pinned official Bend toolchain without changing PATH.

This only installs the compiler/standard library. It never builds or launches
the Retina worker. Historical experiments retain their own toolchain pins.
"""
import argparse
import hashlib
from pathlib import Path
import platform
import tarfile
import tempfile
import urllib.request

from test_bend_compression import VERSION, DEFAULT_BEND


CHECKSUMS = {
    'darwin-arm64': '846d25ccf0c12d5d1e5e648c0b3ba4c8397fdb852812ac00ef8135998f8e194b',
    'darwin-x64': '93eea3f54b7b91f28ea15a1b9f2d825afa58c1fcd417c11024a62f8c3506b813',
    'linux-arm64': 'b65d452e5c68a4c486e353220d4298ba5d76f535db47109a7d76ffdf72efafb1',
    'linux-x64': '84c32465a39bc9ac315eade23f470cb8db03153dc860f89564fcfec153c3e445',
}


def install(target, archive, destination):
    expected = CHECKSUMS[target]
    with archive.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != expected:
        raise ValueError(f'Bend {VERSION} {target} archive SHA256 mismatch: {actual}')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.bend-install-', dir=destination.parent) as temporary:
        stage = Path(temporary)
        with tarfile.open(archive) as package:
            package.extractall(stage, filter='data')
        # Never overwrite an existing toolchain, playable worker, or artifacts.
        # Rename publishes this complete versioned installation atomically.
        (stage/'bend').rename(destination)
    print(f'Installed {destination / "bin/bend"}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    arch = {'aarch64': 'arm64', 'x86_64': 'x64', 'AMD64': 'x64'}.get(platform.machine(), platform.machine())
    parser.add_argument('--target', choices=CHECKSUMS, default=f'{platform.system().lower()}-{arch}')
    parser.add_argument('--archive', type=Path, help='Use an already downloaded official archive')
    parser.add_argument('--destination', type=Path, default=DEFAULT_BEND.parents[1])
    args = parser.parse_args()
    if args.target not in CHECKSUMS:
        parser.error(f'No pinned Bend release for {args.target}')
    if args.archive:
        install(args.target, args.archive, args.destination)
    else:
        with tempfile.TemporaryDirectory(prefix='retina-bend-download-') as temporary:
            archive = Path(temporary)/'bend.tar.gz'
            url = f'https://github.com/bendlang/bend/releases/download/v{VERSION}/bend-{VERSION}-{args.target}.tar.gz'
            urllib.request.urlretrieve(url, archive)
            install(args.target, archive, args.destination)


if __name__ == '__main__':
    main()
