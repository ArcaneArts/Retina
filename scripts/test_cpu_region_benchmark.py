"""Opt-in real-native test of CPU selection, readiness and definition reopen."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("RETINA_TEST_CPU_LIBRARY") and os.environ.get("RETINA_TEST_CPU_DEFINITION"),
                     "Supply a real CPU-capable native library and retina.dat")
class CpuRegionBenchmarkTest(unittest.TestCase):
    def test_cpu_overrides_gpu_request_never_awaits_shaders_and_preserves_reopened_region(self):
        with tempfile.TemporaryDirectory(prefix="retina-cpu-benchmark-") as directory:
            output = Path(directory) / "output"
            command = [sys.executable, str(Path(__file__).with_name("native-region-benchmark.py")),
                       "--library", os.environ["RETINA_TEST_CPU_LIBRARY"], "--backend", "cpu",
                       "--definition", os.environ["RETINA_TEST_CPU_DEFINITION"],
                       "--dimension", "minecraft:the_end", "--out", str(output),
                       "--assembly-threads", "2", "--parallel", "1", "--count", "1", "--warmups", "0",
                       "--readiness", "optimized-interpreter", "--await-specialization", "--preservation-check"]
            environment = dict(os.environ, RETINA_GPU_BACKEND="metal", RAYON_NUM_THREADS="1")
            result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=180)
            self.assertEqual(result.returncode, 0, result.stdout[-1000:] + result.stderr[-1000:])
            data = json.loads((output / "measurements.json").read_text())
            self.assertEqual(data["backend_selected"], "CPU: native")
            self.assertEqual(data["gpu_pipeline"]["completed"], 0)
            self.assertEqual(data["readiness_effective"], "cpu")
            self.assertEqual(data["readiness_wait_ms"], 0)
            self.assertFalse(data["specialization_warmup_check"]["applicable"])
            self.assertEqual(data["regions"][0]["generated"], 1024)
            self.assertEqual(data["regions"][0]["preserved"], 0)
            self.assertEqual(data["corpus"]["records"], 1024)
            self.assertEqual(data["preservation_check"]["preserved"], 1024)
            self.assertEqual(data["preservation_check"]["generated"], 0)


if __name__ == "__main__":
    unittest.main()
