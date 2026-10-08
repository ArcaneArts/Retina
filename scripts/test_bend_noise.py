#!/usr/bin/env python3
"""Check pure Bend seeded noise on CPU/GPU against independent scalar equations.

These are numerical correctness checks, not complete-generator benchmarks.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess

from test_bend_compression import ROOT, DEFAULT_BEND, build

MASK = (1 << 32) - 1
F2, G2 = 0.3660254037844386, 0.2113248654051871


def f32(x):
    return struct.unpack('f', struct.pack('f', x))[0]


def mix_hash(x):
    x = ((x ^ (x >> 16)) * 2146121005) & MASK
    x = ((x ^ (x >> 15)) * 2221713035) & MASK
    return x ^ (x >> 16)


def signed(x):
    return x if x < (1 << 31) else x - (1 << 32)


def coordinate(i):
    h = mix_hash(i)
    return [h % 8192 - 4096, 29999000 + h % 1000, h % 1000 - 30000000,
            2147482648 + h % 1000, 2147483648 + h % 1000, h][i % 6] & MASK


def frequency(i):
    return f32([0., .0035, .015625, .125, 1., 3.25, .000244140625][i % 7])


def axis(x, frequency):
    # Independent exact integer product, rather than Bend's split-word multiply.
    magnitude = abs(signed(x))
    whole = math.floor(frequency)
    fraction = int(f32(f32(frequency - whole) * 4294967296.))
    fixed = magnitude * ((whole << 32) + fraction)
    if signed(x) < 0:
        fixed = -fixed
    cell, fraction = divmod(fixed, 1 << 32)
    fraction = f32(f32(fraction) / (1 << 32))
    carry = math.floor(fraction)
    return (cell + carry) & MASK, f32(fraction - carry)


def add_axes(a, b):
    fraction = f32(a[1] + b[1])
    carry = math.floor(fraction)
    return (a[0] + b[0] + carry) & MASK, f32(fraction - carry)


def contribution(cx, cz, dx, dz, low, high):
    h = mix_hash(((cx * 2654435769) ^ (cz * 2246822507) ^ low ^ mix_hash(high)) & MASK) & 7
    gx, gz = [(1, 1), (-1, 1), (1, -1), (-1, -1),
              (1, 0), (-1, 0), (0, 1), (0, -1)][h]
    t = max(.5 - dx * dx - dz * dz, 0)
    return t ** 4 * (gx * dx + gz * dz)


def simplex_cells(cx, cz, dx, dz, low, high):
    sx, sz = (1, 0) if dx > dz else (0, 1)
    return 70 * (contribution(cx, cz, dx, dz, low, high) +
                 contribution((cx + sx) & MASK, (cz + sz) & MASK,
                              dx - sx + G2, dz - sz + G2, low, high) +
                 contribution((cx + 1) & MASK, (cz + 1) & MASK,
                              dx - 1 + 2 * G2, dz - 1 + 2 * G2, low, high))


def world_simplex(x, z, frequency, low, high):
    sf = f32(frequency * f32(F2))
    skew = add_axes(axis(x, sf), axis(z, sf))
    cx, fx = add_axes(axis(x, frequency), skew)
    cz, fz = add_axes(axis(z, frequency), skew)
    unskew = f32(f32(fx + fz) * f32(G2))
    return simplex_cells(cx, cz, f32(fx - unskew), f32(fz - unskew), low, high)


def point_simplex(x, z, low, high):
    skew = f32(f32(x + z) * f32(F2))
    cx, cz = math.floor(f32(x + skew)), math.floor(f32(z + skew))
    unskew = f32(f32(cx + cz) * f32(G2))
    dx, dz = f32(x - f32(cx - unskew)), f32(z - f32(cz - unskew))
    return simplex_cells(cx & MASK, cz & MASK, dx, dz, low, high)


def trilinear(v, x, y, z):
    # Weighted sum is independent of Bend's nested lerps.
    return sum(v[k] * (x if k & 1 else 1-x) * (y if k & 2 else 1-y) *
               (z if k & 4 else 1-z) for k in range(8))


def perlin(x, y, z, frequency, low, high):
    cells, fractions = zip(axis(x, frequency), axis(y, frequency), axis(z, frequency))
    seed = low ^ mix_hash(high)
    corners = []
    for k in range(8):
        c = [(cells[j] + ((k >> j) & 1)) & MASK for j in range(3)]
        d = [fractions[j] - ((k >> j) & 1) for j in range(3)]
        h = mix_hash(((c[0] * 2654435769) ^ (c[1] * 2246822507) ^
                      (c[2] * 3266489909) ^ seed) & MASK) & 15
        a = d[0] if h < 8 else d[1]
        b = d[1] if h < 4 else d[0] if h in (12, 14) else d[2]
        corners.append((-a if h & 1 else a) + (-b if h & 2 else b))
    fade = [t*t*t*(t*(t*6-15)+10) for t in fractions]
    return trilinear(corners, *fade) * 1.6


def expected(i):
    x, y, z = coordinate(i), (mix_hash(i+31) % 512 - 128) & MASK, coordinate(i+19)
    low, high = mix_hash(i//16 + 117), mix_hash(i//32 + 437)
    freq = frequency(i)
    weights, values = [], []
    for j, modifier in enumerate([1., 0., .25, 1., -1., .5]):
        if modifier > 0:
            weight = modifier / (1 << j)
            weights.append(weight)
            values.append(world_simplex(x, z, f32(freq * (1 << j)),
                                        (low + 2*7919 + j*1013) & MASK, high) * weight)
    registry = max(-1., min(1., sum(values) / max(.0001, sum(weights)) * f32(.8) * f32(1.6)))
    return (axis(x, freq), [world_simplex(x, z, freq, low, high),
        perlin(x, y, z, freq, low, high), registry,
        point_simplex(float(i % 1000 - 500), (mix_hash(i) % 1000 - 500)*.125, low, high),
        trilinear([.1,.2,.3,.4,.5,.6,.7,.8], (i & 255)/256,
                  (mix_hash(i) & 255)/256, (mix_hash(i+1) & 255)/256)],
        mix_hash(low ^ mix_hash(high)), world_simplex((x+1) & MASK, z, freq, low, high))


def verify(path):
    data = path.read_bytes()
    assert len(data) == 8192 * 9 * 4, len(data)
    maximum = [0.] * 6
    distant_nonflat = 0
    for i, row in enumerate(struct.iter_unpack('>9I', data)):
        a, values, hash_value, neighbor = expected(i)
        assert row[0] == a[0], (i, row[0], a)
        assert abs(row[1] / (1 << 24) - a[1]) <= 1 / (1 << 24)
        assert row[7] == hash_value, (i, 'full seed hash')
        for j, (actual, reference) in enumerate(zip([*row[2:7], row[8]], [*values, neighbor])):
            error = abs(actual / (1 << 20) - 2 - reference)
            maximum[j] = max(maximum[j], error)
            assert error < 0.00001, (i, j, error, actual, reference)
        if i % 6 in (1, 2) and i % 7 in (1, 2, 6):
            distant_nonflat += row[2] != row[8]
            assert abs(row[2]-row[8]) / (1 << 20) < .15, (i, 'distant derivative')
    assert distant_nonflat > 1000, distant_nonflat
    return {'rows': 8192, 'scalar_values': 8192*6, 'max_absolute_errors': maximum,
            'distant_neighbor_pairs_with_variation': distant_nonflat,
            'sha256': hashlib.sha256(data).hexdigest()}


def run(binary, output, backend, threads):
    p = subprocess.run(['nice', '-n', '10', str(binary), str(output), '--threads', str(threads),
                        '--gpu', backend], cwd=ROOT, capture_output=True, text=True, timeout=120,
                       env=dict(os.environ, BEND_NO_TELEMETRY='1'))
    if p.returncode:
        raise RuntimeError(p.stdout + p.stderr)
    return p


def prove_metal(bend, source, binary, output):
    # Diagnostic observation only: one fprintf after the STOCK runtime's wait.
    # No application algorithm or GPU kernel is replaced or handwritten.
    cpath = binary.with_suffix('.c')
    build(bend, cpath, source)
    c = cpath.read_text()
    needle = '[cb waitUntilCompleted];'
    assert c.count(needle) == 1
    cpath.write_text(c.replace(needle, needle + '\n    fprintf(stderr, "BEND_METAL_DISPATCH device_ms=%.6f\\n", ([cb GPUEndTime]-[cb GPUStartTime])*1000.0);'))
    xcode = Path('/Applications/Xcode.app/Contents/Developer')
    env = dict(os.environ, BEND_NO_TELEMETRY='1',
               SDKROOT=str(xcode/'Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk'))
    subprocess.run(['nice','-n','10',str(xcode/'Toolchains/XcodeDefault.xctoolchain/usr/bin/clang'),
                    '-DBEND_METAL=1','-x','objective-c','-fobjc-arc','-fmodules','-std=c11','-O3',
                    str(cpath),'-lpthread','-lm','-o',str(binary)], check=True, env=env, timeout=120)
    subprocess.run([str(binary),'--gpu-build'],check=True,env=env,cwd=ROOT,capture_output=True,timeout=120)
    p = run(binary,output,'on',2)
    times = [float(x) for x in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)',p.stderr)]
    assert times and all(t > 0 for t in times), p.stderr
    return {'diagnostic_only': True, 'actual_command_buffers': len(times), 'device_ms': times,
            'output': verify(output)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bend', type=Path, default=DEFAULT_BEND)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--prove-metal', action='store_true')
    args = parser.parse_args()
    folder = ROOT/'build/bend'
    source, binary = ROOT/'bend/tests/noise-fixtures.bend', folder/'noise-fixtures'
    version = 'existing executable' if args.skip_build else build(args.bend,binary,source)
    report = {'scope':'Seeded noise correctness; not full generation or performance',
              'bend_version':version, 'invalid_configurations_rejected_per_run':8,
              'additional_seed_and_stack_boundary_checks_per_run':6, 'runs':[]}
    for backend, threads in [('off',1),('off',2),('on',2)]:
        output = folder/f'noise-{backend}-{threads}.bin'
        run(binary,output,backend,threads)
        report['runs'].append({'requested_gpu':backend,'threads':threads,**verify(output)})
    cpu1, cpu2, gpu = [x['sha256'] for x in report['runs']]
    assert cpu1 == cpu2, 'CPU scheduling changes samples'
    # Backend floating-point contraction may differ; each is independently checked.
    report['cpu_gpu_bytes_equal'] = cpu1 == gpu
    if args.prove_metal:
        report['metal_observation'] = prove_metal(args.bend,source,folder/'noise-metal-probe',folder/'noise-probe.bin')
        assert report['metal_observation']['output']['sha256'] == gpu
    report['source_sha256'] = hashlib.sha256((ROOT/'bend/noise.bend').read_bytes()).hexdigest()
    destination = folder/'noise-tests.json'
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print(f'PASS: 8192 rows on 1/2 CPU workers and GPU; {destination}')


if __name__ == '__main__':
    main()
