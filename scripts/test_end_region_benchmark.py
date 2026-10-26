"""Ownership and evidence checks for disposable benchmark output cleanup."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("end_region_benchmark", Path(__file__).with_name("end-region-benchmark.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class DisposableRegionsTest(unittest.TestCase):
    def test_removes_only_named_owned_outputs_and_retains_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "new-trial"
            directory.mkdir()
            for name in ("0.mca", "1.mca", "warm0.mca", "unrelated.mca", "measurements.json", "failure.nbt"):
                (directory / name).write_bytes(b"evidence")
            receipt = benchmark.discard_generated_regions(directory, 2, 1)
            self.assertEqual([row["file"] for row in receipt["files"]], ["0.mca", "1.mca", "warm0.mca"])
            self.assertEqual(receipt["bytes"], 24)
            self.assertEqual(sorted(path.name for path in directory.iterdir()),
                             ["failure.nbt", "measurements.json", "region-disposal.json", "unrelated.mca"])
            self.assertEqual(json.loads((directory / "region-disposal.json").read_text()), receipt)


if __name__ == "__main__":
    unittest.main()
