#!/usr/bin/env python3
"""Check Bend placement-program projection and live terrain queries independently.

Original registry profiles cross the structural wire unchanged. This component
does not execute the ordered placement walker or place trees/surface features.
"""
import argparse
import copy
import hashlib
import json
import math
import re
import struct
from pathlib import Path

from test_bend_compression import ROOT, DEFAULT_BEND, build, command
from test_bend_decoration_counts import Random, provider, placement_noise
from test_bend_decoration_heights import sample as height_sample
from test_bend_engine import compile_metal_observer
from test_bend_noise import MASK, f32, mix_hash
from test_bend_registry_noise import fixture_wire


def bits(value): return struct.unpack('>I', struct.pack('>f', value))[0]
def signed(value): return value - (1 << 32) if value & (1 << 31) else value
def words(values): return struct.pack('>' + 'I' * len(values), *(v & MASK for v in values))


def predicate(p):
    kind = p['type']
    if kind == 'material': return [0, *p['offset'], len(p['allowed']), *p['allowed']]
    if kind in ('all_of', 'any_of'):
        return [1 if kind == 'all_of' else 2, len(p['predicates']),
                *(v for child in p['predicates'] for v in predicate(child))]
    if kind == 'not': return [3, *predicate(p['predicate'])]
    if kind == 'true': return [4]
    raise AssertionError(kind)


def sampled(source, recipe, modifier, spec, kind):
    result = []
    for i in range(4):
        rng = Random((((0x80000000 ^ (recipe * 7919)) & MASK) << 32) |
                     mix_hash((modifier * 811 + i) & MASK))
        if kind == 'height': value = height_sample(spec, rng, -64, 384)
        elif kind == 'count' and spec['type'] == 'noise_threshold_count':
            noise = placement_noise(source['decoration_noise']['permutation'],
                                    29990000 + i * 37, (-30000000 + i * 19) & MASK, 200.)
            value = spec['below_noise'] if noise < f32(spec['noise_level']) else spec['above_noise']
        elif kind == 'count' and spec['type'] == 'noise_based_count':
            noise = placement_noise(source['decoration_noise']['permutation'],
                                    29990000 + i * 37, (-30000000 + i * 19) & MASK,
                                    spec['noise_factor'])
            value = max(0, min((1 << 31) - 1, math.ceil(f32(
                f32(noise + f32(spec.get('noise_offset', 0))) * f32(spec['noise_to_count_ratio'])))))
        else: value = provider(spec, rng)
        result += [value & MASK, rng.state >> 32, rng.state & MASK]
    return result


def modifier(source, r, m, op):
    kind = op['type']
    if kind == 'count': return [0, *sampled(source, r, m, op['count'], 'integer')]
    if kind in ('noise_threshold_count', 'noise_based_count'):
        return [0, *sampled(source, r, m, op, 'count')]
    if kind == 'count_on_every_layer': return [1, *sampled(source, r, m, op['count'], 'integer')]
    if kind == 'in_square': return [2]
    if kind == 'cuboid':
        return [3, int(op.get('include_edges', True)), int(op.get('include_interior', True)),
                *sampled(source, r, m, op['xz_size'], 'integer'),
                *sampled(source, r, m, op['y_size'], 'integer')]
    if kind == 'heightmap': return [4, op['map']]
    if kind == 'height_range': return [5, *sampled(source, r, m, op['height'], 'height')]
    if kind == 'environment_scan':
        return [6, op['direction'], op['max_steps'], *predicate(op['target_condition']),
                *predicate(op.get('allowed_search_condition', {'type': 'true'}))]
    if kind == 'offset':
        return [7, *(v for axis in ('x', 'y', 'z') for v in sampled(source, r, m, op.get(axis, 0), 'integer'))]
    if kind == 'block_predicate_filter': return [8, *predicate(op['predicate'])]
    if kind == 'surface_water_depth_filter': return [9, op['max_water_depth']]
    if kind == 'surface_relative_threshold_filter':
        return [10, op['map'], op.get('min_inclusive', -(1 << 31)), op.get('max_inclusive', (1 << 31) - 1)]
    if kind == 'biome': return [11]
    if kind == 'select': return [12, bits(op['min']), bits(op['max'])]
    if kind == 'rarity_filter': return [13, op['chance']]
    if kind == 'random_chance': return [14, bits(op['chance'])]
    raise AssertionError(kind)


def expected_recipes(source, reverse):
    groups = {}
    records = []
    for r, recipe in enumerate(source['decorations']):
        salt = recipe.get('placement_salt', recipe['salt']) & ((1 << 64) - 1)
        group = groups.setdefault(salt, r)
        ops = recipe.get('placement')
        records.append([r, group, salt >> 32, salt & MASK, int(ops is not None), len(ops or []),
                        *(v for m, op in enumerate(ops or []) for v in modifier(source, r, m, op))])
    return [v for row in (reversed(records) if reverse else records) for v in row]


def expected_policy(source):
    result = [source['water'], source['lava'], source['bedrock'], int(source.get('decoration_biome_3d', False))]
    for field in ('heightmap_masks', 'material_flags'):
        result += [len(source[field]), *source[field]]
    result += [len(source['biomes'])]
    for biome in source['biomes']:
        membership = biome.get('decorations', [])
        result += [len(membership), *membership]
    return result


def origin(i):
    return [(29990000, -30000000), (-128, 713),
            ((1 << 31) - 16, -(1 << 31)), (-(1 << 31), (1 << 31) - 16)][i % 4]


class World:
    def __init__(self, source, context):
        self.source, self.context = source, context
        self.ox, self.oz = origin(context)
        self.patch = {}

    def column(self, x, z):
        dx, dz = (x - self.ox) & MASK, (z - self.oz) & MASK
        if dx >= 4 or dz >= 4 or dx + dz * 4 == 15: return None
        return dx + dz * 4

    def material(self, x, y, z):
        if not -64 <= y < 64: return 0
        if (x, y, z) in self.patch: return self.patch[x, y, z]
        if self.column(x, z) is None: return None
        offset = y + 64
        if offset < 1: return self.source['bedrock']
        if offset < 10: return 1
        if offset < 13: return 0
        if offset < 20: return 1
        if offset < 24: return self.source['water']
        return 0

    def put(self, x, y, z, value):
        if self.column(x, z) is not None and -64 <= y < 64 and 0 <= value < len(self.source['materials']):
            self.patch[x, y, z] = value

    def height(self, x, z, map):
        if self.column(x, z) is None: return None
        for y in range(63, -65, -1):
            material = self.material(x, y, z)
            if self.source['heightmap_masks'][material] & (1 << map): return y + 1
        return -64

    def biome(self, x, y, z):
        col = self.column(x, z)
        if col is None: return None
        if self.source.get('decoration_biome_3d', False) and self.context % 2 == 0 and y < -52:
            return ((y + 64) // 4) % max(1, len(self.source['biomes']))
        return (15 - col) % max(1, len(self.source['biomes']))

    def empty(self, m):
        return m == 0 or self.source['material_flags'][m] & 64 or m in (self.source['water'], self.source['lava'])

    def ground(self, x, z, layer):
        start = self.height(x, z, 4)
        if start is None: return None
        for y in range(start, -64, -1):
            below, current = self.material(x, y - 1, z), self.material(x, y, z)
            if below is None or current is None: return None
            if self.empty(current) and not self.empty(below) and below != self.source['bedrock'] and not self.source['material_flags'][below] & 128:
                if layer == 0: return y
                layer -= 1
        return None

    def scan(self, x, z, steps, allowed, target):
        y = -40
        if not allowed(self.material(x, y, z)): return None
        for _ in range(steps):
            if target(self.material(x, y, z)): return y
            y -= 1
            if y < -64: return None
            if not allowed(self.material(x, y, z)): break
        return y if target(self.material(x, y, z)) else None


def expected_world(source):
    result = []
    for context in range(32):
        w = World(source, context)
        for i in range(context):
            w.put(w.ox + i % 4, -60 + ((i % 8) * 17) % 40, w.oz + (i // 4) % 2,
                  [0, 1, source['water']][i % 3])
        x, y, z = w.ox + context % 4, -64 + (context * 7) % 128, w.oz + (context // 4) % 4
        material = w.material(x, y, z)
        h1, h3, h4, biome = w.height(x, z, 1), w.height(x, z, 3), w.height(x, z, 4), w.biome(x, y, z)
        membership = [] if biome is None or biome >= len(source['biomes']) else source['biomes'][biome].get('decorations', [])
        values = [material, *(w.height(x, z, m) for m in range(6)),
                  *(w.ground(x, z, layer) for layer in range(4)), biome, None, None, material,
                  2 if material is None else int(material == 1), 2, 0, 1, 2, 2,
                  w.scan(x, z, 32, lambda m: m is not None and m != 1, lambda m: m == 1),
                  None, w.scan(x, z, 3, lambda m: True, lambda m: m == 1),
                  y if h4 is not None and h4 - (1 << 31) <= y <= h4 + (1 << 31) - 1 else None,
                  y if h1 is not None and h3 is not None and h1 - h3 <= 4 else None,
                  y if 0 in membership else None, h4, material, None,
                  World(source, context).height(x, z, 4), 0]
        assert len(values) == 32
        result += [MASK if v is None else v & MASK for v in values]
    return result


def check(data, source, reverse):
    recipe_data = expected_recipes(source, reverse)
    header = [len(source['decorations']), *expected_policy(source)]
    expected = words([*header, *recipe_data, *expected_world(source)])
    if data != expected:
        actual_words = struct.unpack('>' + 'I' * (len(data) // 4), data)
        expected_words = struct.unpack('>' + 'I' * (len(expected) // 4), expected)
        differing = [(i, a, b) for i, (a, b) in enumerate(zip(actual_words, expected_words)) if a != b]
        raise AssertionError((len(data), len(expected), differing[:20], data[:8]))
    canonical = words([*header, *expected_recipes(source, False), *expected_world(source)])
    return dict(recipes=len(source['decorations']),
                modifiers=sum(len(r.get('placement') or []) for r in source['decorations']),
                world_queries=1024, bytes=len(data), sha256=hashlib.sha256(canonical).hexdigest())


def fixtures(permutation):
    material = dict(type='material', offset=[0, -1, 0], allowed=[1, 3])
    ops = [dict(type='count', count=2), dict(type='count_on_every_layer', count={'type': 'uniform', 'min': 0, 'max': 4}),
           dict(type='noise_threshold_count', noise_level=.25, below_noise=2, above_noise=7),
           dict(type='noise_based_count', noise_to_count_ratio=6, noise_factor=80., noise_offset=.5),
           dict(type='in_square'), dict(type='cuboid', xz_size=3, y_size=4),
           dict(type='cuboid', xz_size={'type': 'uniform', 'min': 1, 'max': 16}, y_size=1, include_edges=False, include_interior=True),
           *(dict(type='heightmap', map=i) for i in range(6)),
           dict(type='height_range', height={'type': 'uniform', 'min_inclusive': {'above_bottom': 3}, 'max_inclusive': {'below_top': 1}}),
           dict(type='environment_scan', direction=-1, target_condition=material, max_steps=32),
           dict(type='environment_scan', direction=1, target_condition=material, allowed_search_condition={'type': 'not', 'predicate': material}, max_steps=3),
           dict(type='offset', x={'type': 'uniform', 'min': -3, 'max': 3}, y=-17),
           dict(type='block_predicate_filter', predicate={'type': 'all_of', 'predicates': [material, {'type': 'any_of', 'predicates': [{'type': 'true'}, {'type': 'not', 'predicate': material}]}]}),
           dict(type='surface_water_depth_filter', max_water_depth=-1),
           dict(type='surface_relative_threshold_filter', map=4),
           dict(type='surface_relative_threshold_filter', map=1, min_inclusive=-5, max_inclusive=9),
           dict(type='biome'), dict(type='select', min=.2, max=.8), dict(type='rarity_filter', chance=7),
           dict(type='random_chance', chance=.125)]
    source = dict(materials=['air', 'stone', 'water', 'bedrock', 'leaf', 'plant', 'lava', 'other'],
                  heightmap_masks=[0, 63, 51, 63, 39, 3, 51, 15], material_flags=[64, 0, 64, 128, 16, 64, 64, 2],
                  water=2, lava=6, bedrock=3, decoration_biome_3d=True, biomes=[{'decorations': [0, 2]}, {'decorations': [1]}],
                  decoration_noise={'permutation': permutation}, decorations=[
                      dict(salt=-9223372036854775807, placement_salt=123, placement=ops),
                      dict(salt=123456789, placement_salt=123, placement=list(reversed(ops))),
                      dict(salt=123, placement=[]), dict(salt=123, placement=None), dict(salt=9223372036854775807)])
    invalid = []
    def bad_op(op):
        value = copy.deepcopy(source)
        value['decorations'][0]['placement'] = [op]
        invalid.append(value)
    for op in [dict(type='unknown'), dict(type='heightmap', map=6), dict(type='heightmap', map=-1),
               dict(type='rarity_filter', chance=0), dict(type='random_chance', chance=1.01),
               dict(type='select', min=.8, max=.2), dict(type='count', count=-1),
               dict(type='count_on_every_layer', count=257), dict(type='cuboid', xz_size=0, y_size=1),
               dict(type='cuboid', xz_size=17, y_size=1), dict(type='cuboid', xz_size=1, y_size=1, include_edges=1),
               dict(type='height_range', height={'absolute': True}),
               dict(type='environment_scan', direction=0, max_steps=3, target_condition=material),
               dict(type='environment_scan', direction=-1, max_steps=33, target_condition=material),
               dict(type='environment_scan', direction=-1, max_steps=3),
               dict(type='surface_relative_threshold_filter', map=7),
               dict(type='offset', x={'type': 'unknown'}), dict(type='surface_water_depth_filter', max_water_depth=1.5)]: bad_op(op)
    for p in [None, {}, {'type': 'not'}, {'type': 'truee'}, {'type': 'all_of', 'predicates': None},
              dict(type='material', offset=[0, 0, 0]), dict(type='material', offset=[0, 0], allowed=[1]),
              dict(type='material', offset=[18, 0, 0], allowed=[1]),
              dict(type='material', offset=[0, 0, 0], allowed=[8]),
              dict(type='material', offset=[0, 0, 0], allowed=[3, 1]),
              dict(type='material', offset=[0, 0, 0], allowed=[1, 1])]: bad_op(dict(type='block_predicate_filter', predicate=p))
    for field, value in [('materials', []), ('heightmap_masks', [0]), ('material_flags', [0]),
                         ('heightmap_masks', [0, 64, 51, 63, 39, 3, 51, 15]),
                         ('material_flags', [64, 256, 64, 128, 16, 64, 64, 2]),
                         ('water', 8), ('lava', -1), ('bedrock', True), ('decoration_biome_3d', 1), ('biomes', None),
                         ('biomes', [{'decorations': [5]}]), ('decorations', None)]:
        value_source = copy.deepcopy(source); value_source[field] = value; invalid.append(value_source)
    for field, value in [('placement', {}), ('salt', True), ('placement_salt', '123')]:
        value_source = copy.deepcopy(source); value_source['decorations'][0][field] = value; invalid.append(value_source)
        if field == 'salt': del value_source['decorations'][0]['placement_salt']
    # Empty programs/membership are valid, with the same domain-query fixture.
    empty = copy.deepcopy(source); empty['decorations'] = []; empty['biomes'] = [{'decorations': []}]
    del empty['decoration_biome_3d']
    return source, empty, invalid


def exercise(binary, folder, name, source, gpu, reverse=False, threads=2):
    path = folder / f'{name}.rbp'; path.write_bytes(fixture_wire(source, True))
    output = folder / f'{name}-{int(gpu)}-{int(reverse)}-{threads}.bin'
    result = command(['nice', '-n', '10', binary, path, output, *(['reverse'] if reverse else []),
                      '--threads', threads, '--gpu', 'on' if gpu else 'off'], timeout=300)
    return check(output.read_bytes(), source, reverse), result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, action='append', required=True)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--prove-metal', action='store_true')
    args = parser.parse_args()
    folder = ROOT / 'build/bend/placement-program-tests'; folder.mkdir(parents=True, exist_ok=True)
    binary, driver = ROOT / 'build/bend/placement-programs', ROOT / 'bend/tests/placement-programs.bend'
    if not args.skip_build: build(DEFAULT_BEND, binary, driver)
    profiles = [(f'profile-{i}', json.loads(path.read_text())) for i, path in enumerate(args.profile)]
    fixture, empty, invalid = fixtures(profiles[0][1]['decoration_noise']['permutation'])
    profiles += [('fixture', fixture), ('empty', empty)]
    runs = []
    for gpu in (False, True):
        for name, source in profiles:
            checked, _ = exercise(binary, folder, name, source, gpu)
            reverse, _ = exercise(binary, folder, name, source, gpu, True)
            assert checked == reverse, (name, gpu, 'reverse order')
            runs.append(dict(profile=name, gpu_required=gpu, reverse_order='pass', **checked))
        for i, source in enumerate(invalid):
            path = folder / f'invalid-{i}.rbp'; path.write_bytes(fixture_wire(source, True))
            output = folder / f'invalid-{int(gpu)}.bin'
            command(['nice', '-n', '10', binary, path, output, '--threads', '2', '--gpu', 'on' if gpu else 'off'], timeout=120)
            assert output.read_bytes() == words([MASK, 761]), (i, output.read_bytes()[:32])
    for name, _ in profiles:
        assert len({r['sha256'] for r in runs if r['profile'] == name}) == 1, name
    one, _ = exercise(binary, folder, 'single-thread', fixture, False, threads=1)
    assert one['sha256'] == next(r['sha256'] for r in runs if r['profile'] == 'fixture')
    observed = []
    if args.prove_metal:
        probe = ROOT / 'build/bend/placement-programs-observer'
        archive = binary.with_suffix('.gpu')
        compile_metal_observer(DEFAULT_BEND, driver, probe, gpu_archive=archive)
        assert archive.read_bytes() == probe.with_suffix('.gpu').read_bytes()
        for name, source in profiles:
            checked, stderr = exercise(probe, folder, f'observer-{name}', source, True)
            assert checked['sha256'] == next(r['sha256'] for r in runs if r['profile'] == name)
            commands = [float(n) for n in re.findall(r'BEND_METAL_DISPATCH device_ms=([0-9.]+)', stderr)]
            assert commands, (name, 'No actual Metal command buffers observed')
            observed.append(dict(profile=name, command_buffers=len(commands), device_ms=commands, sha256=checked['sha256']))
    sources = [ROOT / 'bend' / (name + '.bend') for name in (
        'placement_data', 'placement_world', 'placement_checks', 'registry_placement',
        'registry_placement_predicate', 'registry_placement_policy', 'tests/placement-programs', 'tests/placement-world-fixtures')]
    sources.append(Path(__file__))
    report = dict(scope=__doc__.strip(), bend_version=command([DEFAULT_BEND, 'version']).stdout.strip(),
                  runs=runs, invalid_profiles_per_backend=len(invalid), one_cpu_thread='pass',
                  cpu_gpu_bytes_equal=True, metal_observation=observed,
                  profiles={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in args.profile},
                  sources={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
                  native_sha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                  gpu_sha256=hashlib.sha256(binary.with_suffix('.gpu').read_bytes()).hexdigest())
    destination = ROOT / 'docs/benchmarks/bend-placement-program-correctness.json'
    destination.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ('runs', 'invalid_profiles_per_backend', 'metal_observation')}, indent=2))


if __name__ == '__main__': main()
