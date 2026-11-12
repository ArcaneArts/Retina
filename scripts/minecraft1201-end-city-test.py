"""Read and reopen a generated End city on an installed Minecraft 1.20.1 mod."""
import argparse
import json
import os
import shutil
import time
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import decode_nbt, mca_records

CITY = 'minecraft:end_city'
END = 'execute in minecraft:the_end'
LOOT = 'minecraft:chests/end_city_treasure'


def reference(path):
    chunks = [next(iter(decode_nbt(payload).values())) for payload in mca_records(path).values()]
    starts = {(c['xPos'], c['zPos']): c['structures']['starts'][CITY] for c in chunks
              if CITY in c['structures']['starts']}
    for chunk in chunks:
        for frame in chunk.get('entities', []):
            if frame['id'] != 'minecraft:item_frame' or frame.get('Item', {}).get('id') != 'minecraft:elytra':
                continue
            references = chunk['structures']['References'][CITY]
            for (cx, cz), start in starts.items():
                key = (cx & 0xffffffff) | ((cz & 0xffffffff) << 32)
                if not any((value & 0xffffffffffffffff) == key for value in references):
                    continue
                for nearby in chunks:
                    if not any((value & 0xffffffffffffffff) == key
                               for value in nearby['structures']['References'].get(CITY, [])):
                        continue
                    for chest in nearby.get('block_entities', []):
                        if chest.get('LootTable') == LOOT:
                            return start, (chunk['xPos'], chunk['zPos']), frame, chest
    raise AssertionError('No End city with a ship and loot chest in the supplied headless region')


def locate(server, start):
    x, z = start['ChunkX'] * 16, start['ChunkZ'] * 16
    return server.command(f'{END} positioned {x} 100 {z} run locate structure minecraft:end_city',
                          f'[{x}, ~, {z}]', timeout=45)


def explorer_map(server, start, claimed):
    x, z = start['ChunkX'] * 16 + 1, start['ChunkZ'] * 16 + 1
    execute = f'{END} positioned {x} 220 {z}'
    marker = 'minecraft:map' if claimed else 'minecraft:filled_map'
    selector = '@e[type=minecraft:item,distance=..3,limit=1,sort=nearest]'
    server.command(f'{execute} run loot spawn ~ ~ ~ loot retina:explorer_city', 'Dropped 1 [', timeout=45)
    server.command(f'{execute} run data get entity {selector} Item', marker, timeout=15)
    server.command(f'{execute} run kill {selector}', 'Killed', timeout=15)


def sample(server, wait_chunk, start, frame, chest, restart):
    x, y, z = (frame['TileX'], frame['TileY'], frame['TileZ'])
    bx, by, bz = (chest['x'], chest['y'], chest['z'])
    positions = {(x // 16, z // 16), (bx // 16, bz // 16), (start['ChunkX'], start['ChunkZ'])}
    if not restart:
        for cx, cz in sorted(positions):
            server.command(f'{END} run forceload add {cx * 16} {cz * 16}', 'force loaded')
    for cx, cz in sorted(positions):
        wait_chunk(server, END, cx, cz)
    server.command(f'{END} positioned {x} {y} {z} run data get entity '
                   '@e[type=minecraft:item_frame,distance=..2,limit=1,sort=nearest] Item', 'minecraft:elytra', timeout=30)
    server.command(f'{END} run data get block {bx} {by} {bz} LootTable', LOOT, timeout=20)
    server.command('save-all flush', 'Saved the game')


def read_chunk(directory, position):
    x, z = position
    region = directory / f'r.{x // 32}.{z // 32}.mca'
    slot = (x & 31) + (z & 31) * 32
    return next(iter(decode_nbt(mca_records(region)[slot]).values()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'reference', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--explorer-map-pack', type=Path, help='Also validate the unreferenced explorer map path')
    parser.add_argument('--backend', default='cpu', choices=('cpu', 'metal', 'vulkan'))
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

    start, frame_chunk, frame, chest = reference(args.reference)
    directory = args.output.resolve()
    legacy.prepare(args, directory)
    properties = directory / 'server.properties'
    config = properties.read_text().replace('generate-structures=false', 'generate-structures=true')
    world = directory / 'world'
    if args.explorer_map_pack:
        shutil.copytree(args.explorer_map_pack, world / 'datapacks/retina-explorer')
        config += 'initial-enabled-packs=vanilla,file/retina-explorer\n'
    properties.write_text(config)
    expected = start.copy()
    if args.explorer_map_pack:
        expected['references'] = 1
    result = {'start': [start['ChunkX'], start['ChunkZ']], 'piece_count': len(start['Children'])}
    for restart in (False, True):
        stage = 'restart' if restart else 'first'
        server = support.Server(Namespace(java=args.java.resolve(), launch_args=command), directory, restart)
        try:
            server.wait('Done (')
            source = 'saved-file' if restart else 'registry-export' if args.explorer_map_pack else 'bundled-vanilla'
            assert f'from {source}' in server.output
            if restart:
                assert support.digest(world / 'retina.dat') == original
            else:
                original = support.digest(world / 'retina.dat')
            result[stage + '_startup_seconds'] = round(time.monotonic() - server.started, 3)
            target = world / 'DIM1' / 'region' / f"r.{start['ChunkX'] // 32}.{start['ChunkZ'] // 32}.mca"
            if not restart:
                assert not target.exists(), 'City region existed before the read-only query'
            result[stage + '_locate_seconds'] = round(locate(server, start), 3)
            if not restart:
                assert not target.exists(), 'Read-only locate published the city region'
            sample(server, legacy.wait_chunk, start, frame, chest, restart)
            if args.explorer_map_pack:
                if not restart:
                    explorer_map(server, start, claimed=False)
                explorer_map(server, start, claimed=True)
                server.command('save-all flush', 'Saved the game')
        finally:
            server.stop()
        end = world / 'DIM1'
        chunk = read_chunk(end / 'region', (start['ChunkX'], start['ChunkZ']))
        assert chunk['structures']['starts'][CITY] == expected, 'Saved city start differed'
        chunk = read_chunk(end / 'region', frame_chunk)
        key = (start['ChunkX'] & 0xffffffff) | ((start['ChunkZ'] & 0xffffffff) << 32)
        assert any((value & 0xffffffffffffffff) == key for value in chunk['structures']['References'][CITY])
        entities = read_chunk(end / 'entities', frame_chunk)
        assert any(entity['UUID'] == frame['UUID'] and entity['Item']['id'] == 'minecraft:elytra'
                   for entity in entities['Entities'] if entity['id'] == 'minecraft:item_frame'), 'Saved elytra frame differed'
    result['status'] = 'pass'
    (directory / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
