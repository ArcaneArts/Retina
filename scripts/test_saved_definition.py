"""Actual Rust-bridge integration; enable with the native library and Java fixture directory."""
import ctypes as c
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from saved_definition import SavedDefinition


@unittest.skipUnless(os.environ.get("RETINA_NATIVE_LIBRARY") and os.environ.get("RETINA_DEFINITION_FIXTURES"),
                     "requires RETINA_NATIVE_LIBRARY and RETINA_DEFINITION_FIXTURES; binding uses the actual GPU")
class SavedDefinitionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.library = c.CDLL(str(Path(os.environ["RETINA_NATIVE_LIBRARY"]).resolve()))
        cls.fixtures = Path(os.environ["RETINA_DEFINITION_FIXTURES"])

    def test_missing_and_malformed_files_report_actual_reads_and_preserve_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "retina.dat"
            with self.assertRaises(FileNotFoundError):
                SavedDefinition(self.library, path, "minecraft:overworld")
            path.write_bytes(b"broken gzip definition")
            with self.assertRaisesRegex(RuntimeError, "world definition gzip"):
                SavedDefinition(self.library, path, "minecraft:overworld")
            self.assertEqual(path.read_bytes(), b"broken gzip definition")

    def test_frozen_requests_preserve_seed_and_independent_storage_until_close(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "retina.dat"
            shutil.copyfile(self.fixtures / "cropped/retina.dat", path)
            with SavedDefinition(self.library, path, "minecraft:overworld") as world:
                self.assertEqual(world.metadata["seed"], -(1 << 63) + 23)
                self.assertEqual(world.dimension["parameters"]["storage"], {"min_y": -64, "height": 384})
                request = world.request(-33, 32)
                self.assertEqual((request.seed, request.x, request.z, request.min_y, request.height),
                                 ((1 << 63) + 23, -33, 32, 0, 64))
                self.assertEqual((request.base, request.amplitude), (37.5, 19.25))
                self.assertEqual(world.fallback_biome, "minecraft:forest")
                with self.assertRaises(OverflowError):
                    world.request(1 << 32, 0)
                with self.assertRaisesRegex(RuntimeError, "overflows block coordinates"):
                    world.request(1 << 30, 0)
                path.write_bytes(b"changed after opening")
                self.assertEqual(bytes(world.request(-33, 32)), bytes(request))
            world.close()
            with self.assertRaisesRegex(RuntimeError, "unknown or closed"):
                world.request(0, 0)
            with self.assertRaisesRegex(RuntimeError, "world definition gzip"):
                SavedDefinition(self.library, path, "minecraft:overworld")

    def test_execution_bindings_and_real_dimension_failures_leave_file_unchanged(self):
        path = self.fixtures / "flat/retina.dat"
        saved = path.read_bytes()
        with SavedDefinition(self.library, path, "minecraft:overworld", "zlib", "interpreter") as a, \
             SavedDefinition(self.library, path, "minecraft:overworld", "lz4", "specialized") as b:
            self.assertEqual(a.metadata, b.metadata)
            x, y = a.request(-1, 0), b.request(-1, 0)
            x.profile = y.profile = 0
            self.assertEqual(bytes(x), bytes(y))
        for dimension, message in [("retina:missing", "has no dimension"),
                                   ("minecraft:the_end", "unsupported generation")]:
            with self.assertRaisesRegex(RuntimeError, message):
                SavedDefinition(self.library, path, dimension)
        self.assertEqual(saved, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
