#!/usr/bin/env python3
"""Repeatable real-GPU MCA benchmark. Outputs are private temporary regions, never live saves.
Use a profile exported by structureTest and --library to compare retained native builds.
"""
import argparse
import concurrent.futures
import ctypes as c
import json
import hashlib
from pathlib import Path
import platform
import resource
import statistics
import time
import zlib

class Request(c.Structure):
    _fields_ = [("seed", c.c_uint64), ("x", c.c_int32), ("z", c.c_int32), ("min_y", c.c_int32),
                ("height", c.c_uint32), ("base", c.c_float), ("amplitude", c.c_float), ("frequency", c.c_float), ("profile", c.c_uint32)]
class Report(c.Structure):
    _fields_ = [("generated", c.c_uint32), ("preserved", c.c_uint32)] + [(s, c.c_uint64) for s in ("gpu", "assembly", "write", "bytes")]
class Snapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("flags", c.c_uint32), ("chunks", c.c_uint64), ("columns", c.c_uint64),
                ("jobs", c.c_uint64), ("nanos", c.c_uint64 * 20)]
STAGES = ["queue", "encode", "wait_copy", "height", "sites", "columns", "cave_density", "cave_mask",
          "structure_plan", "vegetation_plan", "ore_plan", "assembly", "geology", "cave_features", "vegetation", "structures", "snow", "nbt", "compress", "io"]
class PipelineSnapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("peak_in_flight", c.c_uint32)] + [(s, c.c_uint64) for s in
                ("completed", "device_span_nanos", "device_gap_nanos", "unavailable_timestamp_pairs")]
class ProgramSnapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("status", c.c_uint32), ("compile_nanos", c.c_uint64), ("source_bytes", c.c_uint64)] + [(s,c.c_uint32) for s in ("nodes","emitted","graphs","horizontal_fields")] + [(s,c.c_uint64) for s in ("cache_hits","upload_bytes","readback_bytes")]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--compare", type=Path, help="A matched baseline output directory; fail on any changed chunk NBT")
    parser.add_argument("--parallel", type=int, choices=(1,2), default=1)
    parser.add_argument("--seed", type=int, default=123456789)
    parser.add_argument("--count", type=int, default=6)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--program-execution", choices=("auto", "interpreter", "specialized"), help="Override GPU program mode for matched diagnostics")
    parser.add_argument("--await-specialization", action="store_true", help="After measurement, await compilation and compare one regenerated region with its pre-warmup output")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    lib = c.CDLL(str(args.library.resolve()))
    lib.retina_initialize.restype = c.c_int
    lib.retina_register_profile.argtypes = [c.c_char_p, c.c_uint64, c.POINTER(c.c_uint32)]
    lib.retina_generate_region.argtypes = [c.POINTER(Request), c.c_char_p, c.c_uint64, c.c_int32, c.c_char_p, c.c_uint64, c.POINTER(Report)]
    lib.retina_timing_snapshot.argtypes = [c.c_uint32, c.POINTER(Snapshot)]
    lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
    def check(status):
        if status:
            buf = c.create_string_buffer(8192); lib.retina_last_error(buf, len(buf))
            raise RuntimeError(buf.value.decode())
    startup = time.perf_counter()
    check(lib.retina_initialize())
    initialize_ms = (time.perf_counter()-startup)*1000
    source = args.profile.read_bytes(); profile = c.c_uint32()
    if args.program_execution:
        value = json.loads(source); value["program_execution"] = args.program_execution
        source = json.dumps(value, separators=(",", ":")).encode()
    registration = time.perf_counter()
    check(lib.retina_register_profile(source, len(source), c.byref(profile)))
    registration_ms = (time.perf_counter()-registration)*1000
    def generate(item):
        name, x, z = item
        path = str((args.out / f"{name}.mca").resolve()).encode()
        req = Request(args.seed, x*32, z*32, -64, 384, 64, 48, .008, profile.value)
        report = Report(); start = time.perf_counter()
        check(lib.retina_generate_region(c.byref(req), path, len(path), 0, b"minecraft:plains", 16, c.byref(report)))
        return dict(name=name, x=x, z=z, ms=(time.perf_counter()-start)*1000, generated=report.generated,
                    gpu_ms=report.gpu/1e6, assembly_ms=report.assembly/1e6, write_ms=report.write/1e6)
    warmups = [generate((f"warm{i}",8+i,8)) for i in range(args.warmups)]
    before = Snapshot(); check(lib.retina_timing_snapshot(profile, c.byref(before)))
    program = getattr(lib,"retina_gpu_program_snapshot",None)
    before_program=ProgramSnapshot()
    if program:
        program.argtypes=[c.c_uint32,c.POINTER(ProgramSnapshot)]
        check(program(profile,c.byref(before_program)))
    pipeline = getattr(lib, "retina_gpu_pipeline_snapshot", None)
    before_pipeline = PipelineSnapshot()
    if pipeline:
        pipeline.argtypes = [c.POINTER(PipelineSnapshot)]
        check(pipeline(c.byref(before_pipeline)))
    # Adjacent tiles exercise shared structure halos and cold full-region terrain.
    coords = [(str(i), i%3-1, i//3-1) for i in range(args.count)]
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(args.parallel) as workers:
        regions = list(workers.map(generate, coords))
    wall = time.perf_counter()-start
    after = Snapshot(); check(lib.retina_timing_snapshot(profile, c.byref(after)))
    chunks = after.chunks-before.chunks
    data = dict(library=str(args.library), parallel=args.parallel, seed=args.seed, regions=regions, program_execution=args.program_execution,
                total_ms=wall*1000, chunks_per_second=chunks/wall, median_ms=statistics.median(r["ms"] for r in regions),
                average_region_ms=statistics.mean(r["ms"] for r in regions),
                startup=dict(initialize_ms=initialize_ms, registration_ms=registration_ms, warmups=warmups),
                profile_sha256=hashlib.sha256(source).hexdigest(),
                region_file_bytes=sum((args.out/f"{name}.mca").stat().st_size for name,_,_ in coords),
                peak_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if platform.system()=="Darwin" else 1024),
                gpu_timestamps=bool(after.flags & 1),
                stage_ms_chunk={name:(after.nanos[i]-before.nanos[i])/max(chunks,1)/1e6 for i,name in enumerate(STAGES)})
    if pipeline:
        after_pipeline = PipelineSnapshot(); check(pipeline(c.byref(after_pipeline)))
        data["gpu_pipeline"] = {"peak_in_flight": after_pipeline.peak_in_flight,
            "completed": after_pipeline.completed-before_pipeline.completed,
            "device_span_ms": (after_pipeline.device_span_nanos-before_pipeline.device_span_nanos)/1e6,
            "device_gap_ms": (after_pipeline.device_gap_nanos-before_pipeline.device_gap_nanos)/1e6,
            "unavailable_timestamp_pairs": after_pipeline.unavailable_timestamp_pairs-before_pipeline.unavailable_timestamp_pairs}
    if program:
        after_program=ProgramSnapshot();check(program(profile,c.byref(after_program)))
        data["gpu_program"]={name:getattr(after_program,name) for name,_ in ProgramSnapshot._fields_ if name != "version"}
        data["gpu_transfers"]={"upload_bytes":after_program.upload_bytes-before_program.upload_bytes,"readback_bytes":after_program.readback_bytes-before_program.readback_bytes,
            "upload_bytes_per_region":(after_program.upload_bytes-before_program.upload_bytes)/len(regions),"readback_bytes_per_region":(after_program.readback_bytes-before_program.readback_bytes)/len(regions)}
    def records(path):
        data = path.read_bytes()
        for i in range(1024):
            location = int.from_bytes(data[i*4:i*4+4], "big")
            offset = (location >> 8)*4096
            length = int.from_bytes(data[offset:offset+4], "big")
            if not location or data[offset+4] != 2: raise ValueError(f"Invalid benchmark MCA record: {path}:{i}")
            yield zlib.decompress(data[offset+5:offset+4+length])
    if args.compare:
        identical = 0
        for name, _, _ in coords:
            for slot, (old, new) in enumerate(zip(records(args.compare/f"{name}.mca"), records(args.out/f"{name}.mca"), strict=True)):
                if old != new: raise AssertionError(f"Changed NBT: {name}.mca, slot {slot}")
                identical += 1
        data["identical_nbt_chunks"] = identical
    if args.await_specialization:
        if not program: raise RuntimeError("The loaded library does not expose program diagnostics")
        awaited=ProgramSnapshot();check(program(profile,c.byref(awaited)))
        wait_start=time.perf_counter()
        print(f"Awaiting specialization after measuring {len(regions)} regions; status={awaited.status}",flush=True)
        while awaited.status==1 and time.perf_counter()-wait_start<180:
            time.sleep(.1);check(program(profile,c.byref(awaited)))
        if awaited.status!=2: raise RuntimeError(f"Specialization did not become ready: status={awaited.status}")
        _,x,z=coords[0]
        regenerated=generate(("compiled_check",x,z))
        for slot,(old,new) in enumerate(zip(records(args.out/f"{coords[0][0]}.mca"),records(args.out/"compiled_check.mca"),strict=True)):
            if old!=new: raise AssertionError(f"Compilation changed NBT in slot {slot}")
        data["specialization_warmup_check"]={"wait_after_measurement_ms":(time.perf_counter()-wait_start)*1000,
            "compile_ms":awaited.compile_nanos/1e6,"identical_nbt_chunks":1024,"regenerated_region":regenerated}
    data["peak_rss_bytes"]=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if platform.system()=="Darwin" else 1024)
    (args.out/"measurements.json").write_text(json.dumps(data, indent=2)+"\n")
    print(json.dumps(data, indent=2))
if __name__ == "__main__": main()
