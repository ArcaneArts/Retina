#!/usr/bin/env python3
"""Check pure Bend registered height providers against an independent integer model.

This component projects the original ordered placement programs and samples their
height providers. It does not place surface features or establish full backend
parity/throughput. JSON profiles cross the registry wire without simplification.
"""
import argparse
import copy
import hashlib
import json
import re
import struct
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_decoration_counts import Random
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, mix_hash
from test_bend_registry_noise import fixture_wire

MINIMUM, MAXIMUM = -(1 << 31), (1 << 31) - 1
CONTEXTS = [(-64, 384), (-2032, 4064), (0, 256), (-128, 512),
            (MINIMUM, MASK), (MAXIMUM - 127, 128), (MINIMUM, 128), (-1, 1)]


def saturated(value):
    return min(MAXIMUM, max(MINIMUM, value))


def anchor(value, minimum, height):
    if 'absolute' in value: return value['absolute']
    if 'above_bottom' in value: return saturated(minimum + value['above_bottom'])
    if 'below_top' in value: return saturated(minimum + height - 1 - value['below_top'])
    raise AssertionError(value)


def between(rng, lo, hi):
    return lo if lo >= hi else rng.integer(lo, hi)


def sample(value, rng, minimum, height):
    if 'type' not in value: return anchor(value, minimum, height)
    kind = value['type']
    if kind == 'constant': return anchor(value['value'], minimum, height)
    if kind == 'weighted_list':
        choice = rng.next() % sum(entry['weight'] for entry in value['distribution'])
        for entry in value['distribution']:
            if choice < entry['weight']: return sample(entry['data'], rng, minimum, height)
            choice -= entry['weight']
        raise AssertionError('invalid independent weighted selection')
    lo = anchor(value['min_inclusive'], minimum, height)
    hi = anchor(value['max_inclusive'], minimum, height)
    if kind == 'uniform': return between(rng, lo, hi)
    inner = value.get('inner', 1)
    if kind in ('biased_to_bottom', 'very_biased_to_bottom'):
        if hi - lo - inner + 1 <= 0: return lo
        if kind == 'biased_to_bottom':
            limit = rng.integer(0, hi - lo - inner)
            return lo + rng.integer(0, limit + inner - 1)
        upper = between(rng, lo + inner, hi)
        biased = between(rng, lo, upper - 1)
        return between(rng, lo, saturated(biased + inner - 1))
    if kind == 'trapezoid':
        if lo > hi: return lo
        span = hi - lo
        plateau = value.get('plateau', 0)
        if plateau >= span: return between(rng, lo, hi)
        low = (span - plateau) // 2
        return lo + between(rng, 0, span - low) + between(rng, 0, low)
    raise AssertionError(kind)


def rows(source):
    return [(recipe, modifier, op['height'])
            for recipe, spec in enumerate(source['decorations'])
            for modifier, op in enumerate(spec.get('placement') or [])
            if op['type'] == 'height_range']


def check(data, source):
    expected = rows(source)
    count, context_bits = struct.unpack_from('>2I', data)
    assert (count, context_bits) == (len(expected), 7), (count, len(expected), context_bits)
    stride = 12 + 256 * 12
    assert len(data) == 8 + count * stride, (len(data), count)
    actual = {}
    for offset in range(8, len(data), stride):
        id, recipe, modifier = struct.unpack_from('>3I', data, offset)
        er, em, spec = expected[id]
        assert id not in actual and (recipe, modifier) == (er, em)
        values = []
        for i in range(256):
            bits, high, low = struct.unpack_from('>3I', data, offset + 12 + i * 12)
            initial = (mix_hash((id * 7919 + i // 32) & MASK) << 32) | mix_hash((id * 811 + (i // 32) * 131) & MASK)
            rng = Random(initial)
            minimum, height = CONTEXTS[i % 8]
            for _ in range(1 + (i // 8) % 4):
                value = sample(spec, rng, minimum, height)
            assert (bits, high, low) == (value & MASK, rng.state >> 32, rng.state & MASK), (
                id, i, spec, (minimum, height), (bits, high, low), value, rng.state)
            values.append((bits, high, low))
        actual[id] = (recipe, modifier, values)
    canonical = json.dumps(actual, sort_keys=True, separators=(',', ':')).encode()
    return dict(providers=count, checked_samples=count * 256, build_contexts=len(CONTEXTS),
                sequential_draw_lengths=[1, 2, 3, 4], context_validation='pass',
                sampling_sha256=hashlib.sha256(canonical).hexdigest())


def fixtures():
    providers = []
    for kind in ('absolute', 'above_bottom', 'below_top'):
        for value in (0, 1, -1, 2032, -4096, MINIMUM, MAXIMUM):
            providers.append({kind: value})
    providers += [{'type': 'constant', 'value': {kind: v}}
                  for kind, v in [('absolute', -64), ('above_bottom', 17), ('below_top', 7)]]
    ranges = [({'absolute': -64}, {'absolute': 320}),
              ({'above_bottom': 0}, {'below_top': 0}),
              ({'above_bottom': -17}, {'below_top': 33}),
              ({'absolute': 7}, {'absolute': 7}),
              ({'absolute': 8}, {'absolute': 7}),
              ({'absolute': MINIMUM}, {'absolute': MAXIMUM}),
              ({'absolute': MAXIMUM-20}, {'absolute': MAXIMUM}),
              ({'absolute': MINIMUM}, {'absolute': MINIMUM+20})]
    for lo, hi in ranges:
        for kind in ('uniform', 'biased_to_bottom', 'very_biased_to_bottom', 'trapezoid'):
            providers.append(dict(type=kind, min_inclusive=lo, max_inclusive=hi))
            if kind in ('biased_to_bottom', 'very_biased_to_bottom'):
                providers.extend(dict(type=kind, min_inclusive=lo, max_inclusive=hi, inner=inner)
                                 for inner in (8, MAXIMUM))
            if kind == 'trapezoid':
                providers.extend(dict(type=kind, min_inclusive=lo, max_inclusive=hi, plateau=p)
                                 for p in (1, 17, MAXIMUM))
    providers += [
        {'type': 'weighted_list', 'distribution': [
            {'data': {'absolute': 7}, 'weight': MASK},
            {'data': providers[25], 'weight': MASK}]},
        {'type': 'weighted_list', 'distribution': [
            {'data': {'above_bottom': 99}, 'weight': 0},
            {'data': {'type': 'weighted_list', 'distribution': [
                {'data': providers[27], 'weight': 3},
                {'data': {'below_top': 2}, 'weight': 5}]}, 'weight': 1}]},
        {'type': 'weighted_list', 'distribution': [{'data': {'absolute': 11}, 'weight': 1}]},
    ]
    source = {'decorations': [
        {'placement': [{'type': 'count', 'count': 2}, {'type': 'height_range', 'height': p},
                       {'type': 'biome'}, {'type': 'height_range', 'height': {'above_bottom': -1}}]}
        for p in providers] + [{'placement': None}, {}, {'placement': []}]}
    bad_values = [None, True, 3, 1.5, 'uniform', [], {}, {'absolute': True},
                  {'absolute': MINIMUM-1}, {'absolute': MAXIMUM+1}, {'above_bottom': 1.5},
                  {'below_top': '1'}, {'absolute': 1, 'below_top': 2},
                  {'type': 'constant'}, {'type': 'constant', 'value': {'absolute': 1, 'extra': 1}},
                  {'type': 'unknown', 'value': {'absolute': 1}}, {'type': None}]
    for kind in ('uniform', 'biased_to_bottom', 'very_biased_to_bottom', 'trapezoid'):
        bad_values += [dict(type=kind, min_inclusive={'absolute': 0}),
                       dict(type=kind, min_inclusive=0, max_inclusive={'absolute': 10})]
    for kind in ('biased_to_bottom', 'very_biased_to_bottom'):
        bad_values += [dict(type=kind, min_inclusive={'absolute': 0}, max_inclusive={'absolute': 10}, inner=x)
                       for x in (0, -1, 1.5, True, MAXIMUM+1)]
    bad_values += [dict(type='trapezoid', min_inclusive={'absolute': 0}, max_inclusive={'absolute': 10}, plateau=x)
                   for x in (-1, 1.5, True, MAXIMUM+1)]
    bad_values += [dict(type='weighted_list', distribution=distribution) for distribution in (
        [], None, [{'data': {'absolute': 0}, 'weight': 0}],
        [{'data': {'absolute': 0}, 'weight': -1}], [{'data': {'absolute': 0}, 'weight': MASK+1}],
        [{'data': {'absolute': 0}, 'weight': True}], [{'data': {'absolute': 0}}],
        [{'weight': 1}], [{'data': {'type': 'unknown'}, 'weight': 0}, {'data': {'absolute': 0}, 'weight': 1}])]
    invalid = [{'decorations': [{'placement': [{'type': 'height_range', 'height': p}]}]}
               for p in bad_values]
    invalid += [{'decorations': [{'placement': [{'type': 'height_range'}]}]},
                {'decorations': [{'placement': {}}]}, {'decorations': None}, {},
                {'decorations': [{'placement': [{'type': False}]}]}]
    return source, invalid


def exercise(binary, folder, name, source, gpu, reverse=False, threads=2):
    path = folder / f'{name}.rbp'
    path.write_bytes(fixture_wire(source, True))
    output = folder / f'{name}-{int(gpu)}-{int(reverse)}-{threads}.bin'
    result = command(['nice', '-n', '10', binary, path, output, *(['reverse'] if reverse else []),
                      '--threads', threads, '--gpu', 'on' if gpu else 'off'], timeout=300)
    return check(output.read_bytes(), source), result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, action='append', required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--prove-metal', action='store_true')
    args = parser.parse_args()
    folder = ROOT / 'build/bend/decoration-height-tests'
    folder.mkdir(parents=True, exist_ok=True)
    binary = ROOT / 'build/bend/decoration-heights'
    driver = ROOT / 'bend/tests/decoration-heights.bend'
    if not args.skip_build: build(DEFAULT_BEND, binary, driver)
    profiles = [(f'profile-{i}', json.loads(path.read_text())) for i, path in enumerate(args.profile)]
    fixture, invalid = fixtures()
    profiles += [('fixture', fixture), ('empty', {'decorations': []})]
    runs = []
    for gpu in (False, True):
        for name, source in profiles:
            checked, _ = exercise(binary, folder, name, source, gpu)
            reverse, _ = exercise(binary, folder, name, source, gpu, True)
            assert checked == reverse, (name, gpu, 'reverse order')
            runs.append(dict(profile=name, gpu_required=gpu, reverse_order='pass', **checked))
        for i, source in enumerate(invalid):
            path = folder / f'invalid-{i}.rbp'
            path.write_bytes(fixture_wire(source, True))
            output = folder / f'invalid-{int(gpu)}.bin'
            command(['nice', '-n', '10', binary, path, output, '--threads', '2',
                     '--gpu', 'on' if gpu else 'off'], timeout=120)
            assert output.read_bytes() == struct.pack('>2I', MASK, 760), (i, output.read_bytes())
    for name, _ in profiles:
        assert len({r['sampling_sha256'] for r in runs if r['profile'] == name}) == 1, name
    one_thread, _ = exercise(binary, folder, 'single-thread', fixture, False, threads=1)
    fixture_result = next(r for r in runs if r['profile'] == 'fixture')
    assert one_thread['sampling_sha256'] == fixture_result['sampling_sha256']
    observed = []
    if args.prove_metal:
        probe = ROOT / 'build/bend/decoration-heights-observer'
        archive = binary.with_suffix('.gpu')
        compile_metal_observer(DEFAULT_BEND, driver, probe, gpu_archive=archive)
        assert archive.read_bytes() == probe.with_suffix('.gpu').read_bytes()
        for name, source in profiles:
            if name == 'empty': continue
            checked, stderr = exercise(probe, folder, f'observer-{name}', source, True)
            normal = next(r for r in runs if r['profile'] == name and r['gpu_required'])
            assert checked['sampling_sha256'] == normal['sampling_sha256']
            commands = [float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)', stderr)]
            assert commands, (name, 'No actual Metal command buffers observed')
            observed.append(dict(profile=name, command_buffers=len(commands), device_ms=commands,
                                 sampling_sha256=checked['sampling_sha256']))
    sources = [ROOT / 'bend' / f'{name}.bend' for name in (
        'placement_height', 'registry_placement_height', 'registry_decoration_heights',
        'placement_random', 'word64', 'tests/decoration-heights')]
    sources.append(Path(__file__))
    report = dict(scope=__doc__.strip(), bend_version=command([DEFAULT_BEND, 'version']).stdout.strip(),
                  runs=runs, invalid_profiles_per_backend=len(invalid), one_cpu_thread='pass',
                  cpu_gpu_bytes_equal=True, metal_observation=observed,
                  profiles={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in args.profile},
                  sources={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
                  executable_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  gpu_archive_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    destination = folder / 'results.json'
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print('PASS:', destination)


if __name__ == '__main__': main()
