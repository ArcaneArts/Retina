"""Collect the actual installable JARs after the release build completes."""

import shutil
import sys
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def inspect_catalog(archive: ZipFile, directory: str, minecraft: str, snapshot: str = 'vanilla-recompiled') -> None:
    manifest = json.loads(archive.read(directory + 'manifest.json'))
    catalog = archive.read(directory + 'retina-catalog.dat')
    expected = {'minecraft_version': minecraft, 'catalog_version': 2,
                'materialization_version': 1, 'generation_version': 1,
                'asset': 'retina-catalog.dat', 'runtime_snapshot': snapshot}
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
        inspect_catalog(archive, f'catalogs/{minecraft}/{snapshot}/', minecraft, snapshot)


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
    jar = Path('universal/build/libs', f'retina-{minecraft}-{version}.jar')
    libraries = {
        'aarch64-apple-darwin': ('macos-aarch64', 'libretina_worldgen.dylib'),
        'x86_64-pc-windows-msvc': ('windows-x86_64', 'retina_worldgen.dll'),
        'x86_64-unknown-linux-gnu': ('linux-x86_64', 'libretina_worldgen.so'),
    }
    with ZipFile(jar) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\n ', '')
        for attribute in ['Main-Class: art.arcane.retina.core.RetinaCli',
                          'Enable-Native-Access: ALL-UNNAMED', f'Retina-Minecraft-Version: {minecraft}']:
            if attribute not in manifest: raise ValueError(f'Missing CLI manifest attribute: {attribute}')
        if not archive.read('art/arcane/retina/core/RetinaCli.class'): raise ValueError('Missing CLI entry point')
        names = archive.namelist()
        if len(names) != len(set(names)): raise ValueError('Duplicate ZIP members')
        expected_natives = set()
        for target, (platform, library) in libraries.items():
            member = f'natives/{platform}/{library}'
            expected_natives.add(member)
            content = archive.read(member)
            manifest = json.loads(archive.read(f'META-INF/retina/natives/{target}.json'))
            if manifest['resource'] != member or manifest['inputs']['target'] != target:
                raise ValueError(f'Incorrect native target manifest: {target}')
            if len(content) != manifest['bytes'] or hashlib.sha256(content).hexdigest() != manifest['sha256']:
                raise ValueError(f'Native bytes differ from build manifest: {target}')
        actual_natives = {name for name in names if name.startswith('natives/') and not name.endswith('/')}
        if actual_natives != expected_natives: raise ValueError(f'Unexpected native set: {actual_natives}')
        fabric = json.loads(archive.read('fabric.mod.json'))
        if fabric['version'] != version or fabric['depends']['minecraft'] != minecraft:
            raise ValueError('Incorrect Fabric version')
        import tomllib
        neo = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
        if neo['mods'][0]['version'] != version: raise ValueError('Incorrect NeoForge version')
        for entry in ['art/arcane/retina/fabric/RetinaFabric.class',
                      'art/arcane/retina/fabric/RetinaFabricClient.class',
                      'art/arcane/retina/neoforge/RetinaNeoForge.class',
                      'retina.mixins.json', 'retina.client.mixins.json',
                      'META-INF/retina/runtime-inputs.json']:
            if not archive.read(entry): raise ValueError(f'Missing runtime entry {entry}')
        inspect_bukkit(archive, minecraft, version)
        inspect_mod_catalogs(archive, minecraft)
    for old in destination.glob('*.jar'):
        old.unlink()
    shutil.copy2(jar, destination / jar.name)
    print(f'Staged {jar.name} sha256={hashlib.sha256(jar.read_bytes()).hexdigest()} with three native platforms')


if __name__ == '__main__':
    stage(*sys.argv[1:])
