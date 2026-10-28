"""Release catalog checks reject broken packaged assets, not source declarations."""
import importlib.util
import io
import json
import hashlib
import unittest
from pathlib import Path
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location('stage_release', Path(__file__).with_name('stage-release.py'))
stage_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage_release)


class PackagedCatalogTest(unittest.TestCase):
    def entries(self):
        result = {}
        for snapshot in ('vanilla-recompiled', 'vanilla-binary'):
            directory = f'catalogs/26.3/{snapshot}/'
            data = ('distinct payload ' + snapshot).encode()
            result[directory + 'retina-catalog.dat'] = data
            result[directory + 'manifest.json'] = json.dumps({
                'minecraft_version': '26.3', 'catalog_version': 2,
                'materialization_version': 1, 'generation_version': 1,
                'asset': 'retina-catalog.dat', 'compressed_bytes': len(data),
                'sha256': hashlib.sha256(data).hexdigest(),
                'variants': dict.fromkeys(['normal', 'normal-bonus-chest', 'normal-no-structures',
                                          'normal-no-structures-bonus-chest', 'bukkit-api'], {})
            }).encode()
        return result

    def inspect(self, entries):
        data = io.BytesIO()
        with ZipFile(data, 'w') as archive:
            for name, value in entries.items(): archive.writestr(name, value)
        with ZipFile(data) as archive:
            stage_release.inspect_mod_catalogs(archive, '26.3')

    def test_accepts_complete_packaged_snapshots(self):
        self.inspect(self.entries())

    def test_each_snapshot_is_required(self):
        for snapshot in ('vanilla-recompiled', 'vanilla-binary'):
            with self.subTest(snapshot=snapshot):
                entries = self.entries()
                del entries[f'catalogs/26.3/{snapshot}/retina-catalog.dat']
                with self.assertRaises(KeyError): self.inspect(entries)

    def test_same_size_corruption_in_either_snapshot_is_rejected(self):
        for snapshot in ('vanilla-recompiled', 'vanilla-binary'):
            with self.subTest(snapshot=snapshot):
                entries = self.entries(); name = f'catalogs/26.3/{snapshot}/retina-catalog.dat'
                entries[name] = b'X' + entries[name][1:]
                with self.assertRaisesRegex(ValueError, 'bytes differ'): self.inspect(entries)

    def test_manifest_for_another_minecraft_version_is_rejected(self):
        entries = self.entries(); name = 'catalogs/26.3/vanilla-binary/manifest.json'
        manifest = json.loads(entries[name]); manifest['minecraft_version'] = 'wrong-version'
        entries[name] = json.dumps(manifest).encode()
        with self.assertRaisesRegex(ValueError, 'minecraft_version'): self.inspect(entries)


if __name__ == '__main__': unittest.main()
