import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

from foreground_probe import ForegroundProbes, Samples
from saved_definition import Request


class Query:
    def __init__(self):
        self.block = self.corrupt = False
        self.entered, self.release = threading.Event(), threading.Event()

    def __call__(self, request, points, count, kind, output):
        if self.block:
            self.entered.set()
            self.release.wait()
        for i in range(count):
            output[i] = points[i].x % 7 + int(self.corrupt)
        return 0


class ForegroundProbeTest(unittest.TestCase):
    def make_probe(self, output):
        query = Query()
        library = type("Library", (), {})()
        library.retina_query_points = query
        request = Request(min_y=-64, height=384)
        return ForegroundProbes(library, request, output, batch=2, batches=2,
                                pause_seconds=.001, sample_limit=4), query

    def test_context_drains_admitted_query_before_returning_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            probe, query = self.make_probe(Path(directory) / "result.json")
            probe.__enter__()
            query.block = True
            probe.begin(time.perf_counter())
            self.assertTrue(query.entered.wait(2))
            probe.end_window(time.perf_counter())
            closed = threading.Event()
            closer = threading.Thread(target=lambda: (probe.__exit__(None, None, None), closed.set()))
            closer.start()
            try:
                self.assertFalse(closed.wait(.03))
            finally:
                query.release.set()
                closer.join(2)
            self.assertTrue(closed.is_set())
            self.assertTrue(probe.finished.is_set())
            self.assertFalse(probe.thread.is_alive())
            self.assertEqual(probe.report()["concurrent"]["count"], 1)

    def test_changed_answers_fail_after_query_thread_is_drained(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            probe, query = self.make_probe(output)
            with self.assertRaisesRegex(AssertionError, "biome values changed"):
                with probe:
                    query.corrupt = True
                    probe.begin(time.perf_counter())
                    self.assertTrue(probe.finished.wait(2))
            self.assertFalse(probe.thread.is_alive())
            report = json.loads(output.read_text())
            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["concurrent"]["count"], 1)

    def test_interrupted_thread_start_never_admits_native_work(self):
        with tempfile.TemporaryDirectory() as directory:
            probe, query = self.make_probe(Path(directory) / "result.json")
            query.block = True
            start = probe.thread.start

            def interrupted_start():
                start()
                raise KeyboardInterrupt()

            probe.thread.start = interrupted_start
            try:
                with self.assertRaises(KeyboardInterrupt):
                    probe.__enter__()
                self.assertTrue(probe.finished.wait(2))
                self.assertFalse(query.entered.is_set())
            finally:
                query.release.set()
                probe.thread.join(2)
            self.assertFalse(probe.thread.is_alive())

    def test_reservoir_bounds_retention_without_losing_exact_aggregate(self):
        samples = Samples(4)
        for value in range(100):
            samples.add({"ns": value})
        report = samples.report()
        self.assertEqual(report["count"], 100)
        self.assertEqual(report["retained_samples"], 4)
        self.assertTrue(report["reservoir_sampled"])
        self.assertEqual(report["total_ns"], 4950)
        self.assertEqual(report["minimum_ns"], 0)
        self.assertEqual(report["maximum_ns"], 99)


if __name__ == "__main__":
    unittest.main()
