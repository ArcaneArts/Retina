"""Compare bounded cold/restart Bukkit biome-query workloads with real native parity."""
import argparse
import gzip
import hashlib
import importlib.util
import json
import platform
import shutil
import statistics
import subprocess
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('server_suite', Path(__file__).with_name('bukkit-server-test.py'))
suite = importlib.util.module_from_spec(spec); spec.loader.exec_module(suite)


def run(args, output, restart=False):
    server = suite.Server(args, output, restart)
    result = {'phase': 'restart' if restart else 'cold'}
    try:
        server.wait('Done (', timeout=300)
        result['ready_seconds'] = time.monotonic() - server.started
        result['generation_seconds'] = []
        for dimension, blocks, count in [('overworld', '-16 -16 31 31', 9), ('the_nether', '-16 -16 31 31', 9), ('the_end', '-64 -64 64 64', 81)]:
            elapsed = server.command(f'execute in minecraft:{dimension} run forceload add {blocks}', f'Marked {count} chunks in minecraft:{dimension}', 300) if not restart else 0
            result['generation_seconds'].append(elapsed)
        if not restart:
            for dimension, at, block in [('overworld', '1 100 1', 'diamond_block'), ('the_nether', '-1 130 -1', 'emerald_block'), ('the_end', '1 100 1', 'gold_block')]:
                server.command(f'execute in minecraft:{dimension} run setblock {at} minecraft:{block}', 'Changed the block at')
        server.command('retinaprobe', 'RETINA_PROBE_PASS ')
        server.command('save-all flush', 'Saved the game')
    finally: server.stop()
    assert server.process.returncode == 0, server.log
    result['rss_kib'] = server.rss_kib
    result['biome_stats'] = [line for line in server.output.splitlines() if 'RETINA_BIOME_STATS' in line]
    result['definition_sha256'] = suite.digest(output / 'world/retina.dat')
    capture = output.parent / ('restart-chunks.bin.gz' if restart else 'cold-chunks.bin.gz')
    shutil.copy2(output / 'plugins/RetinaProbe/chunks.bin.gz', capture)
    result['snapshot_sha256'] = hashlib.sha256(gzip.decompress(capture.read_bytes())).hexdigest()
    result['cache_bytes'] = sum(path.stat().st_size for path in (output / 'world/retina-cache').glob('*.dat'))
    result['parity'] = suite.check_snapshot_pair(args, output, output.parent / ('restart-parity' if restart else 'cold-parity'))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True); parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, required=True); parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True); parser.add_argument('--iterations', type=int, default=2)
    parser.add_argument('--disk-cache', type=int, default=16384, help='Retained sparse biome tiles per dimension; 0 disables persistence.')
    parser.add_argument('--label', required=True)
    args = parser.parse_args(); args.jvm_args = ['-Dretina.qa.biomeStats=true']; args.output.mkdir(parents=True)
    report = {'label': args.label, 'plugin_sha256': suite.digest(args.plugin), 'server_sha256': suite.digest(args.server),
              'seed': suite.SEED, 'platform': platform.platform(),
              'java': subprocess.run([str(args.java), '-version'], capture_output=True, text=True, check=True).stderr.strip(),
              'jvm_args': ['-XX:ActiveProcessorCount=4', '-Xmx2G'] + args.jvm_args,
              'cold_jvm': True, 'warmups': 0, 'disk_cache_tiles': args.disk_cache, 'runs': [], 'status': 'failed'}
    try:
        for iteration in range(args.iterations):
            directory = args.output / str(iteration) / 'server'; suite.prepare(args, directory)
            with (directory / 'plugins/Retina/config.yml').open('a') as config: config.write(f'biome-disk-cache: {args.disk_cache}\n')
            # A deliberately cold JVM/workload has no warmup; restart is measured separately.
            (directory / 'spigot.yml').write_text('settings:\n  timeout-time: 300\n  restart-on-crash: false\n')
            for phase in ['cold-parity', 'restart-parity']: (directory.parent / phase).mkdir()
            cold = run(args, directory); warm = run(args, directory, True)
            assert cold['definition_sha256'] == warm['definition_sha256']
            assert cold['snapshot_sha256'] == warm['snapshot_sha256'], 'Saved/reloaded public terrain changed'
            report['runs'].append({'cold': cold, 'restart': warm})
            (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
            print(json.dumps({'iteration': iteration, 'cold_overworld_seconds': cold['generation_seconds'][0], 'restart_seconds': warm['ready_seconds']}), flush=True)
        for field, values in [('cold_overworld', [r['cold']['generation_seconds'][0] for r in report['runs']]), ('restart', [r['restart']['ready_seconds'] for r in report['runs']])]:
            ordered = sorted(values); report[field] = {'median_seconds': statistics.median(values), 'p95_seconds': ordered[-1], 'samples': values}
        report['status'] = 'pass'
    finally: (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'report': str(args.output / 'report.json')}), flush=True)


if __name__ == '__main__': main()
