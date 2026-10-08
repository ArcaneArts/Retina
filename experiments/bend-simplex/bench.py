#!/usr/bin/env python3
"""Pinned Bend 2 vs production Retina simplex. All native work runs sequentially."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import shutil
import statistics
import subprocess
import tarfile
import tempfile
import time
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BUILD = ROOT / 'build/bend-experiment'
VERSION = '2.0.36'
DEFAULT_BEND = Path.home() / f'.local/share/retina-bend/{VERSION}/bin/bend'
ENV = dict(os.environ, BEND_NO_TELEMETRY='1', CARGO_BUILD_JOBS='2',
           CARGO_TARGET_DIR=str(BUILD / 'target'))
# Explicit toolchain fixes this host's CLT / linker SDK mismatch; no global changes.
XCODE = Path('/Applications/Xcode.app/Contents/Developer')
if XCODE.exists():
    ENV['CC'] = str(XCODE / 'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang')
    ENV['SDKROOT'] = str(XCODE / 'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk')


def command(args, timeout=180):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, env=ENV,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{args}\n{result.stdout}\n{result.stderr}')
    return result.stdout


def install():
    """Install official, checksum-pinned arm64 macOS release without PATH edits."""
    dest = DEFAULT_BEND.parents[1]
    url = f'https://github.com/bendlang/bend/releases/download/v{VERSION}/bend-{VERSION}-darwin-arm64.tar.gz'
    expected = '2876687ceb0aba836abc98b3f62c8fc9fb612d124ae2af6814d5ec937760878d'
    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / 'bend.tar.gz'
        urllib.request.urlretrieve(url, archive)
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected, 'Release SHA256 mismatch'
        with tarfile.open(archive) as tar:
            tar.extractall(tmp, filter='data')
        dest.mkdir(parents=True, exist_ok=True)
        for name in ('bin', 'bend2', 'guide'):
            shutil.copytree(Path(tmp) / 'bend' / name, dest / name, dirs_exist_ok=True)
    print(f'Installed {DEFAULT_BEND}', flush=True)


def generate(grain, parallel=False):
    source = (HERE / 'simplex.bend').read_text() + '\n'
    source += (HERE / 'batch.bend.in').read_text().replace('@GRAIN@', str(grain))
    if parallel:
        source = source.replace('    case Node{a, b}: U32.add(consume(a), consume(b))',
                                '    case Node{a, b}:\n      x y = consume(a) consume(b)\n      U32.add(x, y)')
    prefix = 'p' if parallel else 'g'
    path = BUILD / f'{prefix}{grain}.bend'
    path.write_text(source)
    shutil.copyfile(HERE / 'now_us.c', BUILD / 'now_us.c')
    return path


def build(bend):
    BUILD.mkdir(parents=True, exist_ok=True)
    logs = {}
    for grain in (16, 64, 256, 1024):
        for parallel in (False, True):
            source = generate(grain, parallel)
            start = time.perf_counter()
            command(['nice', '-n', '10', bend, source, '-o', source.with_suffix('')])
            logs[f'bend_{source.stem}_seconds'] = time.perf_counter() - start
            print(f'Built Bend {source.stem}', flush=True)
    source = generate(64).read_text().split('def main()')[0]
    source += (HERE / 'validate.bend.in').read_text()
    (BUILD / 'validate.bend').write_text(source)
    command(['nice', '-n', '10', bend, BUILD / 'validate.bend', '-o', BUILD / 'validate'])
    start = time.perf_counter()
    command(['nice', '-n', '10', 'cargo', 'build', '--manifest-path',
             HERE / 'rust/Cargo.toml', '--release', '-j', '2'])
    logs['rust_incremental_build_seconds'] = time.perf_counter() - start
    (BUILD / 'build-times.json').write_text(json.dumps(logs, indent=2) + '\n')


def rust_bin():
    return BUILD / 'target/release/retina-bend-simplex-bench'


def validate():
    reference = json.loads(command([rust_bin(), 1024, 0, 'dump']))
    result = {}
    for backend in ('off', 'on'):
        values = [float(v) for v in command([BUILD / 'validate', '--threads', 4,
                                            '--gpu', backend]).splitlines()]
        # Each 64-element leaf is printed in reverse, leaves are in ascending order.
        values = [v for offset in range(0, len(values), 64)
                  for v in reversed(values[offset:offset + 64])]
        assert len(values) == len(reference), (backend, len(values))
        error = max(abs(a - b) for a, b in zip(reference, values))
        assert all(math.isfinite(v) for v in values)
        assert error < 0.0001, (backend, error)
        result[backend] = {'samples': len(values), 'max_abs_error_vs_rust': error}
    return result


def bend_run(n, grain, threads, gpu, repetitions, parallel=False):
    depth = int(math.log2(n // grain))
    assert grain * 2 ** depth == n
    start = time.perf_counter()
    prefix = 'p' if parallel else 'g'
    lines = command([BUILD / f'{prefix}{grain}', depth, repetitions + 3,
                     '--threads', threads, '--gpu', 'on' if gpu else 'off']).splitlines()
    wall = (time.perf_counter() - start) * 1000
    rows = []
    for line in lines:
        materialize, total, checksum = map(int, line.split(','))
        rows.append(dict(materialize_ms=materialize / 1000, total_ms=total / 1000,
                         checksum=checksum))
    assert len(rows) == repetitions + 3
    assert len({v['checksum'] for v in rows}) == 1
    return rows[3:], {'process_wall_ms': wall, 'first_call_ms': rows[0]['total_ms']}


def native_run(n, threads, gpu, repetitions):
    start = time.perf_counter()
    rows = [json.loads(v) for v in command([rust_bin(), n, repetitions,
                                          'gpu' if gpu else 'cpu', threads]).splitlines()]
    meta = rows.pop(0)
    meta['process_wall_ms'] = (time.perf_counter() - start) * 1000
    assert meta['max_error_vs_rust'] < 0.0001, meta
    assert len(rows) == repetitions
    return rows, meta


def summary(rows):
    result = {}
    for metric in ('total_ms', 'materialize_ms', 'device_ms'):
        data = sorted(r[metric] for r in rows if r.get(metric) is not None)
        if data:
            result[metric] = dict(median=statistics.median(data), mean=statistics.mean(data),
                                  p95=data[math.ceil(0.95 * len(data)) - 1], min=data[0])
    return result


def run(bend, repetitions, rounds):
    result = dict(bend_version=command([bend, 'version']).strip(),
                  source_commit=command(['git', 'rev-parse', 'HEAD']).strip(),
                  shader_sha256=hashlib.sha256((ROOT/'native/src/simplex.wgsl').read_bytes()).hexdigest(),
                  native_output_reclamation_included=True,
                  utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                  platform=platform.platform(), timer='Bend: monotonic microseconds; Rust: Instant',
                  cpu=command(['sysctl', '-n', 'machdep.cpu.brand_string']).strip(),
                  validation=validate(), build_times=json.loads((BUILD / 'build-times.json').read_text()),
                  tuning=[], results=[])
    # Tune each output size independently; final rounds do not reuse these samples.
    configs = {}
    for n in (256, 262144, 1048576, 4194304):
        candidates = []
        for grain in (16, 64, 256, 1024):
            if grain > n:
                continue
            for parallel in (False, True):
                rows, meta = bend_run(n, grain, 4, True, repetitions, parallel)
                item = dict(n=n,grain=grain,parallel_consume=parallel,summary=summary(rows),rows=rows,metadata=meta)
                result['tuning'].append(item)
                candidates.append(item)
        best = min(candidates, key=lambda r:r['summary']['total_ms']['median'])
        configs[n] = (best['grain'], best['parallel_consume'])
        print(f'Best GPU config for {n}: {configs[n]}', flush=True)
    result['selected_configs'] = configs
    jobs = [(n, kind, threads) for n in (256, 262144, 1048576, 4194304)
            for kind, threads in [('wgsl_gpu',4), ('rust_cpu',1), ('rust_cpu',4),
                                 ('bend_gpu',4), ('bend_cpu',1), ('bend_cpu',4)]]
    samples = {(n,k,t): [] for n,k,t in jobs}
    metadata = {(n,k,t): [] for n,k,t in jobs}
    for round_number in range(rounds):
        order = jobs.copy()
        random.Random(20261007 + round_number).shuffle(order)
        for n, kind, threads in order:
            # A chunk is smaller than grain 1024; cap leaf size there.
            if kind.startswith('bend'):
                grain, parallel = configs[n]
                rows, meta = bend_run(n, grain, threads, kind == 'bend_gpu', repetitions, parallel)
            else:
                rows, meta = native_run(n, threads, kind == 'wgsl_gpu', repetitions)
            samples[n,kind,threads].extend(rows)
            metadata[n,kind,threads].append(meta)
            print(f'Round {round_number+1}: {n} {kind}/{threads}: {summary(rows)["total_ms"]["median"]:.3f} ms', flush=True)
    for n, kind, threads in jobs:
        rows = samples[n,kind,threads]
        sums = {v['checksum'] for v in rows}
        assert len(sums) == 1
        result['results'].append(dict(n=n,kind=kind,threads=threads,grain=configs[n][0] if kind.startswith('bend') else None,
                                     parallel_consume=configs[n][1] if kind.startswith('bend') else None,
                                     summary=summary(rows),rows=rows,metadata=metadata[n,kind,threads]))
    output = HERE / 'results.json'
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(f'Saved {output}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--repetitions', type=int, default=20)
    parser.add_argument('--rounds', type=int, default=2)
    args = parser.parse_args()
    if args.install:
        install()
    if args.build:
        build(args.bend)
    if args.run:
        run(args.bend, args.repetitions, args.rounds)
