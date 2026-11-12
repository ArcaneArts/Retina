"""Run the packaged 1.20.1 mod with stock or datapack Flat world generation."""
import argparse
import json
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

VILLAGE = 'minecraft:village_plains'
PYRAMID = 'minecraft:desert_pyramid'
VOID = 'retina:void_probe'


def chunks(path):
    return [next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values()]


def starts(rows, name):
    return {(chunk['xPos'], chunk['zPos']): chunk['structures']['starts'][name]
            for chunk in rows if name in chunk['structures']['starts']}


def sample(rows, unpack):
    remaining = {'minecraft:torch': 19, 'minecraft:gold_block': 20, 'minecraft:lava': -32}
    positions = {}
    for chunk in rows:
        for section in chunk['sections']:
            container = section.get('block_states', {})
            names = {row['Name'] for row in container.get('palette', [])}
            for block in sorted(remaining.keys() & names):
                y = remaining[block]
                if section['Y'] != y // 16:
                    continue
                for z in range(16):
                    for x in range(16):
                        index = ((y & 15) << 8) | (z << 4) | x
                        if unpack(container, index, 4)['Name'] == block:
                            positions[block] = (chunk['xPos'] * 16 + x, y, chunk['zPos'] * 16 + z)
                            break
                    if block in positions:
                        del remaining[block]
                        break
        if not remaining:
            return positions
    raise AssertionError(f'Missing Flat samples {remaining}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'reference', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--pack', action='store_true', help='Load the decorated Desert and Void fixture')
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

    rows = chunks(args.reference)
    name = PYRAMID if args.pack else VILLAGE
    saved = starts(rows, name)
    assert saved
    blocks = sample(rows, legacy.sibling('bukkit-probe').unpack) if args.pack else {}
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    properties = directory / 'server.properties'
    options = properties.read_text().replace('generate-structures=false', 'generate-structures=true')
    options += 'level-type=minecraft\\:flat\n'
    if args.pack:
        fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/flat-worldgen-pack'
        shutil.copytree(fixture, world / 'datapacks/retina-flat-worldgen')
        options += 'initial-enabled-packs=vanilla,file/retina-flat-worldgen\n'
    properties.write_text(options)
    positions = set(saved) | {(x // 16, z // 16) for x, _, z in blocks.values()}
    edit = (min(positions)[0] * 16, 100, min(positions)[1] * 16)
    launch = Namespace(java=args.java.resolve(), launch_args=command)
    for restart in (False, True):
        server = support.Server(launch, directory, restart)
        try:
            server.wait('Done (', timeout=120)
            assert 'Retina activated for minecraft:overworld' in server.output
            source = 'saved-file' if restart else 'registry-export'
            assert f'from {source}' in server.output
            digest = support.digest(world / 'retina.dat')
            if restart:
                assert digest == original
            else:
                original = digest
            for x, z in saved:
                server.command(f'execute positioned {x * 16} 60 {z * 16} run locate structure {name}',
                               f'[{x * 16}, ~, {z * 16}]', timeout=45)
            for x, z in sorted(positions):
                if not restart:
                    server.command(f'forceload add {x * 16} {z * 16}', 'force loaded')
                legacy.wait_chunk(server, 'execute in minecraft:overworld', x, z)
            for block, (x, y, z) in blocks.items():
                server.command(f'execute if block {x} {y} {z} {block} run say RETINA_FLAT_{block.split(":")[1].upper()}',
                               'RETINA_FLAT_' + block.split(':')[1].upper(), timeout=20)
            if args.pack:
                assert f'Retina activated for {VOID}' in server.output
                if not restart:
                    server.command(f'execute in {VOID} run forceload add 8 8', 'force loaded')
                legacy.wait_chunk(server, f'execute in {VOID}', 0, 0)
                server.command(f'execute in {VOID} if block 8 -61 8 minecraft:cobblestone run say RETINA_VOID_PLATFORM',
                               'RETINA_VOID_PLATFORM')
            x, y, z = edit
            if not restart:
                server.command(f'setblock {x} {y} {z} minecraft:diamond_block', 'Changed the block at')
            server.command(f'execute if block {x} {y} {z} minecraft:diamond_block run say RETINA_FLAT_EDIT', 'RETINA_FLAT_EDIT')
            server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
        assert starts(chunks(world / 'region' / args.reference.name), name) == saved
    print(json.dumps({'status': 'pass', 'structure': name, 'starts': len(saved), 'sampled_blocks': blocks,
                      'void_platform': args.pack, 'edit_preserved': True}), flush=True)


if __name__ == '__main__':
    main()
