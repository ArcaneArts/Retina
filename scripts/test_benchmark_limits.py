"""Supervisor tests use sleeping Python fixtures, never Bend, Rust or a GPU."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from benchmark_limits import positive_seconds, run_bounded, supervise


class BenchmarkLimitsTest(unittest.TestCase):
    def test_eperm_existence_probe_does_not_abort_group_cleanup(self):
        process = mock.Mock(pid=12345)
        process.wait.return_value = 0
        signals = []
        def killpg(pid, signum):
            self.assertEqual(pid, process.pid)
            signals.append(signum)
            if signum == 0:
                raise PermissionError('group is still exiting')
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch('benchmark_limits.subprocess.Popen', return_value=process), \
                mock.patch('benchmark_limits.os.killpg', side_effect=killpg):
            self.assertEqual(run_bounded(['mock-command'], 1, Path(folder), grace_seconds=.01), 0)
        self.assertEqual(signals[0], signal.SIGTERM)
        self.assertIn(0, signals)
        self.assertEqual(signals[-1], signal.SIGKILL)

    def test_actual_signal_denied_for_live_group_still_reports_failure(self):
        process = mock.Mock(pid=12345)
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch('benchmark_limits.subprocess.Popen', return_value=process), \
                mock.patch('benchmark_limits.os.killpg', side_effect=PermissionError('denied')), \
                mock.patch('benchmark_limits.subprocess.check_output', return_value='12345 S\n'):
            with self.assertRaises(PermissionError):
                run_bounded(['mock-command'], 1, Path(folder), grace_seconds=.01)
            control = json.loads((Path(folder)/'run-control.json').read_text())
            self.assertEqual(control['status'], 'cleanup_failed')

    def test_limit_rejects_unbounded_values(self):
        for value in ('0', '-1', 'inf', 'nan'):
            with self.assertRaises(argparse.ArgumentTypeError):
                positive_seconds(value)
        self.assertEqual(.25, positive_seconds('.25'))

    def test_existing_output_is_not_replaced(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            report = path/'report.json'
            report.write_text('original')
            with self.assertRaises(FileExistsError):
                supervise(__file__, 1, path)
            self.assertEqual('original', report.read_text())

    def test_success_and_failure_exit_codes(self):
        original = signal.getsignal(signal.SIGTERM)
        for expected in (0, 3):
            with tempfile.TemporaryDirectory() as folder:
                path = Path(folder)
                code = run_bounded([sys.executable, '-c', f'raise SystemExit({expected})'], 2, path)
                self.assertEqual(expected, code)
                control = json.loads((path/'run-control.json').read_text())
                self.assertEqual('completed' if not expected else 'failed', control['status'])
        self.assertEqual(original, signal.getsignal(signal.SIGTERM))

    def assert_terminated(self, pid):
        # Orphaned test children may briefly remain as zombies until init reaps
        # them. They are terminal and consume no CPU/GPU resources.
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
        self.assertTrue(not result.stdout.strip() or result.stdout.strip().startswith('Z'), result.stdout)

    def test_timeout_kills_ignoring_worker_and_keeps_checkpoint(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            worker = "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)"
            driver = f'''import json,subprocess,sys,time
from pathlib import Path
p=subprocess.Popen([sys.executable,'-c',{worker!r}])
Path({str(path/'pid')!r}).write_text(str(p.pid))
Path({str(path/'report.json')!r}).write_text(json.dumps({{'regions':[{{'ms':12}}],'status':'running'}}))
time.sleep(30)
'''
            started = time.monotonic()
            code = run_bounded([sys.executable, '-c', driver], .5, path, grace_seconds=.1)
            self.assertEqual(124, code)
            self.assertLess(time.monotonic()-started, 3)
            self.assert_terminated(int((path/'pid').read_text()))
            self.assertEqual([{'ms':12}], json.loads((path/'report.json').read_text())['regions'])
            self.assertEqual('timed_out', json.loads((path/'run-control.json').read_text())['status'])

    def test_driver_exit_cleans_up_orphan(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            driver = f'''import subprocess,sys
from pathlib import Path
p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'])
Path({str(path/'pid')!r}).write_text(str(p.pid))
raise SystemExit(7)
'''
            self.assertEqual(7, run_bounded([sys.executable, '-c', driver], 2, path, grace_seconds=.1))
            self.assert_terminated(int((path/'pid').read_text()))

    def test_sigterm_cancels_supervisor_and_child(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            driver = f'''import os,time
from pathlib import Path
Path({str(path/'pid')!r}).write_text(str(os.getpid()))
time.sleep(30)
'''
            parent = f'''from pathlib import Path
from benchmark_limits import run_bounded
raise SystemExit(run_bounded({[sys.executable,'-c',driver]!r},10,Path({folder!r}),grace_seconds=.1))
'''
            process = subprocess.Popen([sys.executable, '-c', parent], cwd=Path(__file__).parent)
            try:
                deadline = time.monotonic()+3
                while not (path/'pid').exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue((path/'pid').exists())
                process.send_signal(signal.SIGTERM)
                self.assertEqual(143, process.wait(timeout=3))
                self.assert_terminated(int((path/'pid').read_text()))
                self.assertEqual('cancelled', json.loads((path/'run-control.json').read_text())['status'])
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
