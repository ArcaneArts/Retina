#!/usr/bin/env python3
"""Compare branch-selected material columns with eager queries and prior engines.

All compilation precedes optional alternating warm A/B timings. These are
material-column component calls, not complete lit/featured region throughput.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import signal
import statistics
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_noise import fixture_wire
from test_bend_material import fixtures as material_fixtures, request, Reference
from test_bend_material_columns import decode
from test_bend_surface import tile_bytes, query_bytes
from test_bend_density import ins
from test_bend_noise import MASK
from test_bend_density_lattice import LOW, HIGH


def fixtures(folder):
    folder.mkdir(parents=True, exist_ok=True)
    materials, _ = material_fixtures(folder/'rules')
    profiles = []
    for name, source, _ in materials:
        source = copy.deepcopy(source)
        source.update(geology_min_y=-16, geology_height=32, sea_level=1, stone=1, water=5)
        source['materials'] = ['minecraft:air', 'minecraft:stone', 'minecraft:grass_block',
                               'minecraft:dirt', 'minecraft:gravel', 'minecraft:water'] + ['minecraft:stone']*11
        program = source['registry_program']; program['surface_noises'] = [0, 0, 0]
        program['surface'] = [-16, 8, 0]
        climate = dict(nodes=[ins(29, 0), ins(0, p=(.125, 0, 0, 0)), ins(6, 0, 1), ins(0)], roots=[2, 3, 3, 3, 3, 3])
        solid = dict(nodes=[ins(0, p=(1, 0, 0, 0))], roots=[0])
        # Wrap arbitrary scalar roots into palette-valid rules while retaining
        # the numeric/predicate dependency graph. Selected branches use grass.
        rules = program['programs'][3:]
        for rule in rules:
            root = rule['roots'][0]; end = len(rule['nodes'])
            rule['nodes'] += [ins(44, root, p=(.5, 100, 0, 0)), ins(0, p=(3, 0, 0, 0)), ins(40, end, end+1)]
            rule['roots'] = [end+2]
        # Shared DAG, both select forms, ignored overflowing branches, implicit
        # root zero, and the maximum accepted 1024-node dependency depth.
        rules += [dict(nodes=[ins(0, p=(3, 0, 0, 0)), ins(0), ins(0, p=(1e30, 0, 0, 0)), ins(12, 2),
                            ins(40, 1, 3), ins(41, 4, 0)], roots=[5]),
                  dict(nodes=[ins(0, p=(3, 0, 0, 0)), ins(0, p=(1e30, 0, 0, 0)), ins(12, 1), ins(41, 0, 2)], roots=[3]),
                  dict(nodes=[ins(0, p=(3, 0, 0, 0)), ins(4, 0, 0), ins(5, 1, 0)], roots=[2]),
                  dict(nodes=[ins(0, p=(3, 0, 0, 0)), ins(0, p=(7, 0, 0, 0))], roots=[]),
                  dict(nodes=[ins(0, p=(3, 0, 0, 0))]+[ins(11, i) for i in range(1023)], roots=[1023])]
        # Every numeric dependency shape, including spline children that are
        # not encoded as ordinary operands, nested fields and transformed noise.
        program['points'] = [[-2, .25, 1, 0], [2, -.5, 2, 0]]
        field = dict(nodes=[ins(29, 0), ins(29, 1), ins(29, 2), ins(27, 0, 1, 2, (0, 1, 0, 0))], roots=[3])
        program['interpolations'] = [dict(cell=[4, 8], input=field)]
        for op in list(range(1, 32)):
            # All operands refer to earlier nodes; immediates follow validation.
            node = ins(op, 0, 1, 2, (0, 1, 1, 0))
            if op in (2, 3, 29, 30): node = ins(op, 0, 0, 0, (0, 8, 0, 1))
            if op == 25: node = ins(op, 0, 0, 2)
            if op == 26: node = ins(op, p=(1, 1, 80, 160))
            if op == 28: node = ins(op, 0, 1, 2, (0, 0, 0, 0))
            if op == 31: node = ins(op, 0, 1, 2, (.1, .1, 80, 160))
            rules.append(dict(nodes=[ins(0, p=(.25, 0, 0, 0)), ins(0, p=(3, 0, 0, 0)),
                ins(0, p=(-1, 0, 0, 0)), node, ins(44, 3, p=(-1, 1, 0, 0)),
                ins(0, p=(4, 0, 0, 0)), ins(40, 4, 5)], roots=[6]))
        program['programs'] = [climate, solid, solid]+rules
        source['biomes'] = [{'flags': 0} for _ in rules]
        source['climate_targets'] = [dict(min=[i, 0, 0, 0], max=[i, 0, 0, 0], weirdness=[0, 0], depth=[0, 0], offset=0, biome=i)
                                     for i in range(len(rules))]
        wire = folder/(name+'.rbp'); wire.write_bytes(fixture_wire(source, True))
        profiles.append((name, source, wire))
    return profiles


def prepare(worker, wire):
    for op, body in [(5, path_request(wire)), (11, b''), (19, b''), (21, b''), (9, b''), (15, b'')]:
        worker.call(op, body)


def tile(worker, geometry):
    x, z, width, depth, low, high = geometry
    descriptor = worker.call(18, tile_bytes((x-1, z-1, width+2, depth+2, low, high)))
    worker.call(13, descriptor); worker.call(16, tile_bytes(geometry))


def correctness(binary, profiles, gpu):
    worker = Worker(binary, gpu); hashes = []; rows = []; dispatches = 0
    try:
        for name, source, wire in profiles:
            signal.alarm(600); prepare(worker, wire)
            count = len(source['biomes']); geometry = (0, -13, count*8, 1, LOW, HIGH)
            tile(worker, geometry); dispatches += 2
            worker.call(22); dispatches += 1
            points = [(i*8, -13, LOW, HIGH) for i in range(count)]
            raw = worker.call(24, query_bytes(points)); columns = decode(raw)
            assert worker.call(24, query_bytes(points[::-1])) == b''.join(
                worker.call(24, query_bytes([point])) for point in points[::-1])
            queries = []; wanted = []
            for pid, column in enumerate(columns):
                assert column['biome'] == pid, (name, pid, column['biome'])
                d, slope, band, secondary, preliminary = column['context']
                for start, end, material in column['runs']:
                    for offset in range(start, end):
                        y = source['geology_min_y']+offset
                        ctx = (source['geology_height']-offset, d, slope, band, offset+1, secondary, -2147483648., preliminary)
                        queries.append((pid, pid*8, y, -13, LOW, HIGH, ctx)); wanted.append(material)
            # Eager first-root queries exercise the same scalar coordinates and
            # GPU-derived contexts without sharing the lazy traversal itself.
            eager = worker.call(20, request(queries)); dispatches += 1
            selected = [int(row[0])-1 if row[0] > 0 else source['stone'] for row in struct.iter_unpack('>6f', eager)]
            assert selected == wanted, (name, next((i for i, pair in enumerate(zip(selected, wanted)) if pair[0] != pair[1]), None))
            # Independent equations additionally cover the original predicate
            # fixtures. Overflow fixtures deliberately cannot use that oracle.
            ref = Reference(source)
            original_count = count-36
            original = [q for q in queries if q[0] < original_count]
            ref.verify(worker.call(20, request(original)), original); dispatches += 1
            worker.call(22); dispatches += 1
            assert worker.call(24, query_bytes(points)) == raw
            hashes.append(hashlib.sha256(raw).hexdigest())
            rows.append(dict(name=name, programs=count, eager_voxels=len(queries), independent_voxels=len(original),
                             implicit_root=True, maximum_dependency_nodes=1024, repeated_reordered_identical=True))
            print(f'{"GPU" if gpu else "CPU"}: {name}; {len(queries)} lazy/eager voxels match', flush=True)
        worker.call(4); assert worker.process.wait(timeout=10) == 0
        diagnostic = worker.process.stderr.read().decode()
        return dict(gpu_required=gpu, profiles=rows, expected_dispatches=dispatches,
                    metal_commands_ms=[float(t) for t in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)', diagnostic)],
                    aggregate_sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:
        signal.alarm(0); worker.close()


def benchmark(baseline, candidate, profiles, gpu, repeats):
    workers = [Worker(binary, gpu) for binary in (baseline, candidate)]; rows = []
    try:
        for name, source, wire in profiles:
            signal.alarm(900)
            for worker in workers: prepare(worker, wire)
            for side in (32, 64):
                geometry = (-64, 96, side, side, LOW, HIGH)
                for worker in workers: tile(worker, geometry)
                warm = [worker.call(22) for worker in workers]; assert warm[0] == warm[1]
                points = [(geometry[0]+i%side, geometry[1]+i//side, LOW, HIGH) for i in range(side*side)]
                def read_all(worker):
                    return b''.join(worker.call(24, query_bytes(points[i:i+256])) for i in range(0, len(points), 256))
                reference = read_all(workers[0]); assert read_all(workers[1]) == reference
                times = [[], []]
                for iteration in range(repeats):
                    for index in ([0, 1] if iteration % 2 == 0 else [1, 0]):
                        start = time.perf_counter(); result = workers[index].call(22)
                        times[index].append((time.perf_counter()-start)*1000); assert result == warm[0]
                for worker in workers: assert read_all(worker) == reference
                medians = [statistics.median(values) for values in times]
                rows.append(dict(profile=name, columns=side*side, warmups=1, repeats=repeats,
                    baseline_host_ms=times[0], candidate_host_ms=times[1], baseline_median_ms=medians[0],
                    candidate_median_ms=medians[1], median_speed_ratio=medians[0]/medians[1],
                    all_column_bytes_identical=True, sha256=hashlib.sha256(reference).hexdigest()))
                print(f'{"GPU" if gpu else "CPU"}: {name} {side}x{side}: {medians[0]:.3f} -> {medians[1]:.3f} ms ({medians[0]/medians[1]:.2f}x)', flush=True)
        for worker in workers: worker.call(4)
        return dict(gpu_required=gpu, rows=rows)
    finally:
        signal.alarm(0)
        for worker in workers: worker.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND); parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--profile', nargs=2, action='append', default=[], metavar=('JSON', 'WIRE'))
    parser.add_argument('--baseline', type=Path); parser.add_argument('--repeats', type=int, default=5)
    metal = parser.add_mutually_exclusive_group()
    metal.add_argument('--prove-metal', action='store_true'); metal.add_argument('--observer', type=Path)
    args = parser.parse_args()
    if args.repeats < 1: parser.error('repeats must be positive')
    binary = ROOT/'build/bend/engine'
    if not args.skip_build: build(args.bend, binary, ROOT/'bend/engine.bend')
    profiles = fixtures(ROOT/'build/bend/lazy-material-profiles')
    actual = [(Path(js).parent.name, json.loads(Path(js).read_text()), Path(wire)) for js, wire in args.profile]
    observer = compile_metal_observer(args.bend, ROOT/'bend/engine.bend', ROOT/'build/bend/lazy-material-metal-probe') if args.prove_metal else args.observer
    report = dict(scope='Lazy material scalar evaluation and warm bulk-column component timings; no complete-region performance claim',
        cpu_workers=2, process_nice=10, candidate_binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
        runs=[correctness(binary, profiles, gpu) for gpu in (False, True)])
    if observer:
        observed = correctness(observer, profiles, True); times = observed['metal_commands_ms']
        assert len(times) == observed['expected_dispatches'] and all(t > 0 for t in times)
        assert observed['aggregate_sha256'] == report['runs'][1]['aggregate_sha256']
        report['metal_observation'] = dict(diagnostic_only=True, binary_sha256=hashlib.sha256(observer.read_bytes()).hexdigest(), commands=len(times), output_identical=True, device_ms=times)
    if args.baseline:
        report['baseline_binary_sha256'] = hashlib.sha256(args.baseline.read_bytes()).hexdigest()
        report['benchmark_policy'] = 'One warmup, five default repeats, serial alternating A/B, no concurrent project compilation; host response time'
        report['benchmarks'] = [benchmark(args.baseline, binary, actual or profiles[:1], gpu, args.repeats) for gpu in (False, True)]
    report['profiles'] = [dict(name=name, wire_sha256=hashlib.sha256(wire.read_bytes()).hexdigest()) for name, _, wire in profiles+actual]
    report['source_sha256'] = {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
        ('bend/material_lazy.bend', 'bend/material_columns.bend', 'scripts/test_bend_lazy_material.py')}
    out = ROOT/'build/bend/lazy-material-tests.json'; out.write_text(json.dumps(report, indent=2)+'\n')
    print(f'PASS: lazy material CPU/GPU execution; {out}')


if __name__ == '__main__':
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('lazy material workload timeout')))
    main()
