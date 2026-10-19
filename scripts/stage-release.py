"""Collect the actual installable JARs after the release build completes."""

import shutil
import sys
from pathlib import Path
from zipfile import ZipFile


def stage(minecraft: str, version: str) -> None:
    destination = Path('build/release')
    destination.mkdir(parents=True, exist_ok=True)
    libraries = {
        'macos': 'libretina_worldgen.dylib',
        'linux': 'libretina_worldgen.so',
        'windows': 'retina_worldgen.dll',
    }
    for loader in ('fabric', 'neoforge'):
        jar = Path(loader, 'build/libs', f'retina-{loader}-{minecraft}-{version}.jar')
        # Inspect the built artifacts themselves; sources/common JARs are not releases.
        with ZipFile(jar) as archive:
            for system, library in libraries.items():
                for arch in ('x86_64', 'aarch64'):
                    member = f'natives/{system}-{arch}/{library}'
                    if not archive.read(member):
                        raise ValueError(f'{jar}: empty native library {member}')
        shutil.copy2(jar, destination / jar.name)
        print(f'Staged {jar.name} with all six native libraries')


if __name__ == '__main__':
    stage(*sys.argv[1:])
