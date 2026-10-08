import tempfile
import unittest
import zlib
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from benchmark_support import compare_corpora, first_difference, mca_records, percentile


def fixture(records):
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for slot, payload in records.items():
        compressed = zlib.compress(payload)
        record = len(compressed + b"\x02").to_bytes(4, "big") + b"\x02" + compressed
        sectors = (len(record) + 4095) // 4096
        header[slot * 4:slot * 4 + 4] = ((sector << 8) | sectors).to_bytes(4, "big")
        body.extend(record)
        body.extend(bytes(sectors * 4096 - len(record)))
        sector += sectors
    return header + body


class BenchmarkSupportTest(unittest.TestCase):
    def test_percentile_interpolates(self):
        self.assertEqual(25, percentile([10, 20, 30, 40], 50))
        self.assertEqual(38.5, percentile([10, 20, 30, 40], 95))

    def test_mca_records_allows_empty_slots(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "0.mca"
            path.write_bytes(fixture({0: b"first", 17: b"second"}))
            self.assertEqual({0: b"first", 17: b"second"}, mca_records(path))

    def test_difference_reports_nested_path(self):
        self.assertEqual("root.Level.sections[1].Y: 2 != 3", first_difference(
            {"Level": {"sections": [{"Y": 1}, {"Y": 2}]}},
            {"Level": {"sections": [{"Y": 1}, {"Y": 3}]}}))

    def test_compare_rejects_changed_membership(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old, new = root / "old", root / "new"
            old.mkdir(); new.mkdir()
            (old / "0.mca").write_bytes(fixture({0: b"first"}))
            (new / "0.mca").write_bytes(fixture({1: b"first"}))
            with self.assertRaisesRegex(AssertionError, "Changed MCA membership"):
                compare_corpora(old, new, ["0"])


if __name__ == "__main__":
    unittest.main()
