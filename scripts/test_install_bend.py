"""Installer integrity/publication checks using tiny local archives; no compiler."""
from contextlib import redirect_stdout
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock

import install_bend as installer


class InstallBendTest(unittest.TestCase):
    def test_verified_archive_installs_without_touching_other_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root/'bend.tar.gz'
            with tarfile.open(archive, 'w:gz') as package:
                for name in ('bin/bend', 'bend2/Base.bend', 'guide/GUIDE.md'):
                    data = name.encode()
                    entry = tarfile.TarInfo('bend/'+name)
                    entry.size = len(data)
                    entry.mode = 0o755 if name == 'bin/bend' else 0o644
                    package.addfile(entry, io.BytesIO(data))
            target = 'darwin-arm64'
            destination = root/'installed'/installer.VERSION
            previous = root/'installed'/'previous'/'playable'
            previous.mkdir(parents=True)
            (previous/'engine').write_bytes(b'previous worker')
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            with mock.patch.dict(installer.CHECKSUMS, {target: checksum}), redirect_stdout(io.StringIO()):
                installer.install(target, archive, destination)
                self.assertEqual((destination/'bin/bend').read_bytes(), b'bin/bend')
                self.assertTrue((destination/'bin/bend').stat().st_mode & 0o100)
                with self.assertRaises(OSError):
                    installer.install(target, archive, destination)
            self.assertEqual((previous/'engine').read_bytes(), b'previous worker')
            self.assertEqual((destination/'guide/GUIDE.md').read_bytes(), b'guide/GUIDE.md')

    def test_corrupt_archive_does_not_create_installation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root/'bend.tar.gz'; archive.write_bytes(b'not the pinned archive')
            destination = root/'installed'/installer.VERSION
            with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
                installer.install('darwin-arm64', archive, destination)
            self.assertFalse(destination.exists())


if __name__ == '__main__':
    unittest.main()
