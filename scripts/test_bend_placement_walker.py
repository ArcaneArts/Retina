#!/usr/bin/env python3
"""Independent ordered-placement interpreter versus Bend CPU and observed Metal.

Checks candidate streams and live marker writes, not actual tree/feature writers
or the playable worker. Original registry profiles are transported unchanged.
Fixture limits report explicit prefixes; production cursors remain resumable.
"""
import argparse
import hashlib
import heapq
import json
import math
import re
import struct
import subprocess
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_decoration_counts import Random, provider, placement_noise, MASK64
from test_bend_decoration_heights import sample as height_sample
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, f32, mix_hash
from test_bend_placement_programs import World as BaseWorld, origin, signed, words
from test_bend_registry_noise import fixture_wire


class World(BaseWorld):
    def column(self, x, z):
        dx, dz = (x - self.ox) & MASK, (z - self.oz) & MASK
        if dx >= 32 or dz >= 32 or dx + dz * 32 == 1023: return None
        return dx + dz * 32

    def biome(self, x, y, z):
        col = self.column(x, z)
        return None if col is None else (1023 - col) % max(1, len(self.source['biomes']))

    def edit(self, at):
        if self.context % 3:
            x, y, z = at
            if self.context % 3 == 2: self.put(x, y - 1, z, 0)
            self.put(x, y, z, 1)


def shift(at, delta):
    result = tuple(a + b for a, b in zip(at, delta))
    return result if all(-(1 << 31) <= v < (1 << 31) for v in result) else None


def predicate(spec, world, at):
    kind = spec['type']
    if kind == 'true': return True
    if kind == 'material':
        probe = shift(at, spec['offset'])
        value = None if probe is None else world.material(*probe)
        return None if value is None else value in spec['allowed']
    if kind == 'not':
        value = predicate(spec['predicate'], world, at)
        return None if value is None else not value
    values = [predicate(p, world, at) for p in spec['predicates']]
    decisive = False if kind == 'all_of' else True
    if decisive in values: return decisive
    return None if None in values else not decisive


def terrain(op, world, at, recipe):
    x, y, z = at
    kind = op['type']
    if kind == 'heightmap':
        height = world.height(x, z, op['map'])
        return None if height is None else (x, height, z)
    if kind == 'block_predicate_filter': return at if predicate(op['predicate'], world, at) is True else None
    if kind == 'surface_water_depth_filter':
        a, b = world.height(x, z, 1), world.height(x, z, 3)
        return at if a is not None and b is not None and a-b <= op['max_water_depth'] else None
    if kind == 'surface_relative_threshold_filter':
        h = world.height(x, z, op['map'])
        return at if h is not None and h + op.get('min_inclusive', -(1 << 31)) <= y <= h + op.get('max_inclusive', (1 << 31)-1) else None
    if kind == 'biome':
        b = world.biome(x, y, z)
        return at if b is not None and recipe in world.source['biomes'][b].get('decorations', []) else None
    if kind == 'environment_scan':
        allowed = op.get('allowed_search_condition', {'type': 'true'})
        target = op['target_condition']
        if predicate(allowed, world, at) is not True: return None
        for i in range(op['max_steps'] + 1):
            if predicate(target, world, at) is True: return at
            if i == op['max_steps']: return None
            at = shift(at, (0, op['direction'], 0))
            if at is None or not -64 <= at[1] < 64: return None
            if predicate(allowed, world, at) is not True:
                return at if predicate(target, world, at) is True else None
        raise AssertionError('unreachable')
    raise AssertionError(kind)


def mixed(value):
    value = ((value ^ (value >> 30)) * 0xbf58476d1ce4e5b9) & MASK64
    value = ((value ^ (value >> 27)) * 0x94d049bb133111eb) & MASK64
    return value ^ (value >> 31)


FANS = {'count', 'noise_threshold_count', 'noise_based_count', 'count_on_every_layer', 'cuboid'}


def count(op, source, rng, at):
    kind = op['type']
    if kind == 'count': return max(0, provider(op['count'], rng))
    factor = 200 if kind == 'noise_threshold_count' else op['noise_factor']
    noise = placement_noise(source['decoration_noise']['permutation'], at[0] & MASK, at[2] & MASK, factor)
    if kind == 'noise_threshold_count': return op['below_noise'] if noise < f32(op['noise_level']) else op['above_noise']
    return max(0, min((1 << 31)-1, math.ceil(f32(f32(noise + f32(op.get('noise_offset', 0))) * f32(op['noise_to_count_ratio'])))))


def walk(source, context):
    """Small-step reference, with Python integers/dicts and heapq rather than
    Bend word-pairs, persistent treaps or a leftist heap. Each task is one unit.
    """
    recipes = source['decorations']
    groups, seen_salts = [], {}
    for i, recipe in enumerate(recipes):
        salt = recipe.get('placement_salt', recipe['salt']) & MASK64
        groups.append(seen_salts.setdefault(salt, i))
    selected = range(len(recipes)) if context in (0, 32) else [context-1]
    ox, oz = origin(context)
    world, cache, heap = World(source, context), {}, []
    world_seed = (((0x80000000 ^ (context * 7919)) & MASK) << 32) | mix_hash((context * 811) & MASK)
    serial, steps, result = 0, 0, []

    def push(recipe, index, at, state, path, family=None):
        nonlocal serial
        if at is None: return
        task_path = path if family is None else path + ((family[1], family[2]) if family[0] == 'layer' else (family[2],))
        priority = 2 if family is None else 0 if family[0] == 'layer' else 1
        entry = (recipe, index, at, state, path, family)
        heapq.heappush(heap, (groups[recipe], task_path, priority, recipe, serial, entry))
        serial += 1

    for r in selected:
        if r >= len(recipes) or recipes[r].get('placement') is None: continue
        salt = recipes[r].get('placement_salt', recipes[r]['salt']) & MASK64
        seed = world_seed ^ salt ^ mixed((ox >> 4) & MASK) ^ mixed(((oz >> 4) & MASK) << 32)
        push(r, 0, (ox, -40, oz), seed, ())

    while heap and steps < 8192 and len(result) < 64:
        group, _, _, _, _, entry = heapq.heappop(heap)
        r, index, at, state, path, family = entry
        program = recipes[r]['placement']
        rng = Random(state)
        steps += 1
        if family is not None:
            kind = family[0]
            if kind == 'count':
                _, remaining, ordinal = family
                if not remaining: continue
                child_seed = rng.next()
                push(r, index, at, rng.state, path, ('count', remaining-1, ordinal+1))
                push(r, index+1, at, child_seed, path+(ordinal,))
            elif kind == 'cuboid':
                _, cell, ordinal, w, h, length, edges, interior = family
                if cell >= (w+1)*(h+1)*(length+1): continue
                dx, dy, dz = cell//((h+1)*(length+1)), (cell//(length+1))%(h+1), cell%(length+1)
                planes = (dx in (0, w)) + (dy in (0, h)) + (dz in (0, length))
                emitted = interior if planes == 0 else True if planes == 1 else edges
                child_seed = rng.next() if emitted else 0
                push(r, index, at, rng.state, path, ('cuboid', cell+1, ordinal+int(emitted), w, h, length, edges, interior))
                if emitted: push(r, index+1, shift(at, (dx, dy, dz)), child_seed, path+(ordinal,))
            else:
                _, layer, attempt, found = family
                limit = max(0, provider(program[index]['count'], rng))
                if attempt >= limit:
                    if found: push(r, index, at, rng.state, path, ('layer', layer+1, 0, False))
                    continue
                dx, dz = rng.integer(0, 15), rng.integer(0, 15)
                key = group, index, path+(layer, attempt)
                if key not in cache:
                    p = shift(at, (dx, 0, dz))
                    y = None if p is None else world.ground(p[0], p[2], layer)
                    cache[key] = None if y is None else (p[0], y, p[2])
                value = cache[key]
                push(r, index, at, rng.state, path, ('layer', layer, attempt+1, found or value is not None))
                push(r, index+1, value, rng.state, path+(layer, attempt))
            continue
        if index == len(program):
            if -64 <= at[1] < 64:
                before = world.height(at[0], at[2], 4)
                world.edit(at)
                after = world.height(at[0], at[2], 4)
                result.append([r, group, *at, state >> 32, state & MASK, len(path), *path,
                               MASK if before is None else before, MASK if after is None else after, len(heap), steps])
            continue
        op = program[index]; kind = op['type']
        if kind in ('count', 'noise_threshold_count', 'noise_based_count'):
            remaining = count(op, source, rng, at)
            push(r, index, at, rng.state, path, ('count', remaining, 0)); continue
        if kind == 'cuboid':
            h = provider(op['y_size'], rng)
            w, length = provider(op['xz_size'], rng), provider(op['xz_size'], rng)
            push(r, index, at, rng.state, path, ('cuboid', 0, 0, w, h, length, op.get('include_edges', True), op.get('include_interior', True))); continue
        if kind == 'count_on_every_layer':
            push(r, index, at, state, path, ('layer', 0, 0, False)); continue
        if kind == 'in_square': at = shift(at, (rng.integer(0, 15), 0, rng.integer(0, 15)))
        elif kind == 'offset': at = shift(at, tuple(provider(op.get(axis, 0), rng) for axis in ('x', 'y', 'z')))
        elif kind == 'height_range': at = (at[0], height_sample(op['height'], rng, -64, 128), at[2])
        elif kind in ('select', 'rarity_filter', 'random_chance'):
            unit = rng.unit()
            low, high = (f32(op['min']), f32(op['max'])) if kind == 'select' else (0, f32(1/f32(op['chance'])) if kind == 'rarity_filter' else f32(op['chance']))
            if not low <= unit < high: at = None
        else:
            key = group, index, path
            remember = any(m['type'] in FANS for m in program[index+1:])
            if not remember: at = terrain(op, world, at, r)
            else:
                if key not in cache: cache[key] = terrain(op, world, at, r)
                at = cache[key]
        push(r, index+1, at, rng.state, path)
    status = 0 if not heap else 1 if len(result) == 64 else 2
    probes = [world.height(ox, oz, m) for m in range(6)]
    return [context, len(result), status, steps, len(heap), len(cache),
            *(v for row in result for v in row), *(MASK if v is None else v for v in probes)]


def fixtures(permutation):
    def ct(n): return dict(type='count', count=n)
    def hm(n=4): return dict(type='heightmap', map=n)
    def layer(n): return dict(type='count_on_every_layer', count=n)
    def filt(p): return dict(type='block_predicate_filter', predicate=p)
    air = dict(type='material', offset=[0, 0, 0], allowed=[0])
    stone = dict(type='material', offset=[0, 0, 0], allowed=[1])
    programs = [
        [ct(3), hm()], [hm(), ct(3)],
        [layer(dict(type='uniform', min=1, max=4))], [layer(2)], [layer(2)],
        [ct(8), dict(type='in_square'), hm(), dict(type='select', min=0., max=.5)],
        [ct(8), dict(type='in_square'), hm(), dict(type='select', min=.5, max=1.)],
        *([dict(type='cuboid', xz_size=2, y_size=2, include_edges=e, include_interior=i)] for e in (False, True) for i in (False, True)),
        [ct(5), dict(type='height_range', height=dict(type='uniform', min_inclusive=dict(above_bottom=3), max_inclusive=dict(below_top=1)))],
        [ct(2), hm(1), dict(type='environment_scan', direction=-1, max_steps=32, target_condition=stone, allowed_search_condition=dict(type='not', predicate=stone)), dict(type='offset', y=1)],
        [filt(stone), ct(3)],
        [ct(4), dict(type='offset', x=dict(type='uniform', min=0, max=31), y=-4, z=dict(type='uniform', min=0, max=31)), dict(type='biome')],
        [dict(type='noise_threshold_count', noise_level=.1, below_noise=2, above_noise=7), dict(type='in_square'), hm()],
        [dict(type='noise_based_count', noise_to_count_ratio=6, noise_factor=80., noise_offset=.5), dict(type='in_square'), hm()],
        [dict(type='offset', x=17)],
        [dict(type='rarity_filter', chance=3), ct(2)],
        [dict(type='random_chance', chance=.25), ct(4)],
        [dict(type='height_range', height=dict(absolute=64))],
        [dict(type='height_range', height=dict(absolute=-65))],
        [ct(0)], [dict(type='surface_water_depth_filter', max_water_depth=4), hm(3), dict(type='surface_relative_threshold_filter', map=4, min_inclusive=-2, max_inclusive=2)],
        [ct(4096), ct(4096)], None, [],
        [ct(3), filt(air)], [filt(air), ct(3)],
        [ct(2), dict(type='in_square'), hm(0), hm(2), hm(5)],
        [ct(2), dict(type='offset', y=30), dict(type='environment_scan', direction=-1, max_steps=32, target_condition=stone)],
    ]
    recipes = [dict(salt=i+200, placement=p) for i, p in enumerate(programs)]
    recipes[4]['placement_salt'] = recipes[3]['salt']
    recipes[6]['placement_salt'] = recipes[5]['salt']
    source = dict(materials=['air', 'stone', 'water', 'bedrock', 'leaf', 'plant', 'lava', 'other'],
                  heightmap_masks=[0, 63, 51, 63, 39, 3, 51, 15], material_flags=[64, 0, 64, 128, 16, 64, 64, 2],
                  water=2, lava=6, bedrock=3, decoration_biome_3d=True,
                  biomes=[{'decorations': list(range(0, len(recipes), 2))}, {'decorations': list(range(1, len(recipes), 2))}],
                  decoration_noise={'permutation': permutation}, decorations=recipes)
    empty = dict(source, decorations=[], biomes=[{'decorations': []}])
    return source, empty


def decode(data):
    values = list(struct.unpack('>'+'I'*(len(data)//4), data)); n = values[0]; p = 1; cases = []
    for _ in range(n):
        header = values[p:p+6]; p += 6; records = []
        for _ in range(header[1]):
            size = 12 + values[p+7]
            records.append(values[p:p+size]); p += size
        cases.append(dict(context=header[0], count=header[1], status=header[2], steps=header[3], queue=header[4], cache=header[5], records=records, probes=values[p:p+6])); p += 6
    assert p == len(values)
    return cases


def expected(source): return words([33, *(v for i in range(33) for v in walk(source, i))])


def check(data, reference, name):
    if data != reference:
        a, b = decode(data), decode(reference)
        for i, (av, bv) in enumerate(zip(a, b)):
            if av == bv: continue
            scalars = {k: (av[k], bv[k]) for k in av if k != 'records' and av[k] != bv[k]}
            first = next(((j, ar, br) for j, (ar, br) in enumerate(zip(av['records'], bv['records'])) if ar != br), None)
            raise AssertionError((name, 'case', i, scalars, 'first record', first))
        raise AssertionError((name, 'different case counts', len(a), len(b)))
    cases = decode(data)
    return dict(cases=len(cases), candidates=sum(c['count'] for c in cases),
                exhausted=sum(c['status'] == 0 for c in cases), explicit_prefixes=sum(c['status'] != 0 for c in cases),
                max_tasks=max(c['steps'] for c in cases), sha256=hashlib.sha256(data).hexdigest())


def semantic_checks(data):
    cases = decode(data)
    y = lambda c: [signed(r[3]) for r in cases[c]['records']]
    assert y(1) == [-40, -39, -38], ('live height after count', y(1))
    assert y(2) == [-40]*3, ('parent height before count', y(2))
    assert [cases[i+1]['count'] for i in range(7, 11)] == [6, 7, 26, 27], 'cuboid boundary planes'
    assert cases[14]['count'] == 0 and cases[14]['cache'] == 1, 'failed parent result retained'
    assert [r[8:8+r[7]] for r in cases[3]['records']] == [[0, 0], [0, 1], [0, 2], [0, 3], [1, 0], [1, 1]], 'layer loop resamples its count'
    branches = [r for r in cases[32]['records'] if r[0] in (5, 6)]
    assert len(branches) == 8 and {r[0] for r in branches} == {5, 6} and {tuple(r[8:8+r[7]]) for r in branches} == {(i,) for i in range(8)}, 'selector branches partition one budget'
    assert cases[18]['count'] == 0, 'signed position overflow'
    assert cases[21]['count'] == cases[22]['count'] == 0, 'final Y clipping'
    huge = cases[25]
    assert huge['count'] == 64 and huge['status'] == 1 and huge['queue'] <= 3
    assert huge['cache'] == 0 and huge['steps'] < 256, 'nested 16,777,216 candidates must stay lazy'
    assert cases[26]['count'] == 0 and cases[27]['count'] == 1, 'inactive versus empty programs'
    assert cases[28]['count'] == 1 and cases[29]['count'] == 3, 'live filter versus parent filter'
    pairs = {}
    for row in cases[32]['records']:
        if row[0] in (3, 4): pairs.setdefault(tuple(row[8:8+row[7]]), []).append(row)
    assert pairs and all(len(v) == 2 and v[0][2:7] == v[1][2:7] for v in pairs.values()), 'shared layer grounds resolved before either branch writes'
    return dict(live_height='pass', parent_height='pass', live_and_parent_filters='pass',
                shared_layer_grounds=len(pairs), selector_shared_budget=8, layer_resampling='pass', failed_parent_cache='pass', cuboid_flag_combinations='pass', signed_overflow='pass', final_y_clip='pass',
                nested_candidates=4096**2, prefix_candidates=64, prefix_queue=huge['queue'], prefix_tasks=huge['steps'])


def exercise(binary, folder, name, source, reference, gpu, reverse=False, fuel=128, threads=2):
    profile = folder / f'{name}.rbp'; profile.write_bytes(fixture_wire(source, True))
    output = folder / f'{name}-{int(gpu)}-{int(reverse)}-{fuel}-{threads}.bin'
    try:
        result = command(['nice', '-n', '10', binary, profile, output, fuel, *(['reverse'] if reverse else []),
                          '--threads', threads, '--gpu', 'on' if gpu else 'off'], timeout=300)
    except subprocess.CalledProcessError as error:
        raise AssertionError((name, gpu, error.returncode, error.stderr)) from error
    data = output.read_bytes()
    return check(data, reference, name), data, result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, action='append', required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--prove-metal', action='store_true')
    args = parser.parse_args()
    folder = ROOT/'build/bend/placement-walker-tests'; folder.mkdir(parents=True, exist_ok=True)
    binary, driver = ROOT/'build/bend/placement-walker', ROOT/'bend/tests/placement-walker.bend'
    if not args.skip_build: build(DEFAULT_BEND, binary, driver)
    profiles = [(f'profile-{i}', json.loads(path.read_text())) for i, path in enumerate(args.profile)]
    fixture, empty = fixtures(profiles[0][1]['decoration_noise']['permutation'])
    profiles += [('fixture', fixture), ('empty', empty)]
    references = {name: expected(source) for name, source in profiles}
    semantics = semantic_checks(references['fixture'])
    runs = []
    for gpu in (False, True):
        for name, source in profiles:
            checked, data, _ = exercise(binary, folder, name, source, references[name], gpu)
            reverse, _, _ = exercise(binary, folder, name, source, references[name], gpu, True)
            fine, _, _ = exercise(binary, folder, name, source, references[name], gpu, fuel=1)
            assert checked == reverse == fine
            if name == 'fixture': assert semantic_checks(data) == semantics
            runs.append(dict(profile=name, gpu_required=gpu, reverse_order='pass', one_task_pauses='pass', **checked))
    one, _, _ = exercise(binary, folder, 'single-thread', fixture, references['fixture'], False, threads=1)
    assert one['sha256'] == hashlib.sha256(references['fixture']).hexdigest()
    observed = []
    if args.prove_metal:
        probe = ROOT/'build/bend/placement-walker-observer'
        compile_metal_observer(DEFAULT_BEND, driver, probe, gpu_archive=binary.with_suffix('.gpu'))
        assert probe.with_suffix('.gpu').read_bytes() == binary.with_suffix('.gpu').read_bytes()
        for name, source in profiles:
            checked, _, stderr = exercise(probe, folder, f'observer-{name}', source, references[name], True)
            commands = [float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)', stderr)]
            assert commands, (name, 'No actual Metal command buffers observed')
            observed.append(dict(profile=name, command_buffers=len(commands), device_ms=commands, sha256=checked['sha256']))
    paths = [ROOT/'bend'/f'{name}.bend' for name in ('placement_cursor', 'placement_walk', 'tests/placement-walker', 'tests/placement-walker-world')]
    paths.append(Path(__file__))
    report = dict(scope=__doc__.strip(), bend_version=command([DEFAULT_BEND, 'version']).stdout.strip(),
                  runs=runs, semantic_controls=semantics, one_cpu_thread='pass', cpu_gpu_bytes_equal=True,
                  metal_observation=observed, profiles={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in args.profile},
                  sources={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
                  native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(), gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    (ROOT/'docs/benchmarks/bend-placement-walker-correctness.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k: report[k] for k in ('runs', 'semantic_controls', 'metal_observation')}, indent=2))


if __name__ == '__main__': main()
