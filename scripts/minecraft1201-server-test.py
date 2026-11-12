"""Run an installable Retina 1.20.1 mod on a local Java 17 dedicated server."""
import argparse
import ctypes as c
import json
import os
import shutil
import time
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records
from saved_definition import SavedDefinition, check


def sibling(name):
    spec = spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


DIMENSIONS = {'overworld': '', 'the_nether': 'DIM-1', 'the_end': 'DIM1'}
SAMPLES = [(-1, -1), (0, 0), (1, 1)]
EDITS = {
    'overworld': (1, 100, 1, 'minecraft:diamond_block'),
    'the_nether': (-1, 130, -1, 'minecraft:emerald_block'),
    'the_end': (1, 100, 1, 'minecraft:gold_block'),
}


def prepare(args, directory, pack=None):
    mods = directory / 'mods'
    mods.mkdir(parents=True)
    shutil.copy2(args.mod, mods / 'retina.jar')
    if args.forge_install:
        (directory / 'libraries').symlink_to(args.forge_install.resolve() / 'libraries', target_is_directory=True)
    (directory / 'eula.txt').write_text('eula=true\n')
    properties = 'level-name=world\nlevel-seed=12345\ngenerate-structures=false\nserver-ip=127.0.0.1\nserver-port=0\n'
    properties += 'online-mode=false\nview-distance=2\nsimulation-distance=2\nmax-tick-time=0\n'
    if pack:
        properties += 'level-type=retina\\:noise_probe\ninitial-enabled-packs=vanilla,file/retina-noise\n'
        target = directory / 'world/datapacks/retina-noise'
        shutil.copytree(pack, target)
        if args.forge_install:
            fixture = Path(__file__).resolve().parent.parent / 'minecraft1201/src/datagen/fixtures/forge-biome-modifier'
            shutil.copytree(fixture, target, dirs_exist_ok=True)
    (directory / 'server.properties').write_text(properties)


def wait_chunk(server, execute, x, z):
    deadline = time.monotonic() + 60
    while True:
        try:
            server.command(f'{execute} if loaded {x * 16} 100 {z * 16} run say RETINA_CHUNK_LOADED',
                           'RETINA_CHUNK_LOADED', timeout=1)
            return
        except TimeoutError:
            if time.monotonic() >= deadline:
                raise


def sample(server, edits):
    server.command('gamerule randomTickSpeed 0', 'Gamerule randomTickSpeed is now set to: 0')
    for dimension in DIMENSIONS:
        execute = 'execute in minecraft:' + dimension
        server.command(f'{execute} run forceload add -1 -1 16 16', 'force loaded')
        for x, z in SAMPLES:
            wait_chunk(server, execute, x, z)
        if edits:
            x, y, z, material = EDITS[dimension]
            server.command(f'{execute} run setblock {x} {y} {z} {material}', 'Changed the block at', timeout=10)
    server.command('save-all flush', 'Saved the game')


def read_end_reference(directory, gateway_exit=(100, 50, 0), crystal_beam=None):
    """Check the seed-12345 corpus produced by the End datagen acceptance commands."""
    import math
    unpack = sibling('bukkit-probe').unpack
    records = {}
    chorus = 0
    island = False
    for region in sorted(directory.glob('*.mca')):
        for payload in mca_records(region).values():
            chunk = next(iter(decode_nbt(payload).values()))
            assert chunk['DataVersion'] == 3465
            crystals = [tag for tag in chunk.get('entities', []) if tag['id'] == 'minecraft:end_crystal']
            gateways = [tag for tag in chunk.get('block_entities', []) if tag['id'] == 'minecraft:end_gateway']
            for crystal in crystals:
                assert crystal['FallDistance'] == 0 and 'fall_distance' not in crystal
                assert crystal.get('BeamTarget') == (dict(zip('XYZ', crystal_beam)) if crystal_beam else None)
                assert crystal['Invulnerable'] == bool(crystal_beam)
                x, y, z = map(math.floor, crystal['Pos'])
                assert block_at(chunk, x, y - 2, z, unpack) == 'minecraft:obsidian'
                assert block_at(chunk, x, y - 1, z, unpack) == 'minecraft:bedrock'
                assert block_at(chunk, x, y, z, unpack) == 'minecraft:air'
            for gateway in gateways:
                assert gateway['ExitPortal'] == dict(zip('XYZ', gateway_exit)) and gateway['ExactTeleport'] == 1
                assert block_at(chunk, gateway['x'], gateway['y'], gateway['z'], unpack) == 'minecraft:end_gateway'
            if crystals or gateways:
                records[chunk['xPos'], chunk['zPos']] = crystals, gateways
            if (chunk['xPos'], chunk['zPos']) == (96, 0):
                # The vanilla getBaseColumn reference is air at this height.
                island = block_at(chunk, 1548, 54, 0, unpack) == 'minecraft:end_stone'
            chorus += any(block['Name'] == 'minecraft:chorus_flower' for section in chunk['sections']
                          for block in section.get('block_states', {}).get('palette', []))
    uuids = {tuple(crystal['UUID']) for crystals, _ in records.values() for crystal in crystals}
    assert len(uuids) == sum(len(crystals) for crystals, _ in records.values()) == 10
    assert sum(len(gateways) for _, gateways in records.values()) > 0
    assert chorus > 0 and island
    return records


def block_at(chunk, x, y, z, unpack):
    section = next(section for section in chunk['sections'] if section['Y'] == y >> 4)
    index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
    return unpack(section['block_states'], index, 4)['Name']


def load_end(server, records, restart):
    execute = 'execute in minecraft:the_end'
    for x, z in records:
        if not restart:
            server.command(f'{execute} run forceload add {x * 16} {z * 16}', 'force loaded')
        wait_chunk(server, execute, x, z)
    server.command('save-all flush', 'Saved the game')


def compare_end(world, records):
    from functools import cache

    @cache
    def region(kind, rx, rz):
        return mca_records(world / 'DIM1' / kind / f'r.{rx}.{rz}.mca')

    checked = {'crystals': 0, 'gateways': 0}
    for (x, z), (crystals, gateways) in records.items():
        slot = (z & 31) * 32 + (x & 31)
        if crystals:
            actual = next(iter(decode_nbt(region('entities', x >> 5, z >> 5)[slot]).values()))
            saved = {tuple(tag['UUID']): tag for tag in actual['Entities'] if tag['id'] == 'minecraft:end_crystal'}
            for crystal in crystals:
                observed = saved[tuple(crystal['UUID'])]
                for key in ('Pos', 'BeamTarget', 'ShowBottom', 'Invulnerable'):
                    assert observed.get(key) == crystal.get(key), (x, z, key, observed)
                checked['crystals'] += 1
        if gateways:
            actual = next(iter(decode_nbt(region('region', x >> 5, z >> 5)[slot]).values()))
            saved = {(tag['x'], tag['y'], tag['z']): tag for tag in actual['block_entities']
                     if tag['id'] == 'minecraft:end_gateway'}
            for gateway in gateways:
                observed = saved[gateway['x'], gateway['y'], gateway['z']]
                assert observed['ExitPortal'] == gateway['ExitPortal'] and observed['ExactTeleport'] == 1
                checked['gateways'] += 1
    return checked


def compare(world, library, dimensions, edits):
    unpack = sibling('bukkit-probe').unpack
    lib = c.CDLL(str(library.resolve()))
    lib.retina_world_dimension_chunk.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_int32, c.c_int32,
                                                 c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
    receipt = {'chunks': 0, 'blocks': 0, 'biomes': 0, 'heightmaps': 0, 'player_edits': 0,
               'emerald_ore': 0, 'gold_ore': 0, 'desert_biomes': 0}
    for dimension in dimensions:
        region = world / DIMENSIONS[dimension] / 'region'
        with SavedDefinition(lib, world / 'retina.dat', 'minecraft:' + dimension) as saved:
            for x, z in SAMPLES:
                payload = mca_records(region / f'r.{x >> 5}.{z >> 5}.mca')[(z & 31) * 32 + (x & 31)]
                actual = next(iter(decode_nbt(payload).values()))
                assert actual['Status'] == 'minecraft:full' and actual['DataVersion'] == 3465 and actual['isLightOn'] == 1
                pointer, length = c.c_void_p(), c.c_uint64()
                check(lib, lib.retina_world_dimension_chunk(saved.handle, saved.key, len(saved.key), x, z,
                                                            c.byref(pointer), c.byref(length)))
                try:
                    native = next(iter(decode_nbt(c.string_at(pointer, length.value)).values()))
                finally:
                    lib.retina_free_bytes(pointer, length)
                sections = {s['Y']: s for s in native['sections'] if 'block_states' in s}
                saved_sections = {s['Y']: s for s in actual['sections'] if 'block_states' in s}
                assert sections.keys() == saved_sections.keys()
                edit = EDITS[dimension] if edits else None
                edited_chunk = edit and (edit[0] >> 4, edit[2] >> 4) == (x, z)
                for section_y, expected in sections.items():
                    observed = saved_sections[section_y]
                    for i in range(4096):
                        block = unpack(expected['block_states'], i, 4)
                        if edited_chunk and (x * 16 + (i & 15), section_y * 16 + (i >> 8), z * 16 + ((i >> 4) & 15)) == edit[:3]:
                            block = {'Name': edit[3]}
                            receipt['player_edits'] += 1
                        assert unpack(observed['block_states'], i, 4) == block, (dimension, x, z, section_y, i, block)
                        receipt['emerald_ore'] += not edits and block['Name'] == 'minecraft:emerald_block'
                        receipt['gold_ore'] += not edits and block['Name'] == 'minecraft:gold_block'
                    for i in range(64):
                        biome = unpack(expected['biomes'], i, 1)
                        assert unpack(observed['biomes'], i, 1) == biome, (dimension, x, z, section_y, i, biome)
                        receipt['desert_biomes'] += biome == 'minecraft:desert'
                    receipt['blocks'] += 4096
                    receipt['biomes'] += 64
                if not edited_chunk:
                    for name, heightmap in actual['Heightmaps'].items():
                        assert heightmap == native['Heightmaps'][name], (dimension, x, z, name)
                        receipt['heightmaps'] += 1
                receipt['chunks'] += 1
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'library', 'pack', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge server installer')
    parser.add_argument('--backend', default='cpu', choices=('cpu', 'metal', 'vulkan'))
    parser.add_argument('--end-reference', type=Path, help='Headless End region directory for vanilla seed 12345')
    args = parser.parse_args()
    support = sibling('bukkit-server-test')
    os.environ['RETINA_GPU_BACKEND'] = args.backend
    if args.forge_install:
        name = 'win_args.txt' if os.name == 'nt' else 'unix_args.txt'
        launch_file, = args.forge_install.glob('libraries/net/minecraftforge/forge/1.20.1-*/' + name)
        command = ['@' + str(launch_file.resolve())]
    else:
        command = ['-jar', str(args.server.resolve())]
    launch = Namespace(java=args.java.resolve(), launch_args=command, jvm_args=[])
    vanilla = args.output.resolve() / 'vanilla'
    prepare(args, vanilla)
    definition = vanilla / 'world/retina.dat'
    end_records = read_end_reference(args.end_reference) if args.end_reference else None
    report = {'backend': args.backend}
    for restart in (False, True):
        server = support.Server(launch, vanilla, restart)
        try:
            server.wait('Done (')
            stage = 'restart' if restart else 'first'
            report[stage + '_seconds'] = time.monotonic() - server.started
            assert ('from saved-file' if restart else 'from bundled-vanilla') in server.output
            if restart:
                assert support.digest(definition) == original
                for dimension, (x, y, z, material) in EDITS.items():
                    server.command(f'execute in minecraft:{dimension} if block {x} {y} {z} {material} run say RETINA_EDIT_KEPT',
                                   'RETINA_EDIT_KEPT')
            else:
                original = support.digest(definition)
                sample(server, edits=True)
            if end_records:
                load_end(server, end_records, restart)
        finally:
            server.stop()
        report[stage] = compare(vanilla / 'world', args.library, DIMENSIONS, edits=True)
        assert report[stage]['player_edits'] == len(EDITS)
        if end_records:
            report[stage]['end_features'] = compare_end(vanilla / 'world', end_records)

    datapack = args.output.resolve() / 'datapack'
    prepare(args, datapack, args.pack)
    server = support.Server(launch, datapack)
    try:
        server.wait('Done (')
        report['datapack_seconds'] = time.monotonic() - server.started
        assert 'from registry-export' in server.output
        server.command('save-all flush', 'Saved the game')
    finally:
        server.stop()
    report['datapack'] = compare(datapack / 'world', args.library, ['overworld'], edits=False)
    assert report['datapack']['emerald_ore'] > 0 and report['datapack']['desert_biomes'] == report['datapack']['biomes']
    if args.forge_install:
        assert report['datapack']['gold_ore'] > 0, 'Forge biome modifier ore was not generated'
    (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
