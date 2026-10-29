"""Focused version, changelog and overlapping-release ordering checks."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from release_version import metadata

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-release.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class ReleaseTest(unittest.TestCase):
    def test_rerun_verifies_both_jar_and_probe_bytes(self):
        expected = {'retina.jar': b'jar', 'probe.exe': b'executable'}
        assets = [{'name': name} for name in reversed(expected)]
        def download(*args):
            name = args[args.index('--pattern') + 1]
            directory = args[args.index('--dir') + 1]
            (Path(directory) / name).write_bytes(expected[name])
        with patch.object(publisher, 'run', side_effect=download) as calls:
            publisher.verify_published_assets('v1.1.1', assets, expected)
            self.assertEqual(calls.call_count, 2)
        with self.assertRaises(ValueError):
            publisher.verify_published_assets('v1.1.1', assets[:1], expected)
        def corrupt_probe(*args):
            download(*args)
            if args[args.index('--pattern') + 1] == 'probe.exe':
                (Path(args[args.index('--dir') + 1]) / 'probe.exe').write_bytes(b'changed')
        with patch.object(publisher, 'run', side_effect=corrupt_probe), self.assertRaises(ValueError):
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

    def test_major_minor_and_real_commit_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            git('init', '--initial-branch=main')
            git('config', 'user.name', 'Version test'); git('config', 'user.email', 'version@example.invalid')
            (root / 'version.json').write_text(json.dumps({'major': '1', 'minor': 1}))
            (root / 'gradle.properties').write_text('minecraft_version=26.3\n')
            git('add', '.'); git('commit', '-m', 'First')
            self.assertEqual(metadata(root)['version'], '1.1.1')
            (root / 'version.json').write_text(json.dumps({'major': 1, 'minor': '2'}))
            git('add', '.'); git('commit', '-m', 'Next minor')
            self.assertEqual(metadata(root)['version'], '1.2.2')

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
        old = {'tag_name': 'v1.1.449'}; new = {'tag_name': 'v1.1.450'}
        with patch.object(publisher, 'published', side_effect=[[old], [old, new], [old, new]]), \
             patch.object(publisher, 'api', side_effect=[None, old, new]), patch.object(publisher, 'run') as run:
            publisher.reconcile_latest()
            self.assertEqual([call.args[3] for call in run.call_args_list], ['v1.1.449', 'v1.1.450'])


if __name__ == '__main__':
    unittest.main()
