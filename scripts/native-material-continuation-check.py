#!/usr/bin/env python3
"""Bounded retained-library A/B for uncached material-bearing chunks.

Run under a process-group deadline. This compares full block/column bytes through
production worker queues; it does not measure MCA encoding or region throughput.
"""
import argparse
import concurrent.futures
import ctypes as c
import hashlib
import json
from pathlib import Path
import resource
import statistics
import time


class Request(c.Structure):
    _fields_ = [("seed", c.c_uint64), ("x", c.c_int32), ("z", c.c_int32),
                ("min_y", c.c_int32), ("height", c.c_uint32), ("base", c.c_float),
                ("amplitude", c.c_float), ("frequency", c.c_float), ("profile", c.c_uint32)]


class Column(c.Structure):
    _fields_ = [("height", c.c_int32), ("packed", c.c_uint32), ("materials", c.c_uint32)]


class Pipeline(c.Structure):
    _fields_ = [("version", c.c_uint32), ("peak", c.c_uint32)] + [
        (name, c.c_uint64) for name in ("completed", "span", "gap", "unavailable")]


def load(path, source):
    lib = c.CDLL(str(path.resolve()))
    lib.retina_register_profile.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint32)]
    lib.retina_generate_chunk_columns_u16.argtypes = [c.POINTER(Request), c.POINTER(c.c_uint16), c.c_uint64, c.POINTER(Column)]
    lib.retina_gpu_pipeline_snapshot.argtypes = [c.POINTER(Pipeline)]
    lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]

    def check(status):
        if status:
            error = c.create_string_buffer(8192)
            lib.retina_last_error(error, len(error))
            raise RuntimeError(error.value.decode())

    check(lib.retina_initialize())
    pid = c.c_uint32()
    check(lib.retina_register_profile(source, len(source), c.byref(pid)))
    return lib, check, pid.value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--pairs", type=int, choices=range(1, 11), default=5)
    parser.add_argument("--chunks", type=int, choices=range(2, 17, 2), default=8)
    parser.add_argument("--height", type=int, choices=(64, 128, 192, 384), default=128)
    args = parser.parse_args()
    profile = json.loads(args.profile.read_bytes())
    profile["program_execution"] = "interpreter"
    source = json.dumps(profile, separators=(",", ":")).encode()
    libraries = {mode: load(path, source) for mode, path in
                 (("baseline", args.baseline), ("candidate", args.candidate))}
    samples = {mode: [] for mode in libraries}
    hashes = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
        def batch(mode, index):
            lib, check, pid = libraries[mode]
            before = Pipeline()
            check(lib.retina_gpu_pipeline_snapshot(c.byref(before)))
            buffers = [((c.c_uint16 * (256 * args.height))(), (Column * 256)())
                       for _ in range(args.chunks)]

            def chunk(i):
                request = Request(123456789, -512 + i * 32, -512 + index * 32,
                                  -64, args.height, 48, 24, 0.008, pid)
                blocks, columns = buffers[i]
                start = time.perf_counter_ns()
                check(lib.retina_generate_chunk_columns_u16(c.byref(request), blocks, len(blocks), columns))
                return (time.perf_counter_ns() - start) / 1e6

            start = time.perf_counter_ns()
            latency = list(workers.map(chunk, range(args.chunks)))
            wall = (time.perf_counter_ns() - start) / 1e6
            after = Pipeline()
            check(lib.retina_gpu_pipeline_snapshot(c.byref(after)))
            assert after.completed - before.completed >= args.chunks, "must exercise uncached GPU jobs"
            digest = hashlib.sha256()
            for blocks, columns in buffers:
                digest.update(bytes(blocks))
                digest.update(bytes(columns))
            result = {"wall_ms": wall, "request_ms": latency,
                      "peak_in_flight": after.peak, "gpu_jobs": after.completed - before.completed,
                      "device_span_ms": (after.span - before.span) / 1e6}
            return result, digest.hexdigest()

        # One unrecorded warmup pair; every later batch uses disjoint coordinates.
        warm_a, warm_hash = batch("baseline", 0)
        warm_b, candidate_hash = batch("candidate", 0)
        assert warm_hash == candidate_hash, "warmup blocks/columns differ"
        for index in range(1, args.pairs + 1):
            pair = {}
            order = ("baseline", "candidate") if index % 2 else ("candidate", "baseline")
            for mode in order:
                result, digest = batch(mode, index)
                samples[mode].append(result)
                pair[mode] = digest
            assert pair["baseline"] == pair["candidate"], f"blocks/columns differ in pair {index}"
            hashes.append(pair["baseline"])
    report = {"profile": str(args.profile), "profile_sha256": hashlib.sha256(source).hexdigest(),
              "library_sha256": {mode: hashlib.sha256(path.read_bytes()).hexdigest() for mode, path in
                                 (("baseline", args.baseline), ("candidate", args.candidate))},
              "height": args.height, "chunks_per_batch": args.chunks, "callers": 2,
              "samples": samples, "output_sha256": hashes, "exact_outputs": True,
              "median_batch_ms": {mode: statistics.median(s["wall_ms"] for s in values)
                                  for mode, values in samples.items()},
              "max_request_ms": {mode: max(t for s in values for t in s["request_ms"])
                                 for mode, values in samples.items()},
              "combined_process_maxrss": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              "scope": "uncached material-bearing chunks; excludes MCA encoding/lighting; maxrss includes both libraries"}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("median_batch_ms", "max_request_ms", "exact_outputs", "combined_process_maxrss")}))


if __name__ == "__main__":
    main()
