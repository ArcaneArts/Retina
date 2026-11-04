"""Evidence-runner guards: false success, environment leaks and return packaging."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import Mock, patch
import zipfile

spec = importlib.util.spec_from_file_location("transport_matrix", Path(__file__).with_name("lighting-transport-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = matrix
spec.loader.exec_module(matrix)


class MatrixEvidenceTests(unittest.TestCase):
    def test_every_factorial_candidate_has_matched_reversed_pairs(self):
        runs = list(matrix.trials(matrix.SETTINGS, 5))
        self.assertEqual(len(runs), 70)
        for candidate in matrix.SETTINGS[1:]:
            selected = [row for row in runs if row[0] == candidate.name]
            for pair in range(5):
                settings = [row[2] for row in selected if row[1] == pair]
                self.assertEqual(settings, [matrix.BASELINE, candidate] if pair % 2 == 0 else [candidate, matrix.BASELINE])

    def test_child_does_not_inherit_experimental_overrides(self):
        with patch.dict(os.environ, {"RETINA_GPU_BACKEND": "cpu", "retina_GPU_LIGHTING": "0",
                                     "RETINA_PERFORMANCE_DIAGNOSTICS": "1", "PYTHONPATH": "user-code", "PATH": "keep"}):
            env = matrix.child_environment(matrix.SETTINGS[-1], Path("private-cache"), 8)
        self.assertNotIn("RETINA_GPU_BACKEND", env)
        self.assertNotIn("retina_GPU_LIGHTING", env)
        self.assertNotIn("RETINA_PERFORMANCE_DIAGNOSTICS", env)
        self.assertNotIn("PYTHONPATH", env)
        self.assertEqual(env["RETINA_GPU_PALETTES"], "1")
        self.assertEqual(env["PATH"], "keep")

    def test_wrong_backend_partial_region_or_changed_nbt_never_pass(self):
        value = dict(backend_selected="Vulkan: test", regions=[dict(generated=1024, preserved=0)],
                     corpus=dict(records=1024, sha256="reference"))
        matrix.verify_measurement(value, "vulkan", value["corpus"])
        # Zero terrain-job counters can be valid; actual output/backend are checked.
        with self.assertRaises(ValueError):
            matrix.verify_measurement(value, "dx12", value["corpus"])
        with self.assertRaises(ValueError):
            matrix.verify_measurement(value, "vulkan", dict(records=1024, sha256="changed"))
        value["regions"][0]["preserved"] = 1
        with self.assertRaises(ValueError):
            matrix.verify_measurement(value, "vulkan", value["corpus"])

    def test_return_archive_excludes_source_world_and_native_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("controller.log", "summary.json", "retina.dat", "region.mca", "native.dll", "cache/metadata.json", "trial/native.log"):
                path = root / name
                path.parent.mkdir(exist_ok=True)
                path.write_text("evidence")
            package = matrix.package_results(root)
            with zipfile.ZipFile(package) as archive:
                self.assertEqual(set(archive.namelist()), {"controller.log", "summary.json", "trial/native.log"})

    def test_optional_bundle_metadata_cannot_prevent_real_generation(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "BUNDLE.json"
            self.assertIn("unavailable", matrix.optional_manifest(path))
            path.write_text("broken metadata")
            self.assertIn("unavailable", matrix.optional_manifest(path))
            path.write_text("[]")
            self.assertIn("unavailable", matrix.optional_manifest(path))

    def test_optional_windows_memory_failure_is_unavailable(self):
        import process_usage
        import ctypes
        with patch.object(ctypes, "WinDLL", create=True, side_effect=OSError("unavailable")):
            self.assertIsNone(process_usage.windows_peak_working_set())

    def test_windows_memory_preserves_size_t_bytes_and_handles_api_failure(self):
        import process_usage
        import ctypes
        kernel = Mock()
        kernel.GetCurrentProcess.return_value = -1
        def fill(handle, pointer, length):
            self.assertEqual(length, 8 + 8 * ctypes.sizeof(ctypes.c_size_t))
            pointer._obj.peak = 1 << 34
            return 1
        kernel.K32GetProcessMemoryInfo.side_effect = fill
        with patch.object(ctypes, "WinDLL", create=True, return_value=kernel):
            self.assertEqual(process_usage.windows_peak_working_set(), 1 << 34)
            kernel.K32GetProcessMemoryInfo.side_effect = None
            kernel.K32GetProcessMemoryInfo.return_value = 0
            self.assertIsNone(process_usage.windows_peak_working_set())

    def test_cancel_terminates_only_the_owned_child(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            matrix.stop_child(child)
            self.assertIsNotNone(child.poll())
        finally:
            if child.poll() is None:
                child.kill()
                child.wait()


if __name__ == "__main__":
    unittest.main()
