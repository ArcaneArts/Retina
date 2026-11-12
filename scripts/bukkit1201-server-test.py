"""Validate the installable Bukkit 1.20.1 plugin on a disposable Java 17 server."""
import argparse
import importlib.util
import json
import os
import platform
import shutil
import time
from pathlib import Path
from zipfile import ZipFile


def sibling(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def native_library(args, output):
    system = {'Darwin': 'macos', 'Linux': 'linux', 'Windows': 'windows'}[platform.system()]
    arch = {'arm64': 'aarch64', 'amd64': 'x86_64'}.get(platform.machine().lower(), platform.machine().lower())
    name = {'macos': 'libretina_worldgen.dylib', 'linux': 'libretina_worldgen.so', 'windows': 'retina_worldgen.dll'}[system]
    library = output / name
    with ZipFile(args.plugin) as archive:
        library.write_bytes(archive.read(f'natives/{system}-{arch}/{name}'))
        assert 'catalogs/1.20.1/retina-catalog.dat' in archive.namelist()
        assert 'catalogs/26.3/retina-catalog.dat' not in archive.namelist()
    return library


def saved_metadata_entity(directory, seed):
    from benchmark_support import decode_nbt, mca_records

    world = directory / 'retina_metadata_scene'
    chunk = next(iter(decode_nbt(mca_records(world / 'region/r.0.0.mca')[0]).values()))
    entities = next(iter(decode_nbt(mca_records(world / 'entities/r.0.0.mca')[0]).values()))
    assert chunk['DataVersion'] == entities['DataVersion'] == 3465
    blocks = {tag['x']: tag for tag in chunk['block_entities']}
    assert blocks[2]['ExitPortal'] == {'X': 8, 'Y': 80, 'Z': 8}
    item = blocks[4]['Items'][0]
    assert (item['Slot'], item['id'], item['Count']) == (0, 'minecraft:diamond', 7)
    assert item['tag']['retina_fixture']['exact_seed'] == (1 << 63) - 17
    assert json.loads(item['tag']['display']['Name']) == {'text': 'Retina fixture'}
    assert blocks[6]['LootTableSeed'] == seed
    assert blocks[10]['SpawnData']['entity']['id'] == 'minecraft:pig'
    stands = [tag for tag in entities['Entities'] if tag['id'] == 'minecraft:armor_stand']
    crystals = [tag for tag in entities['Entities'] if tag['id'] == 'minecraft:end_crystal']
    assert len(stands) == 1
    assert len(crystals) == 1
    crystal = crystals[0]
    assert crystal['BeamTarget'] == {'X': -8, 'Y': 95, 'Z': 12}
    assert crystal['Invulnerable'] == 1 and crystal['ShowBottom'] == 0
    return stands[0]['UUID'], crystal['UUID']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'server', 'plugin', 'probe', 'imported', 'output'):
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--backend', choices=('cpu', 'metal', 'vulkan'), default='cpu')
    parser.add_argument('--startup-timeout', type=float, default=240, help='Seconds allowed for server startup')
    args = parser.parse_args()
    support = sibling('bukkit-server-test')
    probe = sibling('bukkit-probe')
    os.environ['RETINA_GPU_BACKEND'] = args.backend
    args.jvm_args = [f'-Dretina.gpuBackend={args.backend}']
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    directory = output / 'server'
    support.prepare(args, directory)
    definition = directory / 'world/retina.dat'
    end_storage = directory / 'world_the_end/DIM1'
    imported = directory / 'retina_probe_imported/retina.dat'
    imported.parent.mkdir()
    shutil.copy2(args.imported, imported)
    original_import = support.digest(imported)
    snapshots = directory / 'plugins/RetinaProbe/chunks.bin.gz'
    report = {'backend': args.backend}
    server = support.Server(args, directory)
    try:
        server.wait('Done (', timeout=args.startup_timeout)
        report['first_start_seconds'] = time.monotonic() - server.started
        assert {p for p in directory.glob('*/retina.dat')} == {definition, imported}
        original = support.digest(definition)
        server.command('retinaprobe', 'RETINA_PROBE_PASS ')
        shutil.copy2(snapshots, output / 'first.bin.gz')
        server.command('retinaprobe imported 12345', 'RETINA_PROBE_IMPORTED_PASS')
        server.command('retinaprobe metadata', 'RETINA_PROBE_METADATA_ENTITIES_PASS')
        for dimension, position, material in (
            ('overworld', '1 100 1', 'diamond_block'),
            ('the_nether', '-1 130 -1', 'emerald_block'),
            ('the_end', '1 100 1', 'gold_block'),
        ):
            server.command(f'execute in minecraft:{dimension} run setblock {position} minecraft:{material}',
                           'Changed the block at')
        server.command('save-all flush', 'Saved the game')
    finally:
        server.stop()
    metadata_entity = saved_metadata_entity(directory, support.SEED)
    first_crystals = support.read_saved_end_crystals(end_storage)

    server = support.Server(args, directory, True)
    try:
        server.wait('Done (', timeout=args.startup_timeout)
        report['restart_seconds'] = time.monotonic() - server.started
        server.command('retinaprobe', 'RETINA_PROBE_PASS ')
        server.command('retinaprobe imported 12345', 'RETINA_PROBE_IMPORTED_PASS')
        server.command('retinaprobe metadata', 'RETINA_PROBE_METADATA_ENTITIES_PASS')
        server.command('save-all flush', 'Saved the game')
    finally:
        server.stop()
    assert support.digest(definition) == original
    assert support.digest(imported) == original_import
    assert saved_metadata_entity(directory, support.SEED) == metadata_entity

    library = native_library(args, output)
    crystals = support.read_saved_end_crystals(end_storage)
    assert {tuple(tag['UUID']) for tag in crystals} == {tuple(tag['UUID']) for tag in first_crystals}
    support.compare_saved_crystals(crystals, definition, library, 'BeamTarget')
    for stage, snapshot in [('first', output / 'first.bin.gz'), ('restart', snapshots)]:
        results = probe.compare(snapshot, definition, library, expect_test_edits=stage == 'restart', legacy_crystal_fire=True)
        (output / f'parity-{stage}.json').write_text(json.dumps(results, indent=2) + '\n')
        assert not any(r['block_differences'] or r['biome_differences'] for r in results), results
        report[stage] = {
            'chunks': len(results),
            'blocks': sum(r['blocks'] for r in results),
            'quart_biomes': sum(r['quart_biomes'] for r in results),
            'player_edits': sum(r['player_edits'] for r in results),
            'crystal_tick_updates': sum(r['crystal_tick_updates'] for r in results),
        }
    assert report['restart']['player_edits'] == 3
    report.update(status='pass', definition_sha256=original, native_sha256=support.digest(library),
                  imported_datapack=True, metadata_restart=True, persisted_end_crystals=len(crystals))
    (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
