"""Load and reopen headless 1.20.1 Nether ruined portals in a packaged mod."""
import argparse
import json
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

PORTAL = 'minecraft:ruined_portal_nether'
LOOT = 'minecraft:chests/ruined_portal'
EXECUTE = 'execute in minecraft:the_nether'


def read_region(path):
    return [next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values()]


def starts(chunks):
    return {(chunk['xPos'], chunk['zPos']): chunk['structures']['starts'][PORTAL]
            for chunk in chunks if PORTAL in chunk['structures']['starts']}


def sample(path):
    chunks = read_region(path)
    saved = starts(chunks)
    assert saved
    bounds = [start['Children'][0]['BB'] for start in saved.values()]
    loot = {}
    for chunk in chunks:
        for block in chunk.get('block_entities', []):
            if block.get('LootTable') != LOOT:
                continue
            pos = tuple(block[axis] for axis in ('x', 'y', 'z'))
            if any(all(box[axis] <= value <= box[axis + 3] for axis, value in enumerate(pos)) for box in bounds):
                loot[pos] = block['LootTableSeed']
    assert loot
    return saved, loot


def verify(server, legacy, saved, loot, target, restart):
    if not restart:
        assert not target.exists()
    for cx, cz in saved:
        server.command(f'{EXECUTE} positioned {cx * 16} 64 {cz * 16} run locate structure {PORTAL}',
                       f'[{cx * 16}, ~, {cz * 16}]', timeout=45)
    if not restart:
        assert not target.exists(), 'Read-only locate published the Nether region'
    positions = set(saved) | {(x // 16, z // 16) for x, _, z in loot}
    for cx, cz in sorted(positions):
        if not restart:
            server.command(f'{EXECUTE} run forceload add {cx * 16} {cz * 16}', 'force loaded')
        legacy.wait_chunk(server, EXECUTE, cx, cz)
    for (x, y, z), seed in loot.items():
        server.command(f'{EXECUTE} run data get block {x} {y} {z} LootTable', f'"{LOOT}"', timeout=20)
        server.command(f'{EXECUTE} run data get block {x} {y} {z} LootTableSeed', f'{seed}L', timeout=20)
    server.command('save-all flush', 'Saved the game')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'reference', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--pack', action='store_true', help='Load the Nether portal setup fixture')
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

    saved, loot = sample(args.reference)
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    properties = directory / 'server.properties'
    options = properties.read_text().replace('generate-structures=false', 'generate-structures=true')
    if args.pack:
        fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/nether-portal-pack'
        shutil.copytree(fixture, world / 'datapacks/retina-nether-portals')
        options += 'initial-enabled-packs=vanilla,file/retina-nether-portals\n'
    properties.write_text(options)

    target = world / 'DIM-1/region' / args.reference.name
    launch = Namespace(java=args.java.resolve(), launch_args=command)
    for restart in (False, True):
        server = support.Server(launch, directory, restart)
        try:
            server.wait('Done (', timeout=120)
            source = 'saved-file' if restart else ('registry-export' if args.pack else 'bundled-vanilla')
            assert f'from {source}' in server.output
            digest = support.digest(world / 'retina.dat')
            if restart:
                assert digest == original
            else:
                original = digest
            verify(server, legacy, saved, loot, target, restart)
        finally:
            server.stop()
        assert starts(read_region(target)) == saved, 'Server changed or dropped the saved portal pieces'
    print(json.dumps({'status': 'pass', 'starts': len(saved), 'loot_chests': len(loot), 'datapack': args.pack}), flush=True)


if __name__ == '__main__':
    main()
