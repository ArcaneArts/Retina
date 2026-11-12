"""Verify the 1.20.1 attachment fixture in a packaged mod before and after restart."""
import argparse
import json
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

EXECUTE = 'execute in retina:attachment_probe'
PLANTS = {'minecraft:vine', 'minecraft:glow_lichen', 'minecraft:sculk_vein'}


def sample(path, unpack):
    selected = {}
    for payload in mca_records(path).values():
        chunk = next(iter(decode_nbt(payload).values()))
        for section in chunk['sections']:
            states = section.get('block_states', {})
            targets = {block['Name'] for block in states.get('palette', [])
                       if block['Name'] in PLANTS and block['Name'] not in selected
                       and (block['Name'] == 'minecraft:vine' or block.get('Properties', {}).get('waterlogged') == 'true')}
            if not targets:
                continue
            for index in range(4096):
                state = unpack(states, index, 4)
                name = state['Name']
                props = state.get('Properties', {})
                if name not in targets or name != 'minecraft:vine' and props.get('waterlogged') != 'true':
                    continue
                x = chunk['xPos'] * 16 + (index & 15)
                y = section['Y'] * 16 + (index >> 8)
                z = chunk['zPos'] * 16 + ((index >> 4) & 15)
                pattern = name + '[' + ','.join(f'{key}={value}' for key, value in sorted(props.items())) + ']'
                selected[name] = (x, y, z, pattern)
                targets.remove(name)
                if not targets:
                    break
            if set(selected) == PLANTS:
                return selected
    raise AssertionError(f'Missing generated attachment samples: {PLANTS - set(selected)}')


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

    plants = sample(args.reference, legacy.sibling('bukkit-probe').unpack)
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/attachment-pack'
    shutil.copytree(fixture, world / 'datapacks/retina-attachments')
    properties = directory / 'server.properties'
    properties.write_text(properties.read_text() + 'initial-enabled-packs=vanilla,file/retina-attachments\n')
    launch = Namespace(java=args.java.resolve(), launch_args=command)
    positions = {(x // 16, z // 16) for x, _, z, _ in plants.values()}
    for restart in (False, True):
        server = support.Server(launch, directory, restart)
        try:
            server.wait('Done (', timeout=120)
            source = 'saved-file' if restart else 'registry-export'
            assert f'from {source}' in server.output
            digest = support.digest(world / 'retina.dat')
            if restart:
                assert digest == original
            else:
                original = digest
                for x, z in sorted(positions):
                    server.command(f'{EXECUTE} run forceload add {x * 16} {z * 16}', 'force loaded')
            for x, z in sorted(positions):
                legacy.wait_chunk(server, EXECUTE, x, z)
            for name, (x, y, z, state) in plants.items():
                result = 'RETINA_ATTACHMENT_' + name.split(':', 1)[1].upper()
                server.command(f'{EXECUTE} if block {x} {y} {z} {state} run say {result}', result, timeout=25)
            server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
    print(json.dumps({'status': 'pass', 'states': plants}), flush=True)


if __name__ == '__main__':
    main()
