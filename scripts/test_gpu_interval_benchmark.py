"""Opt-in end-to-end checks of real submission attribution and lit MCA output."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("RETINA_TEST_INTERVAL_LIBRARY") and os.environ.get("RETINA_TEST_INTERVAL_DEFINITION"),
                     "Supply a real interval-capable native and saved definition")
class GpuIntervalBenchmarkTest(unittest.TestCase):
    def run_case(self, backend, dimension):
        with tempfile.TemporaryDirectory(prefix="retina-interval-") as directory:
            output = Path(directory) / "region"
            command = [sys.executable, str(Path(__file__).with_name("native-region-benchmark.py")),
                       "--library", os.environ["RETINA_TEST_INTERVAL_LIBRARY"],
                       "--definition", os.environ["RETINA_TEST_INTERVAL_DEFINITION"],
                       "--dimension", dimension, "--backend", backend, "--gpu-interval",
                       "--out", str(output), "--count", "1", "--warmups", "1",
                       "--assembly-threads", "4", "--readiness", "shader-ready"]
            result = subprocess.run(command, env=dict(os.environ, RAYON_NUM_THREADS="2"),
                                    capture_output=True, text=True, timeout=240)
            self.assertEqual(result.returncode, 0, result.stdout[-1000:] + result.stderr[-1000:])
            data = json.loads((output / "measurements.json").read_text())
            interval = data["gpu_interval"]
            self.assertEqual(interval["schema_version"], 1)
            self.assertEqual(interval["dropped_records_during_window"], 0)
            self.assertEqual(interval["incoming_completed"], 0)
            self.assertEqual(interval["outgoing_pending"], 0)
            self.assertEqual(interval["tracked_active_at_start"], 0)
            self.assertEqual(interval["tracked_active_at_end"], 0)
            self.assertEqual(interval["aborted_observations"], 0)
            self.assertEqual(data["regions"][0]["generated"], 1024)
            ids = [row["submission"]["submission_id"] for row in interval["events"]]
            self.assertEqual(len(ids), len(set(ids)))
            for row in interval["events"]:
                self.assertTrue(row["fully_contained"])
                self.assertGreater(row["submission"]["submission_id"], interval["start_sequence"])
                self.assertLess(row["submission"]["completed_sequence"], interval["end_sequence"])
            self.assertLessEqual(interval["fully_contained_pass_union_ns"], interval["fully_contained_pass_sum_ns"])
            return data

    def test_cpu_empty_interval_does_not_prevent_generation(self):
        data = self.run_case("cpu", "minecraft:the_end")
        self.assertEqual(data["backend_selected"], "CPU: native")
        self.assertEqual(data["gpu_interval"]["events"], [])

    @unittest.skipUnless(os.environ.get("RETINA_TEST_INTERVAL_GPU_BACKEND"), "Opt into an actual GPU backend")
    def test_real_world_and_end_lighting_have_interval_events_after_warmup(self):
        backend = os.environ["RETINA_TEST_INTERVAL_GPU_BACKEND"]
        for dimension in ["minecraft:overworld", "minecraft:the_end"]:
            with self.subTest(dimension=dimension):
                data = self.run_case(backend, dimension)
                self.assertTrue(data["backend_selected"].lower().startswith(backend))
                kinds = {row["submission"]["kind"] for row in data["gpu_interval"]["events"]}
                self.assertIn("lighting", kinds)
                if dimension == "minecraft:overworld":
                    self.assertTrue({"terrain", "materials"}.issubset(kinds))


if __name__ == "__main__":
    unittest.main()
