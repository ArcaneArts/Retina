"""Validate headless fortress samples in a packaged Java 17 dedicated server."""
import argparse
import json
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

FORTRESS = 'minecraft:fortress'
EXECUTE = 'execute in minecraft:the_nether'
PACK = 'retina-nether-fortress'


def region(path):
    return (next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values())


def saved_starts(path):
    return {(chunk['xPos'], chunk['zPos']): chunk['structures']['starts'][FORTRESS]
            for chunk in region(path) if FORTRESS in chunk['structures']['starts']}


def sample(path):
    starts = saved_starts(path)
    assert starts and all(len(start['Children']) > 1 for start in starts.values())
    items = {}
    for chunk in region(path):
        for block in chunk.get('block_entities', []):
            if block.get('LootTable') == 'minecraft:chests/nether_bridge':
                items.setdefault('LootTable', ('minecraft:chests/nether_bridge', block))
            if block.get('SpawnData', {}).get('entity', {}).get('id') == 'minecraft:blaze':
                items.setdefault('SpawnData.entity.id', ('minecraft:blaze', block))
    assert len(items) == 2
    return starts, items


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'reference', 'output'):
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

    starts, items = sample(args.reference)
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/nether-fortress-pack'
    shutil.copytree(fixture, world / 'datapacks' / PACK)
    properties = directory / 'server.properties'
    properties.write_text(properties.read_text().replace('generate-structures=false', 'generate-structures=true')
                          + f'initial-enabled-packs=vanilla,file/{PACK}\n')
    target = world / 'DIM-1/region' / args.reference.name
    positions = set(starts)
    positions.update((block['x'] // 16, block['z'] // 16) for _, block in items.values())
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
                assert not target.exists()
            for x, z in starts:
                server.command(f'{EXECUTE} positioned {x * 16} 64 {z * 16} run locate structure {FORTRESS}',
                               f'[{x * 16}, ~, {z * 16}]', timeout=45)
            if not restart:
                assert not target.exists(), 'Read-only locate published the Nether region'
                for x, z in sorted(positions):
                    server.command(f'{EXECUTE} run forceload add {x * 16} {z * 16}', 'force loaded')
            for x, z in sorted(positions):
                legacy.wait_chunk(server, EXECUTE, x, z)
            for field, (value, block) in items.items():
                x, y, z = (block[axis] for axis in ('x', 'y', 'z'))
                server.command(f'{EXECUTE} run data get block {x} {y} {z} {field}', f'"{value}"', timeout=20)
            server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
        assert saved_starts(target) == starts, 'Server changed or dropped saved fortress pieces'
    print(json.dumps({'status': 'pass', 'starts': len(starts), 'pieces': sum(len(s['Children']) for s in starts.values()),
                      'block_data': list(items)}), flush=True)


if __name__ == '__main__':
    main()
