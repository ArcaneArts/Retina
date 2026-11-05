"""Bounded, read-only native biome probes alongside complete-region generation.

The caller retains its SavedDefinition until this context drains every admitted
query. This models native query fairness, not Minecraft rendering or Java work.
"""
from __future__ import annotations

import ctypes as c
import hashlib
import random
import struct
import threading
import time

from benchmark_support import percentile, write_json
from saved_definition import Request, check


class Point(c.Structure):
    _fields_ = [(name, c.c_int32) for name in ("x", "y", "z")]


class Samples:
    def __init__(self, limit=16_384):
        self.limit, self.rows, self.count = limit, [], 0
        self.total, self.minimum, self.maximum = 0, None, 0
        self.random = random.Random(0x524554494E41)

    def add(self, row):
        self.count += 1
        value = row["ns"]
        self.total += value
        self.minimum = value if self.minimum is None else min(self.minimum, value)
        self.maximum = max(self.maximum, value)
        if len(self.rows) < self.limit:
            self.rows.append(row)
        else:
            index = self.random.randrange(self.count)
            if index < self.limit:
                self.rows[index] = row

    def report(self):
        values = [row["ns"] for row in self.rows]
        return {"count": self.count, "retained_samples": len(values),
                "reservoir_sampled": self.count > self.limit,
                "total_ns": self.total, "minimum_ns": self.minimum, "maximum_ns": self.maximum,
                "mean_ns": self.total / self.count if self.count else None,
                "median_ns": percentile(values, 50) if values else None,
                "descriptive_p95_ns": percentile(values, 95) if values else None,
                "samples": self.rows}


class ForegroundProbes:
    def __init__(self, library, request, output, *, batch=8, batches=2048,
                 pause_seconds=.010, sample_limit=16_384, snapshot_completed_samples=None):
        self.library, self.request, self.output = library, request, output
        self.batch, self.batches, self.pause = batch, batches, pause_seconds
        self.snapshot_completed_samples = snapshot_completed_samples
        self.query = library.retina_query_points
        self.query.argtypes = [c.POINTER(Request), c.POINTER(Point), c.c_uint64,
                               c.c_uint32, c.POINTER(c.c_int32)]
        self.query.restype = c.c_int
        self.prepare_event = threading.Event()
        self.ready, self.begin_event = threading.Event(), threading.Event()
        self.stop, self.finished = threading.Event(), threading.Event()
        self.thread = threading.Thread(target=self._run, name="retina-foreground-probes")
        self.started, self.failure = False, None
        self.idle, self.concurrent = Samples(sample_limit), Samples(sample_limit)
        self.window_start_ns = self.window_end_ns = None
        self.last_query_end_ns = None
        self.idle_completed_samples = self.answers_sha256 = None
        self.reference_completed_samples = self.reference_ns = None
        self.idle_batches = min(256, batches)
        self.inputs, self.answers = [], []
        digest = hashlib.sha256()
        for group in range(batches):
            points = []
            for offset in range(batch):
                index = group * batch + offset
                xyz = (1_048_576 + index * 148,
                       request.min_y + ((index * 17) % (request.height // 4)) * 4,
                       -1_048_576 + ((index * 191) % 32771) * 4)
                digest.update(struct.pack("<iii", *xyz))
                points.append(Point(*xyz))
            self.inputs.append((Point * batch)(*points))
        self.fixture_sha256 = digest.hexdigest()

    def _query_points(self, points):
        output = (c.c_int32 * len(points))()
        start = time.perf_counter_ns()
        status = self.query(c.byref(self.request), points, len(points), 0, output)
        end = time.perf_counter_ns()
        check(self.library, status)
        return tuple(output), {"start_ns": start, "end_ns": end, "ns": end - start}

    def _query(self, index):
        answer, sample = self._query_points(self.inputs[index])
        sample["fixture_batch"] = index
        return answer, sample

    def _run(self):
        try:
            # Do not admit native work until start() returned and ownership is
            # recorded. An interrupt inside Thread.start may still launch us.
            self.prepare_event.wait()
            if self.stop.is_set():
                return
            before = self.snapshot_completed_samples() if self.snapshot_completed_samples else None
            # Collect the complete answer corpus in bounded API batches. This
            # setup is not an eight-point latency measurement. Native code
            # subdivides the API's maximum 1,024-point call into its own batches.
            reference_start = time.perf_counter_ns()
            points = [point for group in self.inputs for point in group]
            answers = []
            for offset in range(0, len(points), 1024):
                if self.stop.is_set():
                    return
                group = points[offset:offset + 1024]
                answer, _ = self._query_points((Point * len(group))(*group))
                answers.extend(answer)
            self.reference_ns = time.perf_counter_ns() - reference_start
            self.answers = [tuple(answers[i:i + self.batch]) for i in range(0, len(answers), self.batch)]
            if self.snapshot_completed_samples:
                after_reference = self.snapshot_completed_samples()
                self.reference_completed_samples = after_reference - before
                before = after_reference
            # Idle and concurrent latency use this same thread and small batch.
            # The 16,384-point corpus exceeds today's 8,192-entry query cache.
            for index in range(self.idle_batches):
                if self.stop.is_set():
                    return
                answer, sample = self._query(index)
                if answer != self.answers[index]:
                    raise AssertionError(f"Biome answers changed with API batch shape at fixture batch {index}")
                self.idle.add(sample)
            if self.snapshot_completed_samples:
                self.idle_completed_samples = self.snapshot_completed_samples() - before
            self.answers_sha256 = hashlib.sha256(b"".join(
                struct.pack("<i", value) for answer in self.answers for value in answer)).hexdigest()
            self.ready.set()
            self.begin_event.wait()
            # Skip the idle latency queries; begin in the evicted portion of
            # the corpus rather than measuring those newly warmed cache hits.
            index = self.idle_batches % self.batches
            while not self.stop.is_set():
                answer, sample = self._query(index)
                self.last_query_end_ns = sample["end_ns"]
                self.concurrent.add(sample)
                if answer != self.answers[index]:
                    raise AssertionError(f"Foreground biome values changed at fixture batch {index}")
                index = (index + 1) % self.batches
                self.stop.wait(self.pause)
        except BaseException as error:
            self.failure = error
        finally:
            self.ready.set()
            self.finished.set()

    def __enter__(self):
        try:
            self.thread.start()
            self.started = True
            self.prepare_event.set()
            self.ready.wait()
            if self.failure:
                raise self.failure
            return self
        except BaseException as error:
            self._drain(error)
            raise

    def begin(self, monotonic_seconds):
        self.window_start_ns = int(monotonic_seconds * 1_000_000_000)
        self.begin_event.set()

    def end_window(self, monotonic_seconds):
        self.window_end_ns = int(monotonic_seconds * 1_000_000_000)

    def _drain(self, original_error):
        self.stop.set()
        self.prepare_event.set()
        self.begin_event.set()
        interrupted = None
        if self.started:
            # An interrupted Thread.join can report a stopped Python thread
            # before its foreign call returns. Our own finally-event proves the
            # call is drained before the definition owner may close.
            while not self.finished.is_set():
                try:
                    self.finished.wait(.1)
                except KeyboardInterrupt as error:
                    interrupted = error
            try:
                self.thread.join()
            except KeyboardInterrupt as error:
                interrupted = error
        error = original_error or self.failure or interrupted
        write_json(self.output, self.report(error))
        if original_error is not None and self.failure is not None and original_error is not self.failure:
            original_error.add_note(f"Foreground probe also failed: {self.failure!r}")
        if original_error is None and error is not None:
            raise error

    def __exit__(self, kind, error, traceback):
        self._drain(error)
        return False

    def report(self, error=None):
        return {"schema": "retina.foreground-biome-probes.v1", "status": "failed" if error else "pass",
                "error": repr(error) if error else None, "batch_size": self.batch,
                "corpus_batches": self.batches, "unique_points": self.batch * self.batches,
                "fixture_sha256": self.fixture_sha256, "pause_after_query_seconds": self.pause,
                "idle_completed_gpu_samples_delta": self.idle_completed_samples,
                "reference_completed_gpu_samples_delta": self.reference_completed_samples,
                "reference_setup_ns": self.reference_ns,
                "gpu_sample_counter_scope": "Legacy pipeline.completed counts completed terrain/query samples; a sample can require multiple physical submissions. CPU/constant shortcuts may have zero. This never gates generation.",
                "idle_answers_sha256": self.answers_sha256,
                "region_window_start_ns": self.window_start_ns, "region_window_end_ns": self.window_end_ns,
                "foreground_tail_ns": max(0, self.last_query_end_ns - self.window_end_ns)
                    if self.last_query_end_ns is not None and self.window_end_ns is not None else None,
                "idle": self.idle.report(), "concurrent": self.concurrent.report(),
                "scope": "Uncached remote-coordinate stress workload. Exact same-backend biome values checked against batched answer reference; idle small-call and concurrent small-call values also verify batch-shape invariance. Same query thread is warmed before measurement. One foreground call at a time with think time after completion; request count varies with region duration. Latency includes native call/queue, excludes result comparison. Reference setup is separate from idle latency; both precede every measured native delta. Region completion/CPU window excludes foreground drain tail; native timing/profile snapshots and optional GPU interval end after drain. Not a player/FPS/Java workload. Reservoir sampling is deterministic and bounded; all answers remain checked."}
