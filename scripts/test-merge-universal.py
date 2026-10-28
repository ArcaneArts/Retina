"""Small assembly tests; no Minecraft or native compilation needed."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('merge_universal', Path(__file__).with_name('merge-universal.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class MergeTest(unittest.TestCase):
    def test_identical_class_deduplicated_and_unique_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            a = root / 'a'; a.mkdir(); (a / 'Shared.class').write_bytes(b'shared')
            jar = root / 'b.jar'
            with zipfile.ZipFile(jar, 'w') as out:
                out.writestr('Shared.class', b'shared')
                out.writestr('Adapter.class', b'adapter')
            module.merge(root / 'out', [f'a={a}', f'b={jar}'])
            self.assertEqual((root / 'out/Shared.class').read_bytes(), b'shared')
            self.assertEqual((root / 'out/Adapter.class').read_bytes(), b'adapter')

    def test_conflicting_class_does_not_replace_previous_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ('a', 'b', 'out'):
                (root / name).mkdir(); (root / name / 'Shared.class').write_bytes(name.encode())
            with self.assertRaisesRegex(ValueError, 'Conflicting universal entry Shared.class: a and b'):
                module.merge(root / 'out', [f'a={root / "a"}', f'b={root / "b"}'])
            self.assertEqual((root / 'out/Shared.class').read_bytes(), b'out')

if __name__ == '__main__':
    unittest.main()
