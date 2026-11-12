"""Focused version, changelog and overlapping-release ordering checks."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from release_version import artifact_names, metadata

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-release.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class ReleaseTest(unittest.TestCase):
    def test_rerun_verifies_exactly_one_jar_and_its_bytes(self):
        expected = {'retina.jar': b'jar'}
        assets = [{'name': name} for name in reversed(expected)]
        def download(*args):
            name = args[args.index('--pattern') + 1]
            directory = args[args.index('--dir') + 1]
            (Path(directory) / name).write_bytes(expected[name])
        with patch.object(publisher, 'run', side_effect=download) as calls:
            publisher.verify_published_assets('v1.1.1', assets, expected)
            self.assertEqual(calls.call_count, 1)
        with self.assertRaises(ValueError):
            publisher.verify_published_assets('v1.1.1', [], expected)
        with self.assertRaises(ValueError):
            publisher.verify_published_assets('v1.1.1', assets + [{'name': 'probe.exe'}], expected)
        def corrupt_jar(*args):
            download(*args)
            if args[args.index('--pattern') + 1] == 'retina.jar':
                (Path(args[args.index('--dir') + 1]) / 'retina.jar').write_bytes(b'changed')
        with patch.object(publisher, 'run', side_effect=corrupt_jar), self.assertRaises(ValueError):
            publisher.verify_published_assets('v1.1.1', assets, expected)

    def test_draft_lookup_uses_authenticated_release_listing(self):
        draft = {'tag_name': 'v1.1.450', 'draft': True, 'id': 123}
        with patch.object(publisher, 'api', return_value=[draft]) as api:
            self.assertEqual(publisher.release_for_tag('v1.1.450'), draft)
            self.assertIsNone(publisher.release_for_tag('v1.1.449'))
            api.assert_called_with('releases?per_page=100', pages=True)

    def test_windows_native_environment_and_commit_independence(self):
        spec = importlib.util.spec_from_file_location('native_release', Path(__file__).with_name('native-release.py'))
        native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(native)
        fields = {'VCTOOLSVERSION': '14.44', 'WINDOWSSDKVERSION': '10.0.26100',
                  'IMAGEVERSION': '20261026', 'LIB': 'sdk/lib', 'INCLUDE': 'sdk/include'}
        self.assertEqual(native.build_environment({**fields, 'GITHUB_SHA': 'first'}), fields)
        self.assertEqual(native.build_environment({**fields, 'GITHUB_SHA': 'next'}), fields)
        self.assertEqual(native.build_environment({'VCToolsVersion': '14.44'}), {'VCTOOLSVERSION': '14.44'})

    def test_native_cache_rejects_changed_vendored_backend(self):
        spec = importlib.util.spec_from_file_location('native_release', Path(__file__).with_name('native-release.py'))
        native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(native)
        target = 'aarch64-apple-darwin'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            for name in ('native/Cargo.toml', 'native/Cargo.lock', 'native/src/lib.rs',
                         'native/vendor/wgpu-hal/src/dx12/device.rs', 'scripts/native-release.py'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            with patch.object(native, 'ROOT', root), \
                 patch.object(native, '__file__', str(root / 'scripts/native-release.py')), \
                 patch.object(native, 'output', return_value='test toolchain'):
                original = native.fingerprint(target)
                directory, library, manifest = native.paths(target)
                library.parent.mkdir(parents=True)
                library.write_bytes(b'compiled native')
                manifest.parent.mkdir(parents=True)
                manifest.write_text(json.dumps({**original, 'bytes': library.stat().st_size,
                                               'sha256': native.digest(library.read_bytes())}))
                # Packaging-only files do not invalidate reusable natives.
                (root / 'gradle.properties').write_text('minecraft_version=26.3\n')
                self.assertEqual(native.fingerprint(target), original)
                with patch('builtins.print'):
                    native.verify(target)
                backend = root / 'native/vendor/wgpu-hal/src/dx12/device.rs'
                backend.write_text('changed HLSL lowering')
                self.assertNotEqual(native.fingerprint(target)['key'], original['key'])
                with self.assertRaisesRegex(ValueError, 'native inputs differ'):
                    native.verify(target)
                backend.write_text('native/vendor/wgpu-hal/src/dx12/device.rs')
                self.assertEqual(native.fingerprint(target), original)
                added = backend.with_name('new_shader_pass.rs')
                added.write_text('new compiler input')
                self.assertNotEqual(native.fingerprint(target)['key'], original['key'])
                added.unlink()
                self.assertEqual(native.fingerprint(target), original)
                backend.unlink()
                self.assertNotEqual(native.fingerprint(target)['key'], original['key'])

    def test_minecraft_version_and_real_commit_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            git('init', '--initial-branch=main')
            git('config', 'user.name', 'Version test'); git('config', 'user.email', 'version@example.invalid')
            (root / 'gradle.properties').write_text('minecraft_version=26.3\n')
            git('add', '.'); git('commit', '-m', 'First')
            self.assertEqual(metadata(root)['version'], '26.3-1')
            git('commit', '--allow-empty', '-m', 'Advance revision')
            self.assertEqual(metadata(root)['version'], '26.3-2')
            self.assertEqual(metadata(root)['revision'], 2)
            self.assertEqual(metadata(root)['build_task'], 'universalJar')
            legacy = metadata(root, line='1.20')
            self.assertEqual(legacy['version'], '1.20.1-2')
            self.assertEqual(legacy['build_task'], 'build1201')
            self.assertEqual(legacy['commit'], metadata(root)['commit'])

    def test_legacy_publication_uploads_three_jars_without_changing_latest(self):
        version = '1.20.1-524'
        commit = 'a' * 40
        info = {'version': version, 'commit': commit, 'line': '1.20'}
        names = artifact_names('1.20', version)
        draft = {'assets': [{'name': name} for name in names]}
        with patch.dict(os.environ, {'GITHUB_SHA': commit}), \
             patch.object(Path, 'read_bytes', return_value=b'jar'), \
             patch.object(publisher, 'REPO', 'ArcaneArts/Retina'), \
             patch.object(publisher, 'metadata', return_value=info), \
             patch.object(publisher, 'release_for_tag', side_effect=[None, draft]), \
             patch.object(publisher, 'tag_commit', side_effect=[None, commit]), \
             patch.object(publisher, 'release_notes', return_value='notes'), \
             patch.object(publisher, 'reconcile_latest') as latest, \
             patch.object(publisher, 'run') as run, patch('builtins.print'):
            publisher.publish('1.20')
        latest.assert_not_called()
        upload, = (call.args for call in run.call_args_list if call.args[:3] == ('gh', 'release', 'upload'))
        self.assertEqual(upload[4:7], tuple(f'dist/{name}' for name in names))
        self.assertIn('--latest=false', run.call_args.args)

    def test_pr_and_direct_commits_are_both_linked(self):
        with patch.object(publisher, 'REPO', 'ArcaneArts/Retina'):
            body = publisher.changes([('aaa1111', 'Squashed title'), ('bbb2222', 'Direct change (#77)')],
                [{'merge_commit_sha': 'aaa1111', 'merged_at': 'now', 'title': 'Universal packaging',
                  'user': {'login': 'cyberpwn'}, 'number': 77, 'html_url': 'https://github.com/ArcaneArts/Retina/pull/77'}],
                lambda commit: '@author')
            self.assertIn('Universal packaging by @cyberpwn in [#77]', body)
            self.assertIn('Direct change (#77) by @author in [bbb2222]', body)
            self.assertEqual(len(body.splitlines()), 2)

    def test_older_finisher_keeps_newer_release_latest(self):
        latest = {'tag_name': 'v1.1.450'}
        with patch.object(publisher, 'published', return_value=[{'tag_name': 'v1.1.449'}, latest]), \
             patch.object(publisher, 'api', return_value=latest), patch.object(publisher, 'run') as run:
            publisher.reconcile_latest()
            run.assert_not_called()

    def test_concurrent_newer_publication_repairs_stale_latest_update(self):
        old = {'tag_name': 'v1.1.449'}; new = {'tag_name': '26.3-450'}
        with patch.object(publisher, 'published', side_effect=[[old], [old, new], [old, new]]), \
             patch.object(publisher, 'api', side_effect=[None, old, new]), patch.object(publisher, 'run') as run:
            publisher.reconcile_latest()
            self.assertEqual([call.args[3] for call in run.call_args_list], ['v1.1.449', '26.3-450'])

    def test_legacy_revision_cannot_become_latest(self):
        latest = {'tag_name': '26.3-511'}
        legacy = {'tag_name': '1.20.1-524'}
        with patch.object(publisher, 'published', return_value=[latest, legacy]), \
             patch.object(publisher, 'api', return_value=latest), patch.object(publisher, 'run') as run:
            publisher.reconcile_latest()
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
