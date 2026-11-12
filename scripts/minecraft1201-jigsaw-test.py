"""Load and reopen sampled 1.20.1 jigsaw regions with a packaged mod."""
import argparse
import json
import math
import os
import shutil
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

VILLAGE = 'minecraft:village_snowy'
BASTION = 'minecraft:bastion_remnant'
OVERWORLD = 'minecraft:overworld'
NETHER = 'minecraft:the_nether'
PENDING = 'retina:finalize_structure_entity'


def chunks(path):
    return [next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values()]


def start_at(records, name):
    matches = [chunk['structures']['starts'][name] for chunk in records if name in chunk['structures']['starts']]
    assert len(matches) == 1, (name, len(matches))
    return matches[0]


def marker(records, start, unpack):
    x0, y0, z0, x1, y1, z1 = start['Children'][0]['BB']
    for chunk in records:
        for section in chunk['sections']:
            blocks = section.get('block_states', {})
            if not any(block['Name'] == 'minecraft:gold_block' for block in blocks.get('palette', [])):
                continue
            for index in range(4096):
                x = chunk['xPos'] * 16 + (index & 15)
                y = section['Y'] * 16 + (index >> 8)
                z = chunk['zPos'] * 16 + ((index >> 4) & 15)
                if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
                    if unpack(blocks, index, 4)['Name'] == 'minecraft:gold_block':
                        return x, y, z
    raise AssertionError('No gold block from the loaded jigsaw processor')


def pick_brute(records):
    return next(entity for chunk in records for entity in chunk.get('entities', [])
                if entity['id'] == 'minecraft:piglin_brute' and entity.get(PENDING))


def save_and_check(server, legacy, start, dimension, restart, extra):
    command = f'execute in {dimension}'
    cx, cz = start['ChunkX'], start['ChunkZ']
    server.command(f'{command} positioned {cx * 16} 64 {cz * 16} run locate structure {start["id"]}',
                   f'[{cx * 16}, ~, {cz * 16}]', timeout=45)
    positions = {(cx, cz), *extra}
    for x, z in sorted(positions):
        if not restart:
            server.command(f'{command} run forceload add {x * 16} {z * 16}', 'force loaded')
        legacy.wait_chunk(server, command, x, z)


def check_brute(server, reference):
    uuid = '[I;' + ','.join(str(number) for number in reference['UUID']) + ']'
    selector = '@e[type=minecraft:piglin_brute,nbt={UUID:' + uuid + ',HandItems:[{id:"minecraft:golden_axe"}]}]'
    server.command(f'execute in {NETHER} if entity {selector} run say RETINA_BRUTE_INITIALIZED',
                   'RETINA_BRUTE_INITIALIZED', timeout=25)


def assert_brute_saved(world, brute):
    matches = [entity for path in (world / 'DIM-1/entities').glob('*.mca')
               for chunk in chunks(path) for entity in chunk.get('Entities', []) if entity.get('UUID') == brute['UUID']]
    assert len(matches) == 1 and PENDING not in matches[0], 'Bastion mob should be stored once without its initialization marker'
    assert matches[0]['HandItems'][0]['id'] == 'minecraft:golden_axe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'village', 'bastion', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--pack', action='store_true', help='Load the snowy village processor fixture')
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

    village_records = chunks(args.village)
    village = start_at(village_records, VILLAGE)
    bastion_records = chunks(args.bastion)
    bastion = start_at(bastion_records, BASTION)
    brute = pick_brute(bastion_records)
    bx, _, bz = (math.floor(axis) for axis in brute['Pos'])
    gold = marker(village_records, village, legacy.sibling('bukkit-probe').unpack) if args.pack else None
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    world = directory / 'world'
    properties = directory / 'server.properties'
    options = properties.read_text().replace('generate-structures=false', 'generate-structures=true')
    if args.pack:
        fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/jigsaw-pack'
        shutil.copytree(fixture, world / 'datapacks/retina-jigsaw')
        options += 'initial-enabled-packs=vanilla,file/retina-jigsaw\n'
    properties.write_text(options)
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
            village_extra = {(gold[0] // 16, gold[2] // 16)} if gold else set()
            save_and_check(server, legacy, village, OVERWORLD, restart, village_extra)
            if gold:
                x, y, z = gold
                server.command(f'execute in {OVERWORLD} if block {x} {y} {z} minecraft:gold_block run say RETINA_JIGSAW_RULE',
                               'RETINA_JIGSAW_RULE')
            save_and_check(server, legacy, bastion, NETHER, restart, {(bx // 16, bz // 16)})
            check_brute(server, brute)
            server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
        assert start_at(chunks(world / 'region' / args.village.name), VILLAGE) == village
        assert start_at(chunks(world / 'DIM-1/region' / args.bastion.name), BASTION) == bastion
        assert_brute_saved(world, brute)
    print(json.dumps({'status': 'pass', 'village_pieces': len(village['Children']), 'bastion_pieces': len(bastion['Children']),
                      'custom_processor_marker': gold, 'bastion_brute_reopened': True}), flush=True)


if __name__ == '__main__':
    main()
