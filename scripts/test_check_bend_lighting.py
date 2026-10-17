"""Python-only checks of opt-in GPU orchestration; invokes no compiler or GPU."""
import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
import check_bend_lighting as check


class BoundedLightingTest(unittest.TestCase):
    def test_probe_only_adds_host_logging(self):
        original = 'DEVICE_CODE\n'+check.METAL_WAIT+'\nOTHER_HOST_CODE'
        changed = check.instrument_metal(original)
        self.assertEqual(changed.replace(check.METAL_LOG, ''), original)
        self.assertEqual(changed.count('BEND_METAL_DISPATCH'), 1)
        for invalid in ('unrecognized runtime', original+check.METAL_WAIT):
            with self.assertRaises(AssertionError): check.instrument_metal(invalid)

    def test_requires_completed_timestamped_gpu_work(self):
        log = 'BEND_METAL_DISPATCH device_ms=0.1 status=4\nBEND_METAL_DISPATCH device_ms=2.3 status=4\n'
        self.assertEqual(check.metal_evidence(log), dict(completed_buffers=2, device_ms=[.1, 2.3]))
        for invalid in ('--gpu on', '', 'BEND_METAL_DISPATCH device_ms=0 status=4',
                        'BEND_METAL_DISPATCH device_ms=2 status=5',
                        'BEND_METAL_DISPATCH device_ms=nan status=4',
                        'BEND_METAL_DISPATCH device_ms=inf status=4',
                        'BEND_METAL_DISPATCH device_ms=-1 status=4'):
            with self.assertRaises(AssertionError): check.metal_evidence(invalid)

    def exercise_commands(self, metal, fail_at=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        output = Path(temporary.name).resolve()
        compiler = output/'bend'; compiler.write_bytes(b'compiler fixture')
        args = argparse.Namespace(output=output, profile=output/'profile.json', bend=compiler, metal=metal)
        observed = []
        def fake_run(command, **kwargs):
            observed.append(command)
            self.assertEqual(command[:3], ['nice', '-n', '10'])
            self.assertEqual(kwargs['env']['RAYON_NUM_THREADS'], '2')
            if fail_at == len(observed): raise RuntimeError('intentional failure')
            if command[3] == str(compiler):
                Path(command[-1]).write_text('GENERATED_APPLICATION\n'+check.METAL_WAIT)
            elif '-O3' in command:
                Path(command[-1]).write_bytes(b'native fixture')
            elif '--gpu-build' in command:
                (output/'volume.gpu').write_bytes(b'archive fixture')
            else:
                folder = Path(command[command.index('--output')+1]); folder.mkdir(parents=True)
                case = command[command.index('--case')+1] if '--case' in command else 'cpu-suite'
                report = dict(cpu_threads=2, gpu_requested='--gpu' in command, results=[dict(name=case)])
                (folder/'report.json').write_text(json.dumps(report))
                (folder/(case+'.log')).write_text('BEND_METAL_DISPATCH device_ms=1 status=4\n')
        with mock.patch.object(check.subprocess, 'run', side_effect=fake_run), \
                mock.patch.object(check.sys, 'platform', 'darwin'), redirect_stdout(io.StringIO()):
            if fail_at:
                with self.assertRaisesRegex(RuntimeError, 'intentional failure'): check.run(args)
                self.assertFalse((output/'report.json').exists())
            else:
                check.run(args)
        return output, observed

    def test_cpu_default_never_requests_gpu_or_modifies_emitted_source(self):
        output, commands = self.exercise_commands(False)
        self.assertEqual(len(commands), 3)
        self.assertFalse(any('--gpu-build' in c or '--gpu' in c for c in commands))
        self.assertFalse((output/'volume-metal-diagnostic.c').exists())
        report = json.loads((output/'report.json').read_text())
        self.assertEqual(report['mode'], 'cpu')
        self.assertEqual(report['generated_c_sha256'], report['native_source_sha256'])

    def test_explicit_metal_checks_two_scenes_after_one_archive_build(self):
        output, commands = self.exercise_commands(True)
        self.assertEqual(len(commands), 5)
        self.assertEqual(commands[2][-3:], ['--threads', '2', '--gpu-build'])
        self.assertEqual([c[-1] for c in commands[3:]], list(check.METAL_CASES))
        self.assertTrue(all('--gpu' in c for c in commands[3:]))
        report = json.loads((output/'report.json').read_text())
        self.assertEqual(report['mode'], 'metal')
        self.assertEqual(len(report['result']['checks']), 2)
        self.assertTrue(all(c['observed_metal']['completed_buffers'] == 1 for c in report['result']['checks']))

    def test_gpu_build_failure_has_no_fixture_run_or_retry(self):
        _, commands = self.exercise_commands(True, fail_at=3)
        self.assertEqual(len(commands), 3)


if __name__ == '__main__':
    unittest.main()
