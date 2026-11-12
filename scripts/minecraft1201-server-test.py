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
    (directory / 'eula.txt').write_text('eula=true\n')
    properties = 'level-name=world\nlevel-seed=12345\ngenerate-structures=false\nserver-ip=127.0.0.1\nserver-port=0\n'
    properties += 'online-mode=false\nview-distance=2\nsimulation-distance=2\nmax-tick-time=0\n'
    if pack:
        properties += 'level-type=retina\\:noise_probe\ninitial-enabled-packs=vanilla,file/retina-noise\n'
        shutil.copytree(pack, directory / 'world/datapacks/retina-noise')
    (directory / 'server.properties').write_text(properties)


def sample(server, edits):
    server.command('gamerule randomTickSpeed 0', 'Gamerule randomTickSpeed is now set to: 0')
    for dimension in DIMENSIONS:
        execute = 'execute in minecraft:' + dimension
        server.command(f'{execute} run forceload add -1 -1 16 16', 'force loaded')
        for x, z in SAMPLES:
            deadline = time.monotonic() + 60
            while True:
                try:
                    server.command(f'{execute} if loaded {x * 16} 100 {z * 16} run say RETINA_CHUNK_LOADED',
                                   'RETINA_CHUNK_LOADED', timeout=1)
                    break
                except TimeoutError:
                    if time.monotonic() >= deadline:
                        raise
        if edits:
            x, y, z, material = EDITS[dimension]
            server.command(f'{execute} run setblock {x} {y} {z} {material}', 'Changed the block at', timeout=10)
    server.command('save-all flush', 'Saved the game')


def compare(world, library, dimensions, edits):
    unpack = sibling('bukkit-probe').unpack
    lib = c.CDLL(str(library.resolve()))
    lib.retina_world_dimension_chunk.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_int32, c.c_int32,
                                                 c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
    receipt = {'chunks': 0, 'blocks': 0, 'biomes': 0, 'heightmaps': 0, 'player_edits': 0, 'emerald_ore': 0, 'desert_biomes': 0}
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
    for option in ('java', 'server', 'mod', 'library', 'pack', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    parser.add_argument('--backend', default='cpu', choices=('cpu', 'metal', 'vulkan'))
    args = parser.parse_args()
    support = sibling('bukkit-server-test')
    os.environ['RETINA_GPU_BACKEND'] = args.backend
    launch = Namespace(java=args.java.resolve(), server=args.server.resolve(), jvm_args=[])
    vanilla = args.output.resolve() / 'vanilla'
    prepare(args, vanilla)
    definition = vanilla / 'world/retina.dat'
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
        finally:
            server.stop()
        report[stage] = compare(vanilla / 'world', args.library, DIMENSIONS, edits=True)
        assert report[stage]['player_edits'] == len(EDITS)

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
    (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
