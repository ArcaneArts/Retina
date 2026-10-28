"""Collect the actual installable JARs after the release build completes."""

import shutil
import sys
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def inspect_catalog(archive: ZipFile, directory: str, minecraft: str) -> None:
    manifest = json.loads(archive.read(directory + 'manifest.json'))
    catalog = archive.read(directory + 'retina-catalog.dat')
    expected = {'minecraft_version': minecraft, 'catalog_version': 2,
                'materialization_version': 1, 'generation_version': 1,
                'asset': 'retina-catalog.dat'}
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f'{directory}manifest {key}: {manifest.get(key)!r}, expected {value!r}')
    if len(catalog) != manifest['compressed_bytes'] or hashlib.sha256(catalog).hexdigest() != manifest['sha256']:
        raise ValueError(f'{directory}catalog bytes differ from the packaged manifest')
    required = {'bukkit-api', 'normal', 'normal-bonus-chest',
                'normal-no-structures', 'normal-no-structures-bonus-chest'}
    if not required.issubset(manifest['variants']):
        raise ValueError(f'{directory}manifest is missing supported catalog variants')


def inspect_mod_catalogs(archive: ZipFile, minecraft: str) -> None:
    for snapshot in ('vanilla-recompiled', 'vanilla-binary'):
        inspect_catalog(archive, f'catalogs/{minecraft}/{snapshot}/', minecraft)


def inspect_bukkit(archive: ZipFile, minecraft: str, version: str) -> None:
    descriptor = dict(line.split(':', 1) for line in archive.read('plugin.yml').decode().splitlines()
                      if line and not line.startswith((' ', '#')) and ':' in line)
    descriptor = {key: value.strip().strip("'\"") for key, value in descriptor.items()}
    for key, expected in {'name': 'Retina', 'version': version, 'api-version': minecraft,
                          'main': 'art.arcane.retina.bukkit.RetinaPlugin'}.items():
        if descriptor.get(key) != expected:
            raise ValueError(f'plugin.yml {key}: {descriptor.get(key)!r}, expected {expected!r}')
    required = [descriptor['main'].replace('.', '/') + '.class', 'config.yml',
                'art/arcane/retina/core/NativeLibrary.class',
                'art/arcane/retina/core/NativeWorldDefinition.class',
                'art/arcane/retina/core/NativeCatalog.class',
                'art/arcane/retina/internal/gson/JsonParser.class',
                'art/arcane/retina/internal/kyori/adventure/nbt/BinaryTagIO.class']
    for name in required:
        if not archive.read(name): raise ValueError(f'Empty plugin resource {name}')
    inspect_catalog(archive, f'catalogs/{minecraft}/', minecraft)
    forbidden = ('net/minecraft/', 'org/bukkit/', 'org/spigotmc/', 'net/fabricmc/', 'net/neoforged/',
                 'com/google/gson/', 'net/kyori/')
    for name in archive.namelist():
        if name.startswith(forbidden) or name.endswith('/qa/ProbePlugin.class'):
            raise ValueError(f'Non-release or unshaded class in Bukkit distribution: {name}')


def stage(minecraft: str, version: str) -> None:
    destination = Path('build/release')
    destination.mkdir(parents=True, exist_ok=True)
    libraries = {
        'macos': 'libretina_worldgen.dylib',
        'linux': 'libretina_worldgen.so',
        'windows': 'retina_worldgen.dll',
    }
    for loader in ('fabric', 'neoforge', 'bukkit'):
        jar = Path(loader, 'build/libs', f'retina-{loader}-{minecraft}-{version}.jar')
        # Inspect the built artifacts themselves; sources/common JARs are not releases.
        with ZipFile(jar) as archive:
            for system, library in libraries.items():
                for arch in ('x86_64', 'aarch64'):
                    member = f'natives/{system}-{arch}/{library}'
                    if not archive.read(member):
                        raise ValueError(f'{jar}: empty native library {member}')
            if loader == 'bukkit': inspect_bukkit(archive, minecraft, version)
            else: inspect_mod_catalogs(archive, minecraft)
        shutil.copy2(jar, destination / jar.name)
        print(f'Staged {jar.name} with all six native libraries')


if __name__ == '__main__':
    stage(*sys.argv[1:])
