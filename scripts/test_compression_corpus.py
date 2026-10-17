"""Corpus provenance and extraction checks; no compiler, engine or benchmark."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from benchmark_compression_corpus import SLOTS, digest, prepare, region_sources
from test_benchmark_support import fixture
from test_validate_backend_output import chunk, encode


class CorpusTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.region = self.root / 'r.-1.0.mca'
        self.records = {slot: b'\x0a\0\0' + encode(10, chunk(slot)) for slot in SLOTS}
        self.region.write_bytes(fixture(self.records))
        row = dict(path=str(self.region), rx=-1, rz=0)
        self.source = self.root / 'report.json'
        self.source.write_text(json.dumps(dict(backend='bend', status='interrupted',
            artifact_sha256='engine', profile_sha256='profile', regions=[row])))
        self.audit = self.root / 'audit.json'
        self.checkpoint = dict(schema='retina.saved-output-validation.v1',
            source_report=str(self.source), source_report_sha256=digest(self.source.read_bytes()),
            recorded_artifact_sha256='engine', profile_sha256='profile',
            regions=[dict(row, validation=dict(chunks=1024, sha256=digest(self.region.read_bytes())))],
            current_region=dict(path='/unfinished/r.0.0.mca', rx=0, rz=0, chunks=192))
        self.save_audit()

    def save_audit(self):
        self.audit.write_text(json.dumps(dict(status='partial', checkpoint=self.checkpoint)))

    def test_extracts_exact_nbt_and_preserves_interruption(self):
        output = self.root / 'corpus'
        prepare([], output, [self.audit])
        manifest = json.loads((output / 'manifest.json').read_text())
        self.assertEqual(len(SLOTS), len(manifest['samples']))
        self.assertEqual('interrupted', manifest['sources'][0]['generation_status'])
        self.assertEqual(digest(self.audit.read_bytes()), manifest['sources'][0]['saved_validation']['sha256'])
        self.assertEqual(4, sum(row['lit'] for row in manifest['samples']))
        for row in manifest['samples']:
            self.assertEqual(self.records[row['slot']], (output / row['file']).read_bytes())
        self.assertEqual('interrupted', json.loads(self.source.read_text())['status'])

    def test_changed_source_report_is_rejected(self):
        self.source.write_text(self.source.read_text() + '\n')
        with self.assertRaisesRegex(AssertionError, 'Changed source report'):
            list(region_sources([], [self.audit]))

    def test_changed_region_is_rejected(self):
        self.region.write_bytes(self.region.read_bytes() + b'changed')
        with self.assertRaisesRegex(AssertionError, 'Changed MCA'):
            prepare([], self.root / 'corpus', [self.audit])

    def test_partial_and_unrecorded_regions_are_rejected(self):
        for change in [dict(validation=dict(chunks=192)), dict(rx=20)]:
            original = self.checkpoint['regions'][0].copy()
            self.checkpoint['regions'][0].update(change)
            self.save_audit()
            with self.assertRaises(AssertionError):
                list(region_sources([], [self.audit]))
            self.checkpoint['regions'][0] = original

    def test_registry_and_artifact_provenance_must_match(self):
        for key in ['recorded_artifact_sha256', 'profile_sha256']:
            original = self.checkpoint[key]
            self.checkpoint[key] = 'changed'
            self.save_audit()
            with self.assertRaises(AssertionError):
                list(region_sources([], [self.audit]))
            self.checkpoint[key] = original


if __name__ == '__main__':
    unittest.main()
