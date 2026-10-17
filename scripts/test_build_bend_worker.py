"""Build orchestration checks; no native compiler, engine or GPU is invoked."""
import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import build_bend_worker as build


class WorkerBuildTest(unittest.TestCase):
    def exercise(self, stage, fail_at=None, malformed_smoke=False):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name).resolve()
        output = root/'output'; output.mkdir()
        compiler = root/'bend'; compiler.write_bytes(b'compiler')
        binary = root/'engine'; binary.write_bytes(b'original engine')
        args = argparse.Namespace(stage=stage, output=output, bend=compiler, binary=binary)
        commands = []

        def execute(command, **kwargs):
            commands.append(command)
            self.assertEqual(command[:3], ['nice', '-n', '10'])
            self.assertEqual(kwargs['env']['BUN_JSC_numberOfGCMarkers'], '1')
            self.assertEqual(kwargs['env']['BUN_JSC_useConcurrentJIT'], 'false')
            if fail_at == len(commands):
                raise RuntimeError('intentional compiler failure')
            if command[3] == str(compiler):
                Path(command[-1]).write_text('stock generated source')
            elif '-O3' in command:
                Path(command[-1]).write_bytes(b'compiled engine')
            elif '--gpu-build' in command:
                (output/'engine.gpu').write_bytes(b'compiled archive')
            else:
                expected_request, response = build.smoke_bytes()
                self.assertEqual(kwargs['input'], expected_request)
                return mock.Mock(stdout=b'invalid' if malformed_smoke else response)
            return mock.Mock(stdout=None)

        with mock.patch.object(build.subprocess, 'run', side_effect=execute), \
                mock.patch.object(build.sys, 'platform', 'darwin'), redirect_stdout(io.StringIO()):
            if fail_at or malformed_smoke:
                with self.assertRaises((RuntimeError, AssertionError)):
                    build.run(args)
            else:
                build.run(args)
        self.assertEqual(binary.read_bytes(), b'original engine')
        return commands, json.loads((output/'report.json').read_text())

    def test_native_build_has_one_compiler_cpu_smoke_and_no_gpu_build(self):
        commands, report = self.exercise('native')
        self.assertEqual(len(commands), 3)
        self.assertEqual(report['status'], 'passed')
        self.assertEqual(commands[2][-5:], ['cpu', '--threads', '2', '--gpu', 'off'])
        self.assertFalse(any('--gpu-build' in command for command in commands))
        self.assertEqual(report['cpu_protocol']['requests'], 3)

    def test_metal_only_builds_archive_in_new_output(self):
        commands, report = self.exercise('metal')
        self.assertEqual(len(commands), 1)
        self.assertEqual(commands[0][-3:], ['--threads', '2', '--gpu-build'])
        self.assertEqual(report['status'], 'passed')
        self.assertEqual(report['input_binary_sha256'], report['binary_sha256'])
        self.assertIn('gpu_archive_sha256', report)

    def test_failed_compile_does_not_run_or_retry(self):
        commands, report = self.exercise('native', fail_at=2)
        self.assertEqual(len(commands), 2)
        self.assertEqual(report['status'], 'failed')
        self.assertEqual(report['active_step'], 'compile')
        self.assertNotIn('binary_sha256', report)

    def test_invalid_protocol_cannot_pass_build(self):
        commands, report = self.exercise('native', malformed_smoke=True)
        self.assertEqual(len(commands), 3)
        self.assertEqual(report['status'], 'failed')
        self.assertNotIn('cpu_protocol', report)

    def test_supervisor_failure_overrides_partial_child_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for status in ('timed_out', 'cancelled', 'cleanup_failed'):
                (output/'report.json').write_text(json.dumps(dict(status='running', active_step='emit')))
                (output/'run-control.json').write_text(json.dumps(dict(status=status, exit_code=124)))
                build.record_control(output)
                report = json.loads((output/'report.json').read_text())
                self.assertEqual(report['status'], status)
                self.assertEqual(report['active_step'], 'emit')

    def test_existing_output_reports_are_not_rewritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            report = output/'report.json'; report.write_text('{"status": "running"}')
            (output/'run-control.json').write_text('{"status": "timed_out"}')
            before = report.read_bytes()
            with mock.patch.object(build.sys, 'argv', ['build_bend_worker.py', 'native', '--output', str(output)]):
                with self.assertRaises(FileExistsError): build.main()
            self.assertEqual(report.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
