"""Disposable dedicated-server acceptance for the actual installable Bukkit JAR."""
import argparse
import base64
import ctypes as c
import hashlib
import importlib.util
import json
import math
import os
import platform
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from zipfile import ZipFile

SEED = -(1 << 63) + 41


class Server:
    def __init__(self, args, directory, materializer_disabled=False):
        self.directory = directory
        self.log = directory / ('restart.log' if materializer_disabled else 'console.log')
        self.output = ''
        self.world_locations = {}
        self.rss_kib = 0
        self.started = time.monotonic()
        command = [str(args.java), '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true',
                   '-Dterminal.jline=false', '-Dterminal.ansi=false', '-XX:ActiveProcessorCount=4', '-Xmx2G']
        if materializer_disabled: command += ['-Dretina.qa.catalog.failMaterializer=true']
        command += getattr(args, 'jvm_args', [])
        command += ['-jar', str(args.server.resolve()), '--nogui']
        self.writer = self.log.open('w')
        self.process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=self.writer,
                                        stderr=subprocess.STDOUT, text=True)
        self.reader = self.log.open()

    def refresh(self):
        self.output += self.reader.read()
        for match in re.finditer(r'RETINA_PROBE_WORLD (\S+) (\S+) (\S+)\r?\n', self.output):
            name, folder, key = (base64.b64decode(value).decode('utf-8') for value in match.groups())
            self.world_locations[name] = {'folder': folder, 'key': key}
        try:
            result = subprocess.run(['ps', '-o', 'rss=', '-p', str(self.process.pid)], text=True,
                                    capture_output=True, check=False)
            if result.stdout.strip(): self.rss_kib = max(self.rss_kib, int(result.stdout.strip()))
        except (FileNotFoundError, ValueError): pass

    def wait(self, marker, timeout=240, offset=0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.refresh()
            if marker in self.output[offset:]: return time.monotonic()
            if any(message in self.output[offset:] for message in ('Bukkit snapshot probe failed', 'Metadata fixture differs:', 'Metadata fixture delivery failed', 'Retina generation callback failed')):
                raise RuntimeError(f'Actual probe operation failed; see {self.log}\n' + self.output[-5000:])
            if self.process.poll() is not None: raise RuntimeError(f'Server exited before {marker!r}; see {self.log}\n' + self.output[-5000:])
            time.sleep(.1)
        raise TimeoutError(f'Timed out waiting for {marker!r}; see {self.log}\n' + self.output[-5000:])

    def command(self, command, marker, timeout=240):
        self.refresh(); offset = len(self.output); started = time.monotonic()
        self.process.stdin.write(command + '\n'); self.process.stdin.flush()
        self.wait(marker, timeout, offset)
        return time.monotonic() - started

    def stop(self):
        if self.process.poll() is None:
            try: self.process.stdin.write('stop\n'); self.process.stdin.flush()
            except (BrokenPipeError, OSError): pass
            try: self.process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try: self.process.wait(timeout=15)
                except subprocess.TimeoutExpired: self.process.kill(); self.process.wait(timeout=15)
        self.refresh(); self.reader.close(); self.writer.close()
        self.process.stdin.close()
        (self.directory / 'world-locations.json').write_text(json.dumps(self.world_locations, indent=2) + '\n')


def prepare(args, directory, seed=SEED, variant='bukkit-api'):
    plugins = directory / 'plugins'; plugins.mkdir(parents=True)
    shutil.copy2(args.plugin, plugins / 'Retina.jar'); shutil.copy2(args.probe, plugins / 'RetinaProbe.jar')
    (directory / 'eula.txt').write_text('eula=true\n')
    (directory / 'server.properties').write_text(f'level-name=world\nlevel-seed={seed}\nserver-ip=127.0.0.1\nserver-port=0\n'
                                               'online-mode=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-tick-time=0\n')
    (directory / 'bukkit.yml').write_text('settings:\n  allow-end: true\nworlds:\n  world:\n    generator: Retina\n'
                                         '  world_nether:\n    generator: Retina\n  world_the_end:\n    generator: Retina\n')
    # Cold vanilla structure-ring searches can exceed Spigot's 60-second default.
    # Operations remain bounded by this driver's timeout and fail on actual callback errors.
    (directory / 'spigot.yml').write_text('settings:\n  timeout-time: 300\n  restart-on-crash: false\n')
    config = plugins / 'Retina'; config.mkdir()
    (config / 'config.yml').write_text(f'catalog-variant: {variant}\nchunk-cache: 64\nbiome-cache: 256\nconcurrent-loaders: 4\nallow-metadata-loss: false\nworlds: []\n')


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def check_snapshot_pair(args, directory, output):
    spec = importlib.util.spec_from_file_location('bukkit_probe', Path(__file__).with_name('bukkit-probe.py'))
    probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)
    system = 'macos' if platform.system() == 'Darwin' else 'windows' if os.name == 'nt' else 'linux'
    machine = platform.machine().lower(); arch = {'arm64': 'aarch64', 'amd64': 'x86_64'}.get(machine, machine)
    library_name = 'libretina_worldgen.dylib' if system == 'macos' else 'retina_worldgen.dll' if system == 'windows' else 'libretina_worldgen.so'
    library = output / library_name
    with ZipFile(args.plugin) as archive: library.write_bytes(archive.read(f'natives/{system}-{arch}/{library_name}'))
    results = probe.compare(directory / 'plugins/RetinaProbe/chunks.bin.gz', directory / 'world/retina.dat', library, True)
    (output / 'parity.json').write_text(json.dumps(results, indent=2) + '\n')
    if any(r['block_differences'] or r['biome_differences'] for r in results): raise AssertionError(output / 'parity.json')
    return {'chunks': len(results), 'blocks': sum(r['blocks'] for r in results), 'quart_biomes': sum(r['quart_biomes'] for r in results),
            'player_edits': sum(r['player_edits'] for r in results), 'native_sha256': digest(library),
            'persisted_end_crystals': check_persisted_crystals(directory, output, library)}


def check_persisted_crystals(directory, output, library):
    from benchmark_support import decode_nbt, mca_records
    from saved_definition import SavedDefinition, check
    actual = []
    for region in world_storage(directory, 'world_the_end').glob('entities/r.*.mca'):
        for payload in mca_records(region).values():
            actual.extend(tag for tag in next(iter(decode_nbt(payload).values())).get('Entities', []) if tag['id'] == 'minecraft:end_crystal')
    assert len(actual) == 10, f'Expected all ten generated End pillar crystals, got {len(actual)}'
    native = c.CDLL(str(library))
    native.retina_world_dimension_chunk.argtypes = [c.c_uint64, c.c_char_p, c.c_uint64, c.c_int32, c.c_int32,
                                                    c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
    with SavedDefinition(native, directory / 'world/retina.dat', 'minecraft:the_end') as world:
        for entity in actual:
            x, _, z = entity['Pos']; pointer, length = c.c_void_p(), c.c_uint64()
            check(native, native.retina_world_dimension_chunk(world.handle, world.key, len(world.key), math.floor(x / 16), math.floor(z / 16), c.byref(pointer), c.byref(length)))
            try: root = next(iter(decode_nbt(c.string_at(pointer, length.value)).values()))
            finally: native.retina_free_bytes(pointer, length)
            expected = next(tag for tag in root['entities'] if tag['id'] == entity['id'] and tag['Pos'] == entity['Pos'])
            assert all(abs(math.remainder(a - b, 360)) < .0001 for a, b in zip(expected['Rotation'], entity['Rotation']))
            for key, default in [('ShowBottom', 1), ('Invulnerable', 0), ('beam_target', None)]:
                assert entity.get(key, default) == expected.get(key, default), key
    assert len({tuple(tag['UUID']) for tag in actual}) == 10
    result = {'status': 'pass', 'crystals': 10, 'fields': ['id', 'Pos', 'Rotation', 'ShowBottom', 'Invulnerable', 'beam_target'],
              'equivalent_yaw_normalization': True, 'runtime_uuids_owned_by_server': True}
    (output / 'crystal-parity.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def check_persisted_metadata(directory, output, stage):
    from benchmark_support import decode_nbt, mca_records
    world = world_storage(directory, 'retina_metadata_scene')
    region = world / 'region/r.0.0.mca'
    chunk = next(iter(decode_nbt(mca_records(region)[0]).values()))
    blocks = {(tag['x'], tag['y'], tag['z']): tag for tag in chunk['block_entities']}
    gateway = blocks[2, 65, 2]
    assert gateway['Age'] >= 2401 and gateway['ExactTeleport'] == 1 and gateway['exit_portal'] == [8, 80, 8]
    item = blocks[4, 65, 2]['Items'][0]
    assert (item['id'], item['count'], item['Slot']) == ('minecraft:diamond', 7, 0)
    assert item['components']['minecraft:custom_name'] == 'Retina fixture'
    assert item['components']['minecraft:custom_data']['exact_seed'] == (1 << 63) - 17
    loot = blocks[6, 65, 2]
    assert loot['LootTable'] == 'minecraft:chests/simple_dungeon' and loot['LootTableSeed'] == SEED
    spawner = blocks[10, 65, 2]
    for field, expected in {'Delay': 77, 'MinSpawnDelay': 60, 'MaxSpawnDelay': 600, 'SpawnCount': 2,
                            'MaxNearbyEntities': 5, 'RequiredPlayerRange': 12, 'SpawnRange': 3}.items():
        assert spawner[field] == expected, (field, spawner[field])
    assert spawner['SpawnData']['entity']['id'] == 'minecraft:pig'
    potential = spawner['SpawnPotentials'][0]
    assert potential['weight'] == 3 and potential['data']['entity']['id'] == 'minecraft:pig'
    assert potential['data']['custom_spawn_rules'] == {'block_light_limit': [2, 12], 'sky_light_limit': [3, 13]}
    entities = world / 'entities/r.0.0.mca'
    chunk_entities = next(iter(decode_nbt(mca_records(entities)[0]).values()))['Entities']
    stand = next(tag for tag in chunk_entities if tag['id'] == 'minecraft:armor_stand')
    assert stand['Pos'] == [8.5, 66., 8.5] and stand['Rotation'] == [33., 0.]
    assert all(stand[key] == 1 for key in ['Marker', 'Small', 'Invisible', 'Invulnerable', 'NoGravity'])
    result = {'status': 'pass', 'stage': stage, 'categories': ['gateway', 'inventory-components', 'lazy-loot', 'spawner', 'entity'],
              'server_spawner_identity': spawner['id'], 'region_sha256': digest(region), 'entities_sha256': digest(entities)}
    (output / f'persisted-metadata-{stage}.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def world_storage(directory, name):
    # The observer records actual public World locations and dimension keys.
    # Paper 26.3 shares a level folder; CraftBukkit also supports separate folders.
    locations = json.loads((directory / 'world-locations.json').read_text())
    location = locations[name]; root = Path(location['folder'])
    if (root / 'region').is_dir(): return root
    namespace, key = location['key'].split(':', 1)
    dimension = root / 'dimensions' / namespace / key
    if (dimension / 'region').is_dir(): return dimension
    regions = list(root.glob('**/region'))
    if len(regions) == 1: return regions[0].parent
    raise ValueError(f'Cannot identify saved region directory for observed world {name}: {location}')


def acceptance(args, output):
    directory = output / 'acceptance'; prepare(args, directory)
    server = Server(args, directory)
    timing = {}
    try:
        server.wait('Done ('); timing['first_start_seconds'] = time.monotonic() - server.started
        definition = directory / 'world/retina.dat'; original = digest(definition)
        assert list(directory.glob('*/retina.dat')) == [definition], 'Standard dimensions did not share one complete definition'
        timing['commands'] = []
        for dimension, blocks, count in [('overworld', '-16 -16 31 31', 9), ('the_nether', '-16 -16 31 31', 9), ('the_end', '-64 -64 64 64', 81)]:
            seconds = server.command(f'execute in minecraft:{dimension} run forceload add {blocks}', f'Marked {count} chunks in minecraft:{dimension}')
            timing['commands'].append({'dimension': dimension, 'requested_full_chunks': count, 'seconds': seconds,
                                       'requested_full_chunks_per_second': count / seconds})
        server.command('retinaprobe metadata-scene', 'RETINA_PROBE_METADATA_ENTITIES_PASS')
        server.command('retinaprobe create-end', 'RETINA_PROBE_END_PASS')
        server.command('retinaprobe create-nether', 'RETINA_PROBE_NETHER_PASS')
        imported = directory / 'retina_probe_imported'; imported.mkdir(exist_ok=True)
        shutil.copy2(args.mod_fixtures / 'cropped_api/retina.dat', imported / 'retina.dat')
        # The original mod fixture's full-width seed is part of the reference contract.
        server.command(f'retinaprobe imported {-(1 << 63) + 23}', 'RETINA_PROBE_IMPORTED_PASS')
        geometry = directory / 'retina_probe_bad_geometry'; geometry.mkdir(exist_ok=True); shutil.copy2(definition, geometry / 'retina.dat')
        server.command('retinaprobe bad-geometry', 'RETINA_PROBE_GEOMETRY_FAILURE_PASS')
        assert digest(geometry / 'retina.dat') == original
        server.command('retinaprobe reopen-end', 'RETINA_PROBE_REOPEN_PASS')
        for dimension, at, block in [('overworld', '1 100 1', 'diamond_block'), ('the_nether', '-1 130 -1', 'emerald_block'), ('the_end', '1 100 1', 'gold_block')]:
            server.command(f'execute in minecraft:{dimension} run setblock {at} minecraft:{block}', 'Changed the block at')
        server.command('retinaprobe', 'RETINA_PROBE_PASS ')
        shutil.copy2(directory / 'plugins/RetinaProbe/chunks.bin.gz', output / 'before-restart.bin.gz')
        server.command('save-all flush', 'Saved the game')
        server.command('retinaprobe disable-retina', 'RETINA_PROBE_DISABLE_PASS')
    finally: server.stop()
    timing['first_persisted_metadata'] = check_persisted_metadata(directory, output, 'first')
    assert digest(definition) == original
    first_log = server.output; timing['sampled_first_rss_kib'] = server.rss_kib
    hashes = {str(p.relative_to(directory)): digest(p) for p in directory.glob('*/retina.dat')}
    server = Server(args, directory, True)
    try:
        server.wait('Done ('); timing['restart_seconds'] = time.monotonic() - server.started
        server.command('retinaprobe metadata-scene', 'RETINA_PROBE_METADATA_ENTITIES_PASS')
        server.command('retinaprobe create-end', 'RETINA_PROBE_END_PASS')
        server.command('retinaprobe create-nether', 'RETINA_PROBE_NETHER_PASS')
        server.command(f'retinaprobe imported {-(1 << 63) + 23}', 'RETINA_PROBE_IMPORTED_PASS')
        server.command('retinaprobe', 'RETINA_PROBE_PASS ')
        server.command('save-all flush', 'Saved the game')
    finally: server.stop()
    timing['restart_persisted_metadata'] = check_persisted_metadata(directory, output, 'restart')
    for name, expected in hashes.items(): assert digest(directory / name) == expected, f'Definition rewritten: {name}'
    timing['sampled_restart_rss_kib'] = server.rss_kib
    timing['definition_sha256'] = original; timing['definition_bytes'] = definition.stat().st_size
    timing['definitions_preserved'] = len(hashes)
    timing['parity'] = check_snapshot_pair(args, directory, output)
    assert 'native_override=none' in first_log and 'native_override=none' in server.output
    return timing


def failures(args, output):
    fixtures = output / 'failure-fixtures'
    subprocess.run([str(args.java), '-cp', args.fixture_classpath, 'art.arcane.retina.bukkit.qa.DefinitionFixtures',
                    str(output / 'acceptance/world/retina.dat'), str(fixtures)], check=True)
    cases = [('malformed', None, SEED, 'world definition gzip'),
             ('missing-material', fixtures / 'missing-material.dat', SEED, 'Bukkit cannot resolve saved block state'),
             ('missing-biome', fixtures / 'missing-biome.dat', SEED, 'Bukkit cannot resolve saved biome'),
             ('seed-mismatch', fixtures / 'original.dat', SEED + 1, 'differs from saved Retina seed'),
             ('unsupported-metadata', args.mod_fixtures / 'vanilla/retina.dat', -(1 << 63) + 17, 'structure starts')]
    results = []
    for name, source, seed, marker in cases:
        directory = output / ('failure-' + name); prepare(args, directory, seed)
        target = directory / 'world/retina.dat'; target.parent.mkdir()
        target.write_bytes(source.read_bytes() if source is not None else b'invalid-definition')
        before = digest(target); server = Server(args, directory, True)
        try:
            server.wait(marker, 90)
            # Observe the failed startup, including shutdown, rather than interpreting a process exit code alone.
            server.process.wait(timeout=40); server.refresh()
            assert 'Done (' not in server.output, f'Failed definition fell back to another generator: {name}'
        finally: server.stop()
        assert digest(target) == before, f'Failure replaced the supplied definition: {name}'
        results.append({'case': name, 'status': 'pass', 'definition_preserved': True})
        print(json.dumps(results[-1]), flush=True)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True); parser.add_argument('--server', required=True)
    parser.add_argument('--plugin', type=Path, required=True); parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--fixture-classpath', required=True); parser.add_argument('--mod-fixtures', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.server: parser.error('Supply -PbukkitServerJar=/absolute/path/to/server.jar to the Gradle task')
    args.server = Path(args.server); args.output.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='run-', dir=args.output)).resolve()
    report = {'server': str(args.server.resolve()), 'plugin_sha256': digest(args.plugin), 'artifacts': str(output)}
    try:
        report['acceptance'] = acceptance(args, output)
        print(json.dumps({'acceptance': 'pass', 'artifacts': str(output)}), flush=True)
        report['failures'] = failures(args, output); report['status'] = 'pass'
    except BaseException as error:
        report['status'] = 'failed'; report['error'] = repr(error)
        raise
    finally: (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': 'pass', 'report': str(output / 'report.json')}), flush=True)


if __name__ == '__main__': main()
