"""Qualify Chunky on an installed Retina 1.20.1 server and verify its MCA output after restart."""
import argparse
import json
import os
import shutil
import struct
import time
import zlib
from argparse import Namespace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

from benchmark_support import mca_records

AHEAD = "Retina is generating Chunky's selection ahead to MCA: up to 2 regions in flight"
STOPPED = 'Retina Chunky region pre-generation stopped:'
FINISHED = 'Task finished for'
COMPLETE = 'Retina Chunky region pre-generation complete: 1 region requests finished'
X, Z = -14072, 14088


def select(server, x, z, radius):
    server.command('chunky world minecraft:overworld', 'World changed to')
    server.command('chunky shape square', 'Shape changed to')
    server.command(f'chunky center {x} {z}', 'Center changed to')
    server.command(f'chunky radius {radius}', 'Radius changed to')


def exercise_cancellation(server):
    select(server, -5120, 5120, 960)
    server.refresh()
    offset = len(server.output)
    server.command('chunky start', 'Task started in')
    server.wait(AHEAD, offset=offset)
    server.command('chunky pause', 'Task paused for')
    server.wait(STOPPED, offset=offset)
    server.wait('Task stopped for', timeout=120, offset=offset)

    server.refresh()
    offset = len(server.output)
    server.command('chunky continue', 'Task continuing for')
    server.wait(AHEAD, offset=offset)
    server.command('chunky cancel', 'Cancelled tasks cannot be continued')
    server.command('chunky confirm', 'Cancelling all tasks.')
    server.wait(STOPPED, offset=offset)
    server.wait('Task stopped for', timeout=120, offset=offset)


def exercise_small_selection(server):
    select(server, X, Z, 16)
    server.refresh()
    offset = len(server.output)
    started = time.monotonic()
    server.command('chunky start', 'Task started in')
    server.wait(AHEAD, offset=offset)
    finished = server.wait(FINISHED, offset=offset)
    server.wait(COMPLETE, offset=offset)
    assert 'Processed: 9 chunks (100.00%)' in server.output[offset:], server.output[offset:]
    return round(finished - started, 3), offset


def verify_edit(server, wait_chunk, first):
    if first:
        server.command(f'forceload add {X} {Z}', 'force loaded')
    wait_chunk(server, 'execute in minecraft:overworld', X // 16, Z // 16)
    if first:
        server.command(f'setblock {X} 110 {Z} minecraft:diamond_block', 'Changed the block')
    server.command(f'execute if block {X} 110 {Z} minecraft:diamond_block run say RETINA_CHUNKY_EDIT_OK',
                   'RETINA_CHUNKY_EDIT_OK')
    server.command('save-all flush', 'Saved the game')


def retain_edited_record(target):
    slot = ((X // 16) & 31) + (((Z // 16) & 31) * 32)
    payload = zlib.compress(mca_records(target)[slot])
    record = struct.pack('>IB', len(payload) + 1, 2) + payload
    sectors = (len(record) + 4095) // 4096
    header = bytearray(8192)
    struct.pack_into('>I', header, slot * 4, (2 << 8) | sectors)
    target.write_bytes(header + record.ljust(sectors * 4096, b'\0'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('java', 'mod', 'chunky', 'output'):
        parser.add_argument('--' + option, required=True, type=Path)
    loader = parser.add_mutually_exclusive_group(required=True)
    loader.add_argument('--server', type=Path, help='Fabric server launcher JAR')
    loader.add_argument('--forge-install', type=Path, help='Directory produced by the Forge installer')
    parser.add_argument('--fabric-api', type=Path, help='Fabric API required by Chunky on Fabric')
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

    directory = args.output.resolve()
    legacy.prepare(args, directory)
    shutil.copy2(args.chunky, directory / 'mods/Chunky.jar')
    if args.fabric_api:
        shutil.copy2(args.fabric_api, directory / 'mods/FabricAPI.jar')
    (directory / 'config').mkdir()
    (directory / 'config/retina.toml').write_text('mca_parallelism=2\n')
    temporary = directory / 'tmp'
    temporary.mkdir()
    launch = Namespace(java=args.java.resolve(), launch_args=command,
                       jvm_args=['-Dretina.qa=true', f'-Djava.io.tmpdir={temporary}'])
    world = directory / 'world'
    region_x, region_z = X // 512, Z // 512
    target = world / f'region/r.{region_x}.{region_z}.mca'
    prepare = f'QA_EVT retina_1201_prepared minecraft:overworld {region_x},{region_z} '
    publish = f'QA_EVT retina_1201_published minecraft:overworld {region_x},{region_z} generated=1024 preserved=0'

    server = support.Server(launch, directory)
    try:
        server.wait('Done (')
        exercise_cancellation(server)
        assert not target.exists()
        first_seconds, offset = exercise_small_selection(server)
        server.wait(prepare, offset=offset)
        server.wait(publish, offset=offset)
        verify_edit(server, legacy.wait_chunk, first=True)
        assert 'Could not start Retina' not in server.output
    finally:
        server.stop()
    assert not list(temporary.glob('retina-1201-preview-*'))
    assert len(mca_records(target)) == 1024
    digest = support.digest(world / 'retina.dat')

    server = support.Server(launch, directory, True)
    try:
        server.wait('Done (')
        assert 'from saved-file' in server.output
        assert support.digest(world / 'retina.dat') == digest
        restart_seconds, _ = exercise_small_selection(server)
        verify_edit(server, legacy.wait_chunk, first=False)
        assert prepare not in server.output and publish not in server.output
        server.command(f'forceload remove {X} {Z}', 'Unmarked chunk')
        server.command('save-all flush', 'Saved the game')
    finally:
        server.stop()
    assert not list(temporary.glob('retina-1201-preview-*'))
    assert len(mca_records(target)) == 1024
    (directory / 'restart.log').rename(directory / 'full-reopen.log')
    retain_edited_record(target)
    assert len(mca_records(target)) == 1
    server = support.Server(launch, directory, True)
    try:
        server.wait('Done (')
        assert len(mca_records(target)) == 1
        partial_seconds, offset = exercise_small_selection(server)
        server.wait(f'QA_EVT retina_1201_published minecraft:overworld {region_x},{region_z} generated=1023 preserved=1',
                    offset=offset)
        server.command(f'forceload add {X} {Z}', 'force loaded')
        verify_edit(server, legacy.wait_chunk, first=False)
    finally:
        server.stop()
    assert not list(temporary.glob('retina-1201-preview-*'))
    assert len(mca_records(target)) == 1024
    result = {'status': 'pass', 'region': [region_x, region_z], 'records': 1024, 'selected_chunks': 9,
              'first_seconds': first_seconds, 'restart_seconds': restart_seconds, 'partial_seconds': partial_seconds}
    (directory / 'chunky.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
