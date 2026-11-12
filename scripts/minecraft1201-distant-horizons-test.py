"""Exercise the installed DH pregen command without opening a Minecraft client."""
import argparse
import json
import os
import re
import shutil
import sqlite3
import struct
import time
from argparse import Namespace
from hashlib import sha256
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import mca_records

DIMENSION = 'minecraft:the_end'
DETACHED = re.compile(rf'QA_EVT retina_1201_detached_read {DIMENSION} (-?\d+),(-?\d+)')
PREPARED = re.compile(rf'QA_EVT retina_1201_prepared {DIMENSION} (-?\d+),(-?\d+) ')


def lod_records(world):
    database, = (world / 'DIM1').rglob('DistantHorizons.sqlite')
    with sqlite3.connect(f'{database.as_uri()}?mode=ro', uri=True) as db:
        rows = db.execute('SELECT PosX, PosZ, Data, ColumnGenerationStep, Mapping FROM FullData '
                          'WHERE DetailLevel = 0 AND length(Data) > 0 ORDER BY PosX, PosZ').fetchall()
    assert rows, 'DH did not persist its detailed End LOD data'
    return {(x, z): (data, stage, mapping) for x, z, data, stage, mapping in rows}


def lod_evidence(rows):
    digest = sha256()
    for (x, z), fields in rows.items():
        digest.update(struct.pack('>ii', x, z))
        for payload in fields:
            digest.update(struct.pack('>I', len(payload)))
            digest.update(payload)
    return {'lod_sections': len(rows), 'lod_sha256': digest.hexdigest()}


def verify_chunky_handoff(server, x, z):
    server.command(f'chunky world {DIMENSION}', 'World changed to')
    server.command('chunky shape square', 'Shape changed to')
    server.command(f'chunky center {x} {z}', 'Center changed to')
    server.command('chunky radius 48', 'Radius changed to')
    offset = len(server.output)
    server.command('chunky start', 'Task started in')
    server.wait('Chunky running. Disabling DH world gen', timeout=30, offset=offset)
    server.wait('Task finished for', timeout=60, offset=offset)
    server.wait('Chunky no longer running. Re-enabling DH world gen', timeout=30, offset=offset)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'dh', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--fabric-api', type=Path, help='Fabric API used by the tested DH build')
    parser.add_argument('--chunky', type=Path, help='Also install the matching Chunky JAR')
    parser.add_argument('--check-chunky', action='store_true', help='Verify DH 3.3.3 pauses and resumes around Chunky')
    parser.add_argument('--backend', default='metal', choices=('cpu', 'metal', 'vulkan'))
    parser.add_argument('--reference-world', type=Path, help='Compare detailed DH records with a baseline world')
    parser.add_argument('--baseline', action='store_true', help='Measure the older per-chunk implementation')
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

    directory = args.output.resolve()
    legacy.prepare(args, directory)
    for jar in (args.dh, args.fabric_api, args.chunky):
        if jar is not None:
            shutil.copy2(jar, directory / 'mods')
    (directory / 'config').mkdir()
    (directory / 'config/retina.toml').write_text('mca_parallelism=8\n')
    (directory / 'config/DistantHorizons.toml').write_text(
        '[common.worldGenerator]\ndistantGeneratorMode = "FEATURES"\n'
        '[client.advanced.autoUpdater]\nenableAutoUpdater = false\n')
    temporary = directory / 'tmp'
    temporary.mkdir()
    launch = Namespace(java=args.java.resolve(), launch_args=command,
                       jvm_args=['-Dretina.qa=true', f'-Djava.io.tmpdir={temporary}'])
    world = directory / 'world'
    regions = world / 'DIM1' / 'region'
    server = support.Server(launch, directory)
    try:
        server.wait('Done (')
        initial = set(regions.glob('*.mca'))
        started = time.monotonic()
        server.command(f'dh pregen start {DIMENSION} -14072 14088 32', 'Starting pregen.', timeout=20)
        done = server.wait('Pregen is complete', timeout=180)
        assert set(regions.glob('*.mca')) == initial, 'Detached DH generation published world MCA data'
        assert 'Unsafe MultiThreading' not in server.output
        assert 'Unexpected error during world gen' not in server.output, server.output[-6000:]
        samples = DETACHED.findall(server.output)
        result = {'dh_seconds': round(done - started, 3), 'detached_regions': len(set(samples))}
        if not args.baseline:
            assert samples and all(int(x) < 0 and int(z) > 0 for x, z in samples)
            assert set(samples) <= set(PREPARED.findall(server.output))
            retained = [(int(x), int(z)) for x, z in samples
                        if any(temporary.glob(f'retina-1201-preview-*/r.{x}.{z}.mca'))]
            assert retained, 'DH did not leave a reusable preview'
            rx, rz = retained[-1]
            target = regions / f'r.{rx}.{rz}.mca'
            assert not target.exists()
            x, z = (rx * 32 + 16) * 16, (rz * 32 + 16) * 16
            offset = len(server.output)
            server.command(f'execute in {DIMENSION} run forceload add {x} {z}', 'force loaded')
            server.wait(f'QA_EVT retina_1201_preview_reused {DIMENSION} {rx},{rz} foreground=true', offset=offset)
            server.wait(f'QA_EVT retina_1201_published {DIMENSION} {rx},{rz} generated=1024 preserved=0', offset=offset)
            legacy.wait_chunk(server, f'execute in {DIMENSION}', x // 16, z // 16)
            assert (str(rx), str(rz)) not in PREPARED.findall(server.output[offset:])
            assert len(mca_records(target)) == 1024
            result['promoted_region'] = [rx, rz]
            if args.check_chunky:
                verify_chunky_handoff(server, x, z)
                result['chunky_handoff'] = True
    finally:
        server.stop()
    assert not list(temporary.glob('retina-1201-preview-*'))
    rows = lod_records(world)
    if args.reference_world:
        reference = lod_records(args.reference_world.resolve())
        # Foreground Minecraft loads can add their own detailed LOD records.
        assert all(rows.get(position) == record for position, record in reference.items()), 'Baseline End LOD records differed'
        result['matching_reference_sections'] = len(reference)
    result.update(lod_evidence(rows))
    result['status'] = 'pass'
    (directory / 'distant-horizons.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
