"""Small Python-only format/provenance tests; no generator or native compiler."""
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
from test_benchmark_support import fixture
from test_bend_formats import iter_region, read_region
from test_bend_chunks import MAP_NAMES
from validate_backend_output import audit, digest, finish_control, validate


def text(value):
    data = value.encode('utf-8')
    return struct.pack('>H', len(data))+data


def encode(kind, value):
    if kind in (1, 3):
        return struct.pack({1: '>b', 3: '>i'}[kind], value)
    if kind == 7:
        return struct.pack('>i', len(value))+value
    if kind == 8:
        return text(value)
    if kind == 9:
        element, items = value
        return struct.pack('>Bi', element, len(items))+b''.join(encode(element, v) for v in items)
    if kind == 10:
        return b''.join(bytes([t])+text(n)+encode(t, v) for n, (t, v) in value.items())+b'\0'
    if kind == 12:
        return struct.pack('>i', len(value))+struct.pack(f'>{len(value)}q', *value)
    raise AssertionError(kind)


def chunk(slot):
    lit = 0 < slot%32 < 31 and 0 < slot//32 < 31
    section = {'Y': (1, -1),
               'block_states': (10, {'palette': (9, (10, [{'id': (8, 'minecraft:air')}]))}),
               'biomes': (10, {'palette': (9, (8, ['minecraft:plains']))})}
    sections = [section]
    if lit:
        sections = [{'Y': (1, -2)}, section, {'Y': (1, 0)}]
        for s in sections:
            s['SkyLight'] = (7, b'\xff'*2048)
            # Missing all-zero BlockLight is legal.
    return {'xPos': (3, -32+slot%32), 'zPos': (3, slot//32), 'yPos': (3, -1),
            'DataVersion': (3, 5023), 'Status': (8, 'minecraft:light' if lit else 'minecraft:features'),
            'isLightOn': (1, int(lit)), 'sections': (9, (10, sections)),
            'Heightmaps': (10, {name: (12, [0]*22) for name in MAP_NAMES}),
            'structures': (10, {'starts': (10, {})}), 'block_entities': (9, (10, []))}


PROFILE = dict(materials=['minecraft:air', 'minecraft:stone'], biomes=[{'id': 'minecraft:plains'}],
               geology_min_y=-16, geology_height=16, lighting={'sky': True})


class SavedOutputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = {slot: b'\x0a\0\0'+encode(10, chunk(slot)) for slot in range(1024)}
        cls.region = fixture(cls.records)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root/'r.-1.0.mca'
        self.path.write_bytes(self.region)
        self.profile = self.root/'profile.json'
        self.profile.write_text(json.dumps(PROFILE))
        self.report = self.root/'report.json'
        self.report.write_text(json.dumps(dict(backend='bend', status='interrupted', error='Stopped by user',
            artifact='/nonexistent/engine', artifact_sha256='recorded-only',
            profile_sha256=digest(self.profile.read_bytes()), regions=[dict(rx=-1, rz=0,
                path=str(self.path), bytes=len(self.region))])))
        self.original = self.report.read_bytes()
        self.out = self.root/'audit'
        self.out.mkdir()

    def corrupt(self, slot, mutation):
        tag = chunk(slot)
        mutation(tag)
        self.path.write_bytes(fixture(self.records | {slot: b'\x0a\0\0'+encode(10, tag)}))

    def test_partial_run_validates_without_executable_and_preserves_report(self):
        result = audit(self.report, self.profile, self.out)
        self.assertEqual(result['status'], 'passed')
        self.assertEqual(result['generation_status'], 'interrupted')
        self.assertEqual(result['generation_error'], 'Stopped by user')
        self.assertEqual(self.report.read_bytes(), self.original)
        counts = result['regions'][0]['validation']
        self.assertEqual((counts['chunks'], counts['lit_chunks']), (1024, 900))
        self.assertEqual(counts['block_indices'], 1024*4096)
        self.assertEqual(counts['heightmap_entries'], 1024*6*256)

    def test_failure_is_separate_from_original_report(self):
        self.corrupt(0, lambda t: t.update(xPos=(3, 100)))
        with self.assertRaisesRegex(AssertionError, 'chunk slot 0'):
            audit(self.report, self.profile, self.out)
        self.assertEqual(self.report.read_bytes(), self.original)
        self.assertEqual(json.loads((self.out/'report.json').read_text())['status'], 'failed')

    def test_profile_mismatch_reports_failure(self):
        self.profile.write_text(json.dumps(dict(PROFILE, geology_height=32)))
        with self.assertRaisesRegex(AssertionError, 'profile differs'):
            audit(self.report, self.profile, self.out)

    def test_rejects_empty_completed_file_list(self):
        report = json.loads(self.original); report['regions'] = []
        self.report.write_text(json.dumps(report))
        with self.assertRaisesRegex(AssertionError, 'No completed'):
            audit(self.report, self.profile, self.out)

    def test_rejects_heightmap_outside_build_height(self):
        self.corrupt(0, lambda t: t['Heightmaps'][1]['WORLD_SURFACE'][1].__setitem__(0, 17))
        with self.assertRaisesRegex(AssertionError, 'chunk slot 0'):
            validate(self.path, PROFILE, -1, 0)

    def test_rejects_packed_palette_index_out_of_bounds(self):
        def change(t):
            t['sections'][1][1][0]['block_states'] = (10, {
                'palette': (9, (10, [{'id': (8, 'minecraft:air')}, {'id': (8, 'minecraft:stone')}])),
                'data': (12, [2]+[0]*255)})
        self.corrupt(0, change)
        with self.assertRaisesRegex(AssertionError, 'chunk slot 0'):
            validate(self.path, PROFILE, -1, 0)

    def test_rejects_unregistered_biome(self):
        self.corrupt(0, lambda t: t['sections'][1][1][0].update(biomes=(10, {'palette': (9, (8, ['unknown:biome']))})))
        with self.assertRaisesRegex(AssertionError, 'chunk slot 0'):
            validate(self.path, PROFILE, -1, 0)

    def test_rejects_unlit_ring_with_light_arrays(self):
        self.corrupt(0, lambda t: t['sections'][1][1][0].update(BlockLight=(7, bytes(2048))))
        with self.assertRaisesRegex(AssertionError, 'chunk slot 0'):
            validate(self.path, PROFILE, -1, 0)

    def test_rejects_short_interior_light_array(self):
        self.corrupt(33, lambda t: t['sections'][1][1][0].update(SkyLight=(7, bytes(2047))))
        with self.assertRaisesRegex(AssertionError, 'chunk slot 33'):
            validate(self.path, PROFILE, -1, 0)

    def test_iterator_matches_reader_and_checks_final_sector(self):
        sparse = fixture({0: self.records[0], 1023: self.records[1023]})
        self.assertEqual(dict(iter_region(sparse)), read_region(sparse))
        with self.assertRaises(AssertionError):
            dict(iter_region(sparse+bytes(4096)))
        overlap = bytearray(sparse); overlap[4092:4096] = overlap[0:4]
        with self.assertRaises(AssertionError):
            dict(iter_region(overlap))
        damaged = bytearray(sparse); damaged[-1] = 1
        with self.assertRaises(AssertionError):
            dict(iter_region(damaged))

    def test_deadline_stops_audit_without_touching_source(self):
        out = self.root/'timed'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('validate_backend_output.py')),
            '--report', str(self.report), '--profile', str(self.profile), '--out', str(out),
            '--max-seconds', '0.001'], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertEqual(json.loads((out/'run-control.json').read_text())['status'], 'timed_out')
        self.assertEqual(self.report.read_bytes(), self.original)

    def test_timeout_finalizes_checkpoint_without_marking_partial_region_complete(self):
        checkpoint = dict(status='running', generation_status='interrupted', regions=[], current_region={'chunks': 64})
        (self.out/'report.json').write_text(json.dumps(checkpoint))
        (self.out/'run-control.json').write_text(json.dumps(dict(status='timed_out', exit_code=124)))
        finish_control(self.out)
        result = json.loads((self.out/'report.json').read_text())
        self.assertEqual(result['status'], 'timed_out')
        self.assertEqual(result['generation_status'], 'interrupted')
        self.assertEqual(result['regions'], [])
        self.assertEqual(result['current_region'], {'chunks': 64})
        self.assertEqual(self.report.read_bytes(), self.original)

    def test_legacy_validation_does_not_require_artifact_or_complete_count(self):
        import benchmark_backends
        with mock.patch.object(sys, 'argv', ['benchmark_backends.py', '--validate-only', '--backend', 'bend',
                '--profile', str(self.profile), '--out', str(self.root), '--count', '0', '--max-seconds', '3']), \
                mock.patch.object(benchmark_backends.subprocess, 'call', return_value=7) as run:
            self.assertEqual(benchmark_backends.main(), 7)
        command = run.call_args.args[0]
        self.assertIn('validate_backend_output.py', command[1])
        self.assertEqual(command[-2:], ['--max-seconds', '3.0'])


if __name__ == '__main__':
    unittest.main()
