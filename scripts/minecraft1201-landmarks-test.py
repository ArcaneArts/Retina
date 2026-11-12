"""Load and reopen headless landmark samples with packaged Minecraft 1.20.1 mods."""
import argparse
import json
import math
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

STRUCTURES = ('minecraft:desert_pyramid', 'minecraft:jungle_pyramid', 'minecraft:swamp_hut')
LOOT = {'minecraft:chests/desert_pyramid', 'minecraft:archaeology/desert_pyramid',
        'minecraft:chests/jungle_temple', 'minecraft:chests/jungle_temple_dispenser'}
MOBS = {'minecraft:witch', 'minecraft:cat'}


def decoded_region(path):
    return (next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values())


def inside(point, bounds):
    return all(bounds[index] <= axis <= bounds[index + 3] for index, axis in enumerate(point))


def reference(path):
    starts = {}
    for chunk in decoded_region(path):
        starts.update({name: start for name, start in chunk['structures']['starts'].items() if name in STRUCTURES})
    assert set(starts) == set(STRUCTURES)
    loot = {}
    mobs = {}
    for chunk in decoded_region(path):
        for block in chunk.get('block_entities', []):
            table = block.get('LootTable')
            pos = tuple(block[axis] for axis in ('x', 'y', 'z'))
            if table in LOOT and any(inside(pos, start['Children'][0]['BB']) for start in starts.values()):
                loot.setdefault(table, pos)
        for entity in chunk.get('entities', []):
            name = entity['id']
            pos = tuple(entity['Pos'])
            if name in MOBS and inside(pos, starts['minecraft:swamp_hut']['Children'][0]['BB']):
                mobs[name] = pos
    assert set(loot) == LOOT and set(mobs) == MOBS
    return starts, loot, mobs


def verify_saved(path, starts):
    reread = {}
    for chunk in decoded_region(path):
        x, z = chunk['xPos'], chunk['zPos']
        for name, expected in starts.items():
            if (x, z) == (expected['ChunkX'], expected['ChunkZ']):
                reread[name] = chunk['structures']['starts'][name]
    assert reread == starts, 'The server changed or dropped the saved structure pieces'


def run_sample(server, wait_chunk, starts, loot, mobs, region, restart):
    if not restart:
        assert not region.exists()
    positions = {(start['ChunkX'], start['ChunkZ']) for start in starts.values()}
    for name, start in starts.items():
        x, z = start['ChunkX'] * 16, start['ChunkZ'] * 16
        server.command(f'execute positioned {x} 100 {z} run locate structure {name}', f'[{x}, ~, {z}]', timeout=45)
    if not restart:
        assert not region.exists(), 'Read-only locate published the landmarks'
    positions.update((x // 16, z // 16) for x, _, z in loot.values())
    positions.update((math.floor(x) // 16, math.floor(z) // 16) for x, _, z in mobs.values())
    if not restart:
        for x, z in sorted(positions):
            server.command(f'forceload add {x * 16} {z * 16}', 'force loaded')
    for x, z in sorted(positions):
        wait_chunk(server, 'execute in minecraft:overworld', x, z)
    for table, (x, y, z) in loot.items():
        server.command(f'data get block {x} {y} {z} LootTable', table, timeout=20)
    for name, (x, y, z) in mobs.items():
        marker = f'RETINA_LANDMARK_{name.split(":")[1].upper()}'
        server.command(f'execute positioned {x} {y} {z} if entity @e[type={name},distance=..24] run say {marker}',
                       marker, timeout=30)
    server.command('save-all flush', 'Saved the game')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'reference', 'pack', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--backend', default='metal', choices=('cpu', 'metal', 'vulkan'))
    args = parser.parse_args()
    spec = spec_from_file_location('legacy_server', Path(__file__).with_name('minecraft1201-server-test.py'))
    legacy = module_from_spec(spec)
    spec.loader.exec_module(legacy)
    support = legacy.sibling('bukkit-server-test')
    os.environ['RETINA_GPU_BACKEND'] = args.backend
    if args.forge_install:
        name = 'win_args.txt' if os.name == 'nt' else 'unix_args.txt'
        launch, = args.forge_install.glob('libraries/net/minecraftforge/forge/1.20.1-*/' + name)
        command = ['@' + str(launch.resolve())]
    else:
        command = ['-jar', str(args.server.resolve())]

    starts, loot, mobs = reference(args.reference)
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    shutil.copytree(args.pack, world / 'datapacks/retina-landmarks')
    properties = directory / 'server.properties'
    properties.write_text(properties.read_text().replace('generate-structures=false', 'generate-structures=true')
                          + 'initial-enabled-packs=vanilla,file/retina-landmarks\n')
    region = world / 'region' / args.reference.name
    launch = Namespace(java=args.java.resolve(), launch_args=command)
    for restart in (False, True):
        server = support.Server(launch, directory, restart)
        try:
            server.wait('Done (')
            source = 'saved-file' if restart else 'registry-export'
            assert f'from {source}' in server.output
            definition = support.digest(world / 'retina.dat')
            if restart:
                assert definition == original
            else:
                original = definition
            run_sample(server, legacy.wait_chunk, starts, loot, mobs, region, restart)
        finally:
            server.stop()
        verify_saved(region, starts)
    result = {'status': 'pass', 'landmarks': list(starts), 'loot_tables': list(loot), 'residents': list(mobs)}
    (directory / 'landmarks.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
