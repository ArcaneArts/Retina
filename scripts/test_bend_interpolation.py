#!/usr/bin/env python3
"""Check zero-weight interpolation corners, optionally A/B resident workloads.

Build both executables before benchmarking. Timings are serial, alternating
warm component calls, not complete lit/featured region throughput.
"""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import signal
import statistics
import struct
import time

from test_bend_compression import ROOT, DEFAULT_BEND, build
from test_bend_engine import Worker, compile_metal_observer
from test_bend_registry import path_request
from test_bend_registry_density import request, verify
from test_bend_registry_noise import fixture_wire
from test_bend_density import ins
from test_bend_density_lattice import descriptor, encoded, layout, points_for, Reference, LOW, HIGH


def queries():
    bases = [(0, 0, 0), (-8, -16, 12), (30000000, 64, -30000000),
             (-2147483648, -8, 2147483640)]
    return [(pid, *(a + b*s for a, b, s in zip(base, bits, (2, 4, 2))), LOW, HIGH)
            for pid, base, bits in itertools.product(range(3), bases, itertools.product((0, 1), repeat=3))]


def fixtures(folder):
    folder.mkdir(parents=True, exist_ok=True)
    axes = [ins(29, a=i) for i in range(3)]
    field = dict(nodes=axes + [ins(27, 0, 1, 2, (0, 1, 0, 0)), ins(12, 0),
                              ins(6, 0, 2), ins(4, 4, 1), ins(4, 6, 5), ins(4, 7, 3)], roots=[8])
    nested = dict(nodes=axes + [ins(28, 0, 1, 2, (0, 0, 0, 0)), ins(4, 3, 1)], roots=[4])
    programs = [dict(nodes=axes + [ins(28, 0, 1, 2, (i, 0, 0, 0))], roots=[3]) for i in range(2)]
    programs.append(dict(nodes=axes + [ins(0, p=(.25, 0, 0, 0)), ins(0, p=(.5, 0, 0, 0)),
        ins(0, p=(.75, 0, 0, 0)), ins(4, 0, 3), ins(4, 1, 4), ins(4, 2, 5),
        ins(28, 6, 7, 8, (1, 0, 0, 0))], roots=[9]))
    source = {'registry_program': {'noises': [{'frequency': .0035, 'amplitude': .8,
        'horizontal_scale': 1.25, 'salt': -12345, 'coefficients': [1., 0., -.25, .5]}],
        'points': [], 'interpolations': [{'cell': [4, 8], 'input': field},
                                        {'cell': [4, 8], 'input': nested}], 'programs': programs}}
    wire = folder/'finite.rbp'; wire.write_bytes(fixture_wire(source, True))
    poison = []
    for axis in range(3):
        overflow = dict(nodes=[ins(29, a=axis), ins(0, p=(1.e30, 0, 0, 0)),
            ins(6, 0, 1), ins(12, 2), ins(0, p=(7., 0, 0, 0)), ins(4, 3, 4)], roots=[5])
        profile = {'registry_program': {'noises': [], 'points': [],
            'interpolations': [{'cell': [4, 8], 'input': overflow}],
            'programs': [programs[0]]*3}}
        path = folder/f'overflow-{axis}.rbp'; path.write_bytes(fixture_wire(profile, True))
        poison.append((axis, path))
    return ('finite', source, wire), poison


def correctness(binary, profiles, poison, gpu):
    worker = Worker(binary, gpu); hashes = []; rows = []; dispatches = 0
    try:
        for name, source, wire in profiles:
            signal.alarm(300); worker.call(5, path_request(wire)); worker.call(11)
            points = queries(); output = worker.call(12, request(points)); dispatches += 1
            error = verify(output, source, points)
            assert worker.call(12, request(points)) == output; dispatches += 1
            reverse = worker.call(12, request(points[::-1])); dispatches += 1
            assert reverse == b''.join(output[i:i+24] for i in range(len(output)-24, -1, -24))
            hashes.append(hashlib.sha256(output).hexdigest())
            rows.append(dict(profile=name, queries=len(points), max_reference_error=error,
                             sha256=hashes[-1], repeated_reordered_identical=True))
            print(f'{"GPU" if gpu else "CPU"}: {name}; {len(points)} vertex/edge/face/interior queries pass', flush=True)
        for axis, wire in poison:
            worker.call(5, path_request(wire)); worker.call(11)
            points = [(0, *p, LOW, HIGH) for p in itertools.product((0, 2), (0, 4), (0, 2)) if p[axis] == 0]
            output = worker.call(12, request(points)); dispatches += 1
            assert all(row[0] == 7. for row in struct.iter_unpack('>6f', output)), (axis, output)
            # A contributing overflowing vertex remains nonfinite. The shortcut
            # is endpoint selection, not a clamp or blanket NaN replacement.
            point = [0, 0, 0]; point[axis] = (4, 8, 4)[axis]
            output = worker.call(12, request([(0, *point, LOW, HIGH)])); dispatches += 1
            assert not math.isfinite(struct.unpack('>6f', output)[0])
        worker.call(4); assert worker.process.wait(timeout=10) == 0
        diagnostics = worker.process.stderr.read().decode()
        return dict(gpu_required=gpu, profiles=rows, unused_overflow_axes_checked=3,
                    expected_dispatches=dispatches,
                    metal_commands_ms=[float(t) for t in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)', diagnostics)],
                    aggregate_sha256=hashlib.sha256(''.join(hashes).encode()).hexdigest())
    finally:
        signal.alarm(0); worker.close()


def benchmark(baseline, candidate, profiles, repeats, gpu):
    workers = []; rows = []
    try:
        for binary in (baseline, candidate): workers.append(Worker(binary, gpu))
        for name, source, wire in profiles:
            signal.alarm(600)
            for worker in workers: worker.call(5, path_request(wire)); worker.call(11)
            desc = descriptor(2, (-64, -64, 96), (0, 32, 160))
            origin, counts, steps = layout(desc)
            vertices = [(2, *(a+i*s for a, i, s in zip(origin, index, steps)), LOW, HIGH)
                        for index in itertools.product(*(range(n) for n in counts))]
            workloads = [('mixed_queries', 12, request(queries())), ('lattice_vertices', 13, encoded(desc))]
            for workload, op, body in workloads:
                warm = [worker.call(op, body) for worker in workers]
                assert warm[0] == warm[1], (name, workload, 'warm bytes differ')
                if op == 12: verify(warm[1], source, queries())
                times = [[], []]
                for iteration in range(repeats):
                    for index in ([0, 1] if iteration % 2 == 0 else [1, 0]):
                        start = time.perf_counter(); output = workers[index].call(op, body)
                        times[index].append((time.perf_counter()-start)*1000)
                        assert output == warm[0], (name, workload, index, iteration)
                if op == 13:
                    # Compare every resident vertex after the timed builds, plus
                    # independent arbitrary/seed/cache-miss reference probes.
                    outputs = [worker.call(14, request(vertices)) for worker in workers]
                    assert outputs[0] == outputs[1], (name, 'lattice vertex bytes differ')
                    probes = points_for(desc)
                    probed = [worker.call(14, request(probes)) for worker in workers]
                    assert probed[0] == probed[1]
                    error = Reference(source).verify(probed[1], probes, desc)
                    digest = hashlib.sha256(outputs[1]).hexdigest()
                else:
                    error = verify(warm[1], source, queries()); digest = hashlib.sha256(warm[1]).hexdigest()
                medians = [statistics.median(values) for values in times]
                rows.append(dict(profile=name, workload=workload, samples=len(vertices) if op == 13 else len(queries()),
                    warmups=1, repeats=repeats, baseline_host_ms=times[0], candidate_host_ms=times[1],
                    baseline_median_ms=medians[0], candidate_median_ms=medians[1], median_speed_ratio=medians[0]/medians[1],
                    compared_bytes_identical=True, max_reference_error=error, output_sha256=digest))
                print(f'{"GPU" if gpu else "CPU"} {name} {workload}: {medians[0]:.3f} -> {medians[1]:.3f} ms ({medians[0]/medians[1]:.2f}x)', flush=True)
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
    parser.add_argument('--prove-metal', action='store_true')
    args = parser.parse_args()
    if args.repeats < 1: parser.error('repeats must be positive')
    binary = ROOT/'build/bend/engine'
    if not args.skip_build: build(args.bend, binary, ROOT/'bend/engine.bend')
    finite, poison = fixtures(ROOT/'build/bend/interpolation-profiles'); profiles = [finite]
    for source, wire in args.profile:
        source, wire = Path(source), Path(wire); profiles.append((source.parent.name, json.loads(source.read_text()), wire))
    # All compilation (including optional observer) precedes timed measurements.
    observer = compile_metal_observer(args.bend, ROOT/'bend/engine.bend', ROOT/'build/bend/interpolation-metal-probe') if args.prove_metal else None
    report = dict(scope='Registered trilinear corner evaluation and warm resident component calls; no complete-region performance claim',
                  cpu_workers=2, process_nice=10, candidate_binary_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  runs=[correctness(binary, profiles, poison, gpu) for gpu in (False, True)])
    if observer:
        observed = correctness(observer, profiles, poison, True); times = observed['metal_commands_ms']
        assert len(times) == observed['expected_dispatches'] and all(t > 0 for t in times)
        assert observed['aggregate_sha256'] == report['runs'][1]['aggregate_sha256']
        report['metal_observation'] = dict(diagnostic_only=True, commands=len(times), output_identical=True, device_ms=times)
    if args.baseline:
        report['baseline_binary_sha256'] = hashlib.sha256(args.baseline.read_bytes()).hexdigest()
        report['benchmark_policy'] = 'One warmup, five default repeats, serial alternating A/B, no concurrent project compilation; host response time'
        report['benchmarks'] = [benchmark(args.baseline, binary, profiles, args.repeats, gpu) for gpu in (False, True)]
    report['profiles'] = [dict(name=name, wire_sha256=hashlib.sha256(wire.read_bytes()).hexdigest()) for name, _, wire in profiles]
    report['source_sha256'] = {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
                             ('bend/density.bend', 'scripts/test_bend_interpolation.py', 'scripts/test_bend_density.py')}
    out = ROOT/'build/bend/interpolation-tests.json'; out.write_text(json.dumps(report, indent=2)+'\n')
    print(f'PASS: interpolation corners and CPU/GPU execution; {out}')


def timeout(_signal, _frame):
    raise TimeoutError('Bend interpolation workload timed out')


if __name__ == '__main__':
    signal.signal(signal.SIGALRM, timeout)
    main()
