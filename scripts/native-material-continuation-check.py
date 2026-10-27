#!/usr/bin/env python3
"""Bounded retained-library A/B for uncached material-bearing chunks.

Run under a process-group deadline. This compares full block/column bytes through
production worker queues; it does not measure MCA encoding or region throughput.
"""
import argparse
import concurrent.futures
from contextlib import ExitStack
import ctypes as c
import hashlib
import json
from pathlib import Path
import resource
import statistics
import time


from saved_definition import Request, SavedDefinition


class Column(c.Structure):
    _fields_ = [("height", c.c_int32), ("packed", c.c_uint32), ("materials", c.c_uint32)]


class Pipeline(c.Structure):
    _fields_ = [("version", c.c_uint32), ("peak", c.c_uint32)] + [
        (name, c.c_uint64) for name in ("completed", "span", "gap", "unavailable")]


def load(path, definition, dimension, owners):
    lib = c.CDLL(str(path.resolve()))
    lib.retina_generate_chunk_columns_u16.argtypes = [c.POINTER(Request), c.POINTER(c.c_uint16), c.c_uint64, c.POINTER(Column)]
    lib.retina_gpu_pipeline_snapshot.argtypes = [c.POINTER(Pipeline)]
    lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]

    def check(status):
        if status:
            error = c.create_string_buffer(8192)
            lib.retina_last_error(error, len(error))
            raise RuntimeError(error.value.decode())

    check(lib.retina_initialize())
    world = owners.enter_context(SavedDefinition(lib, definition, dimension, execution="interpreter"))
    return lib, check, world


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--definition", type=Path, required=True)
    parser.add_argument("--dimension", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--pairs", type=int, choices=range(1, 11), default=5)
    parser.add_argument("--chunks", type=int, choices=range(2, 17, 2), default=8)
    args = parser.parse_args()
    with ExitStack() as owners:
        libraries = {mode: load(path, args.definition, args.dimension, owners) for mode, path in
                     (("baseline", args.baseline), ("candidate", args.candidate))}
        samples = {mode: [] for mode in libraries}
        hashes = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            def batch(mode, index):
                lib, check, world = libraries[mode]
                before = Pipeline()
                check(lib.retina_gpu_pipeline_snapshot(c.byref(before)))
                requests = [world.request(-512 + i * 32, -512 + index * 32) for i in range(args.chunks)]
                buffers = [((c.c_uint16 * (256 * request.height))(), (Column * 256)())
                           for request in requests]

                def chunk(i):
                    request = requests[i]
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
        world = libraries["baseline"][2]
        report = {"definition": str(args.definition), "definition_sha256": hashlib.sha256(args.definition.read_bytes()).hexdigest(),
                  "dimension": args.dimension, "seed": world.metadata["seed"],
                  "library_sha256": {mode: hashlib.sha256(path.read_bytes()).hexdigest() for mode, path in
                                     (("baseline", args.baseline), ("candidate", args.candidate))},
                  "height": world.request(0, 0).height, "chunks_per_batch": args.chunks, "callers": 2,
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
