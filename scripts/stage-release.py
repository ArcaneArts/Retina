"""Collect the actual installable JARs after the release build completes."""

import argparse
import gzip
import hashlib
import json
import shutil
import tomllib
from pathlib import Path
from struct import unpack
from zipfile import ZipFile

from benchmark_support import decode_nbt
from release_version import BUILD_TASKS, artifact_names

CATALOG_VARIANTS = {'bukkit-api', 'normal', 'normal-bonus-chest',
                    'normal-no-structures', 'normal-no-structures-bonus-chest'}
NATIVES = {
    'aarch64-apple-darwin': 'natives/macos-aarch64/libretina_worldgen.dylib',
    'x86_64-pc-windows-msvc': 'natives/windows-x86_64/retina_worldgen.dll',
    'x86_64-unknown-linux-gnu': 'natives/linux-x86_64/libretina_worldgen.so',
}


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
    if not CATALOG_VARIANTS.issubset(manifest['variants']):
        raise ValueError(f'{directory}manifest is missing supported catalog variants')


def inspect_mod_catalogs(archive: ZipFile, minecraft: str) -> None:
    for snapshot in ('vanilla-recompiled', 'vanilla-binary'):
        inspect_catalog(archive, f'catalogs/{minecraft}/{snapshot}/', minecraft, snapshot)


def inspect_bukkit(archive: ZipFile, api_level: str, version: str) -> None:
    descriptor = dict(line.split(':', 1) for line in archive.read('plugin.yml').decode().splitlines()
                      if line and not line.startswith((' ', '#')) and ':' in line)
    descriptor = {key: value.strip().strip("'\"") for key, value in descriptor.items()}
    for key, expected in {'name': 'Retina', 'version': version, 'api-version': api_level,
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
    forbidden = ('net/minecraft/', 'org/bukkit/', 'org/spigotmc/', 'net/fabricmc/', 'net/neoforged/',
                 'com/google/gson/', 'net/kyori/')
    for name in archive.namelist():
        if name.startswith(forbidden) or name.startswith('art/arcane/retina/bukkit/qa/'):
            raise ValueError(f'Non-release or unshaded class in Bukkit distribution: {name}')


def inspect_natives(archive: ZipFile) -> dict:
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError('Duplicate ZIP members')
    actual = {name for name in names if name.startswith('natives/') and not name.endswith('/')}
    if actual != set(NATIVES.values()):
        raise ValueError(f'Unexpected native set: {actual}')
    digests = {}
    for target, member in NATIVES.items():
        content = archive.read(member)
        manifest = json.loads(archive.read(f'META-INF/retina/natives/{target}.json'))
        if manifest['resource'] != member or manifest['inputs']['target'] != target:
            raise ValueError(f'Incorrect native target manifest: {target}')
        digest = hashlib.sha256(content).hexdigest()
        if len(content) != manifest['bytes'] or digest != manifest['sha256']:
            raise ValueError(f'Native bytes differ from build manifest: {target}')
        digests[target] = digest
    return digests


def inspect_universal(jar: Path, minecraft: str, version: str) -> None:
    with ZipFile(jar) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\n ', '')
        for attribute in ['Main-Class: art.arcane.retina.core.RetinaCli',
                          'Enable-Native-Access: ALL-UNNAMED', f'Retina-Minecraft-Version: {minecraft}',
                          f'Implementation-Version: {version}']:
            if attribute not in manifest: raise ValueError(f'Missing CLI manifest attribute: {attribute}')
        if not archive.read('art/arcane/retina/core/RetinaCli.class'): raise ValueError('Missing CLI entry point')
        inspect_natives(archive)
        fabric = json.loads(archive.read('fabric.mod.json'))
        if fabric['version'] != version or fabric['depends']['minecraft'] != minecraft:
            raise ValueError('Incorrect Fabric version')
        neo = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode())
        if neo['mods'][0]['version'] != version: raise ValueError('Incorrect NeoForge version')
        for entry in ['art/arcane/retina/fabric/RetinaFabric.class',
                      'art/arcane/retina/fabric/RetinaFabricClient.class',
                      'art/arcane/retina/neoforge/RetinaNeoForge.class',
                      'retina.mixins.json', 'retina.client.mixins.json',
                      'META-INF/retina/runtime-inputs.json']:
            if not archive.read(entry): raise ValueError(f'Missing runtime entry {entry}')
        inspect_bukkit(archive, minecraft, version)
        inspect_catalog(archive, f'catalogs/{minecraft}/', minecraft)
        inspect_mod_catalogs(archive, minecraft)


def inspect_legacy_catalog(archive: ZipFile, minecraft: str) -> str:
    name = f'catalogs/{minecraft}/retina-catalog.dat'
    catalogs = {member for member in archive.namelist() if member.startswith('catalogs/') and not member.endswith('/')}
    if catalogs != {name}:
        raise ValueError(f'Unexpected 1.20.1 catalogs: {catalogs}')
    catalog = archive.read(name)
    root = next(iter(decode_nbt(gzip.decompress(catalog)).values()))
    if root['catalog_version'] != 2 or root['materialization_version'] != 1:
        raise ValueError('Incorrect 1.20.1 catalog schema')
    variants = root['compressed_variants']
    if not CATALOG_VARIANTS.issubset(variants):
        raise ValueError('1.20.1 catalog is missing supported variants')
    for name, encoded in variants.items():
        variant = next(iter(decode_nbt(gzip.decompress(encoded)).values()))
        template = variant['template']
        if template['minecraft_version'] != minecraft or template['data_version'] != 3465:
            raise ValueError(f'{name} contains a different Minecraft version')
        if set(template['dimensions']) != {'minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end'}:
            raise ValueError(f'{name} is missing 1.20.1 dimensions')
    return hashlib.sha256(catalog).hexdigest()


def inspect_legacy(jar: Path, loader: str, minecraft: str, version: str) -> tuple:
    descriptors = {'fabric': 'fabric.mod.json', 'forge': 'META-INF/mods.toml', 'bukkit': 'plugin.yml'}
    forbidden_descriptors = {*descriptors.values(), 'META-INF/neoforge.mods.toml'} - {descriptors[loader]}
    with ZipFile(jar) as archive:
        names = set(archive.namelist())
        if names & forbidden_descriptors:
            raise ValueError(f'{jar.name} contains metadata for another loader')
        manifest = archive.read('META-INF/MANIFEST.MF').decode()
        for attribute in (f'Implementation-Version: {version}', f'Retina-Minecraft-Version: {minecraft}'):
            if attribute not in manifest:
                raise ValueError(f'{jar.name} is missing {attribute}')
        for name in sorted(names):
            if name.startswith(('net/minecraft/', 'net/minecraftforge/', 'net/fabricmc/', 'org/bukkit/')):
                raise ValueError(f'{jar.name} contains host classes: {name}')
            if name.endswith('.class') and unpack('>H', archive.read(name)[6:8])[0] > 61:
                raise ValueError(f'{jar.name} contains a class newer than Java 17: {name}')
        required = ['art/arcane/retina/core/JniDefinitionTransport.class']
        if loader == 'fabric':
            descriptor = json.loads(archive.read(descriptors[loader]))
            if descriptor['version'] != version or descriptor['depends']['minecraft'] != minecraft:
                raise ValueError('Incorrect Fabric 1.20.1 metadata')
            required.extend(['art/arcane/retina/fabric1201/RetinaFabric1201.class', 'retina1201.mixins.json'])
        elif loader == 'forge':
            descriptor = tomllib.loads(archive.read(descriptors[loader]).decode())
            game = next(dependency for dependency in descriptor['dependencies']['retina'] if dependency['modId'] == 'minecraft')
            if descriptor['mods'][0]['version'] != version or game['versionRange'] != f'[{minecraft}]':
                raise ValueError('Incorrect Forge 1.20.1 metadata')
            required.extend(['art/arcane/retina/forge1201/RetinaForge1201.class', 'retina1201-forge.mixins.json',
                             'retina1201.refmap.json', 'META-INF/jarjar/metadata.json'])
        else:
            inspect_bukkit(archive, '1.20', version)
        for name in required:
            if not archive.read(name):
                raise ValueError(f'{jar.name} contains an empty runtime entry: {name}')
        return inspect_legacy_catalog(archive, minecraft), inspect_natives(archive)


def stage(minecraft: str, version: str, line: str = '26') -> None:
    if not version.startswith(f'{minecraft}-') or not version.removeprefix(f'{minecraft}-').isdecimal():
        raise ValueError('Release version must contain the selected Minecraft version and numeric commit count')
    names = artifact_names(line, version)
    if line == '26':
        jars = [Path('universal/build/libs', names[0])]
        inspect_universal(jars[0], minecraft, version)
    else:
        if minecraft != '1.20.1':
            raise ValueError('The 1.20 release line targets Minecraft 1.20.1')
        results = []
        jars = []
        for loader, name in zip(('fabric', 'forge', 'bukkit'), names, strict=True):
            jar = Path(f'{loader}1201/build/libs', name)
            jars.append(jar)
            results.append(inspect_legacy(jar, loader, minecraft, version))
        if any(result != results[0] for result in results[1:]):
            raise ValueError('The 1.20.1 JARs contain different catalog or native bytes')
    destination = Path('build/release')
    destination.mkdir(parents=True, exist_ok=True)
    for old in destination.glob('*.jar'):
        old.unlink()
    for jar in jars:
        shutil.copy2(jar, destination / jar.name)
        print(f'Staged {jar.name} sha256={hashlib.sha256(jar.read_bytes()).hexdigest()} with three native platforms')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('minecraft')
    parser.add_argument('version')
    parser.add_argument('--line', choices=BUILD_TASKS, default='26')
    args = parser.parse_args()
    stage(args.minecraft, args.version, args.line)
