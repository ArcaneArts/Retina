#!/usr/bin/env python3
"""Alternate GPU lighting modes/libraries on identical exported blocks.
Export fixtures with RETINA_LIGHTING_EXPORT=<dir> ./gradlew lightingTest.
The optional lightingRegionDirectory Gradle property adds full-height real-terrain tiles.
"""
import argparse
import ctypes as c
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import statistics
import time

from saved_definition import Request, SavedDefinition

class Snapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("flags", c.c_uint32), ("chunks", c.c_uint64),
                ("columns", c.c_uint64), ("jobs", c.c_uint64), ("nanos", c.c_uint64 * 33)]

def library(path):
    lib = c.CDLL(str(path.resolve()))
    lib.retina_initialize.restype = c.c_int
    lib.retina_light_volume.argtypes = [c.POINTER(Request), c.c_uint32, c.POINTER(c.c_uint16),
                                      c.c_uint64, c.c_void_p, c.c_uint64]
    lib.retina_timing_snapshot.argtypes = [c.c_uint32, c.POINTER(Snapshot)]
    lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
    def check(status):
        if status:
            error = c.create_string_buffer(8192)
            lib.retina_last_error(error, len(error))
            raise RuntimeError(error.value.decode())
    check(lib.retina_initialize())
    return lib, check

def summary(samples):
    ordered = sorted(samples)
    return {"median_ms": statistics.median(samples), "p95_ms": ordered[min(len(ordered)-1, int(len(ordered)*.95))]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--reference-library", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--iterations", type=int, default=21)
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--modes", nargs="+", choices=("dense", "sparse", "reference"), default=["dense", "sparse"],
                        help="reference measures the retained library in sparse mode")
    args = parser.parse_args()
    if "reference" in args.modes and not args.reference_library:
        parser.error("reference mode requires --reference-library")
    paths = sorted(args.fixtures.glob("*.json"))
    if not paths:
        parser.error("no lighting fixtures found")
    lib, check = library(args.library)
    reference = library(args.reference_library) if args.reference_library else None
    report = {"library": str(args.library), "reference_library": str(args.reference_library) if reference else None,
              "library_sha256": hashlib.sha256(args.library.read_bytes()).hexdigest(),
              "reference_sha256": hashlib.sha256(args.reference_library.read_bytes()).hexdigest() if reference else None,
              "input_transport": os.environ.get("RETINA_LIGHTING_INPUT", "default"),
              "iterations": args.iterations, "warmups": args.warmups, "fixtures": []}
    for path in paths:
        fixture = json.loads(path.read_text())
        with ExitStack() as owners:
            definition_path = path.parent / fixture["definition"]
            world = owners.enter_context(SavedDefinition(lib, definition_path, fixture["dimension"]))
            raw = (path.parent / fixture["blocks"]).read_bytes()
            blocks = (c.c_uint16 * (len(raw)//2)).from_buffer_copy(raw)
            request = world.request(0, 0)
            side, height = fixture["side"], request.height
            output = c.create_string_buffer(side * side * (height+32) * 256)
            expected = None
            if reference:
                old, old_check = reference
                old_world = owners.enter_context(SavedDefinition(old, definition_path, fixture["dimension"]))
                old_request = old_world.request(0, 0)
                os.environ["RETINA_LIGHTING_DENSE"] = "0"
                old_check(old.retina_light_volume(c.byref(old_request), side, blocks, len(blocks), output, len(output)))
                expected = hashlib.sha256(output).hexdigest()
            samples = {mode: {"host": [], "device": [], "initialization": [], "propagation": [], "packing": []}
                       for mode in args.modes}
            for iteration in range(args.warmups + args.iterations):
                modes = args.modes if iteration % 2 == 0 else list(reversed(args.modes))
                for mode in modes:
                    active, active_check, active_request = (old, old_check, old_request) if mode == "reference" else (lib, check, request)
                    os.environ["RETINA_LIGHTING_DENSE"] = "1" if mode == "dense" else "0"
                    before, after = Snapshot(), Snapshot()
                    active_check(active.retina_timing_snapshot(active_request.profile, c.byref(before)))
                    start = time.perf_counter_ns()
                    active_check(active.retina_light_volume(c.byref(active_request), side, blocks, len(blocks), output, len(output)))
                    elapsed = (time.perf_counter_ns()-start)/1e6
                    active_check(active.retina_timing_snapshot(active_request.profile, c.byref(after)))
                    digest = hashlib.sha256(output).hexdigest()
                    if expected is None:
                        expected = digest
                    if digest != expected:
                        raise RuntimeError(f"lighting output differs: {path.name} / {mode}")
                    if iteration >= args.warmups:
                        samples[mode]["host"].append(elapsed)
                        samples[mode]["device"].append(sum(after.nanos[i]-before.nanos[i] for i in (30,31,32))/1e6)
                        samples[mode]["initialization"].append((after.nanos[30]-before.nanos[30])/1e6)
                        samples[mode]["propagation"].append((after.nanos[31]-before.nanos[31])/1e6)
                        samples[mode]["packing"].append((after.nanos[32]-before.nanos[32])/1e6)
            result = {"name": path.stem, "side_chunks": side, "height": height, "sha256": expected,
                      "definition_sha256": hashlib.sha256(definition_path.read_bytes()).hexdigest(),
                      "dimension": fixture["dimension"], "seed": world.metadata["seed"],
                      "blocks_sha256": hashlib.sha256(raw).hexdigest(), "samples_ms": samples,
                      "modes": {mode: {stage: summary(values) for stage, values in stages.items()}
                                for mode, stages in samples.items()}}
            if "dense" in samples and "sparse" in samples:
                result["host_speedup"] = result["modes"]["dense"]["host"]["median_ms"] / result["modes"]["sparse"]["host"]["median_ms"]
            if "reference" in samples and "sparse" in samples:
                baseline = result["modes"]["reference"]
                candidate = result["modes"]["sparse"]
                result["reference_speedup"] = {stage: baseline[stage]["median_ms"] / candidate[stage]["median_ms"]
                                               if candidate[stage]["median_ms"] else None for stage in samples["sparse"]}
            report["fixtures"].append(result)
            print(json.dumps(result), flush=True)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2)+"\n")

if __name__ == "__main__":
    main()
