#!/usr/bin/env python3
"""Repeatable real-GPU MCA benchmark. Outputs are private temporary regions, never live saves.
Use a saved retina.dat and --library to compare retained native builds.
"""
import argparse
import atexit
import concurrent.futures
import ctypes as c
import json
import os
import hashlib
from pathlib import Path
import platform
import process_usage
import statistics
import time
import subprocess

from benchmark_support import (RESULT_SCHEMA, RESULT_SCHEMA_VERSION, compare_corpora,
                               corpus_manifest, mca_records, percentile, performance_interval,
                               region_coordinates, write_json)

from saved_definition import Request, SavedDefinition

class Report(c.Structure):
    _fields_ = [("generated", c.c_uint32), ("preserved", c.c_uint32)] + [(s, c.c_uint64) for s in ("gpu", "assembly", "write", "bytes")]
class Snapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("flags", c.c_uint32), ("chunks", c.c_uint64), ("columns", c.c_uint64),
                ("jobs", c.c_uint64), ("nanos", c.c_uint64 * 33)]
STAGES = ["queue", "encode", "wait_copy", "height", "sites", "columns", "cave_density", "cave_mask",
          "structure_plan", "vegetation_plan", "ore_plan", "assembly", "geology", "cave_features", "vegetation", "structures", "snow", "nbt", "compress", "io", "materials", "aquifer_fields", "aquifer_mask", "feature_counts", "ore_mask", "provider_noise", "lake_candidates", "lake_density", "lake_reduce", "light_host", "light_sky", "light_spread", "light_pack"]
class PipelineSnapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("peak_in_flight", c.c_uint32)] + [(s, c.c_uint64) for s in
                ("completed", "device_span_nanos", "device_gap_nanos", "unavailable_timestamp_pairs")]
class ProgramSnapshot(c.Structure):
    _fields_ = [("version", c.c_uint32), ("status", c.c_uint32), ("compile_nanos", c.c_uint64), ("source_bytes", c.c_uint64)] + [(s,c.c_uint32) for s in ("nodes","emitted","graphs","horizontal_fields")] + [(s,c.c_uint64) for s in ("cache_hits","upload_bytes","readback_bytes")]
class SpecializationSnapshot(c.Structure):
    _fields_ = [(s, c.c_uint32) for s in ("version", "status", "ready_groups", "total_groups")] + [(s, c.c_uint64) for s in ("compile_nanos", "source_bytes")]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--definition", type=Path, required=True)
    parser.add_argument("--dimension", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--compare", type=Path, help="A matched baseline output directory; fail on any changed chunk NBT")
    parser.add_argument("--startup-debug", action="store_true",
                        help="Enable detailed startup and region pipeline logs; keep separate from headline timings")
    parser.add_argument("--diagnostics", action="store_true",
                        help="Opt-in workload/wait attribution; keep these runs separate from headline timings")
    parser.add_argument("--parallel", type=int, choices=(1,2,4,8), default=1,
                        help="Concurrent callers; CPU worker limits remain separate")
    parser.add_argument("--origin-x", type=int, default=-1, help="First region X coordinate")
    parser.add_argument("--origin-z", type=int, default=-1, help="First region Z coordinate")
    parser.add_argument("--grid-width", type=int, default=3)
    parser.add_argument("--request-order", choices=("forward", "reverse", "shuffled"), default="forward")
    parser.add_argument("--count", type=int, default=6)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--scope", choices=("native-region", "lit-mca"), default="lit-mca",
                        help="Label the measured completion boundary; this harness does not measure live activation")
    parser.add_argument("--lighting", choices=("enabled", "disabled", "dense"), default="enabled")
    parser.add_argument("--mca-compression", choices=("zlib", "lz4"), default="zlib", help="MCA output codec; LZ4 validation needs Python lz4 and xxhash")
    parser.add_argument("--library-revision", help="Revision or immutable label that produced --library")
    parser.add_argument("--readiness", choices=("driver-warm", "shader-ready", "optimized-interpreter"), default="driver-warm",
                        help="Whether measurement may overlap specialization or waits for a ready/final status")
    parser.add_argument("--program-execution", choices=("auto", "interpreter", "specialized"), help="Override GPU program mode for matched diagnostics")
    parser.add_argument("--interpolation-cache", choices=("enabled", "disabled"), help="A/B diagnostic for resident interpolation-field reuse")
    parser.add_argument("--cached-density-masks", choices=("enabled", "disabled"), help="Resident-only density samplers for proven mask queries")
    parser.add_argument("--lake-point-cache", choices=("enabled", "disabled"), help="Invocation-local interpolation corner reuse for composed lake scans")
    parser.add_argument("--lake-primed-corners", choices=("enabled", "disabled"), help="Reuse interval vertices in the composed lake point cache")
    parser.add_argument("--lake-sparse-fields", choices=("enabled", "disabled"), help="GPU scratch stencils for actual composed lake probe coordinates")
    parser.add_argument("--terrain-execution", choices=("interpreter", "specialized"), help="Select terrain/climate stages independently of specialized material/cave stages")
    parser.add_argument("--specialized-interpolation", choices=("enabled", "disabled"), help="Specialize input-field prepasses independently of remaining terrain stages")
    parser.add_argument("--interpreter-dispatch", choices=("enabled", "disabled"), help="Shared compact interpreter entrypoints or independent pipelines")
    parser.add_argument("--interpreter-preload", choices=("enabled", "disabled"), help="Overlap compact density pipeline preparation with profile parsing")
    parser.add_argument("--tiered-interpreter", choices=("enabled", "disabled"), help="Vulkan-only fast initial interpreter followed by optimized replacement")
    parser.add_argument("--incremental-specialization", choices=("enabled", "disabled"), help="Publish validated specialization groups before the complete bundle")
    parser.add_argument("--interpreter-compile-threads", type=int, choices=(1, 2), help="Sequential or bounded world/cave interpreter compilation")
    parser.add_argument("--interpreter-columns", choices=("enabled", "disabled"), help="Reuse resident X/Z expressions in compact interpreted density programs")
    parser.add_argument("--aquifer-columns", choices=("enabled", "disabled"), help="Reuse invocation-local X/Z expressions in registered aquifer surface searches")
    parser.add_argument("--material-dispatch", choices=("enabled", "disabled"), help="Share the specialized material count/emit pipeline")
    parser.add_argument("--await-specialization", action="store_true", help="After measurement, await compilation and compare one regenerated region with its pre-warmup output")
    parser.add_argument("--specialization-timeout", type=float, default=180, help="Seconds to await a real compilation result after measurements")
    args = parser.parse_args()
    if args.diagnostics:
        os.environ["RETINA_PERFORMANCE_DIAGNOSTICS"] = "1"
    if args.count < 1 or args.warmups < 0 or args.grid_width < 1:
        parser.error("--count and --grid-width must be positive and --warmups cannot be negative")
    if (args.scope == "native-region") != (args.lighting == "disabled"):
        parser.error("--scope native-region requires --lighting disabled; lit-mca requires lighting enabled or dense")
    os.environ["RETINA_GPU_LIGHTING"] = "0" if args.lighting == "disabled" else "1"
    os.environ["RETINA_LIGHTING_DENSE"] = "1" if args.lighting == "dense" else "0"
    if args.interpolation_cache:
        os.environ["RETINA_INTERPOLATION_CACHE"] = "1" if args.interpolation_cache == "enabled" else "0"
    if args.cached_density_masks:
        os.environ["RETINA_CACHED_DENSITY_MASKS"] = "1" if args.cached_density_masks == "enabled" else "0"
    if args.lake_point_cache:
        os.environ["RETINA_LAKE_POINT_CACHE"] = "1" if args.lake_point_cache == "enabled" else "0"
    if args.lake_primed_corners:
        os.environ["RETINA_LAKE_PRIMED_CORNERS"] = "1" if args.lake_primed_corners == "enabled" else "0"
    if args.lake_sparse_fields:
        os.environ["RETINA_LAKE_SPARSE_FIELDS"] = "1" if args.lake_sparse_fields == "enabled" else "0"
    if args.interpreter_dispatch:
        os.environ["RETINA_INTERPRETER_DISPATCH"] = "1" if args.interpreter_dispatch == "enabled" else "0"
    if args.interpreter_preload:
        os.environ["RETINA_INTERPRETER_PRELOAD"] = "1" if args.interpreter_preload == "enabled" else "0"
    if args.tiered_interpreter:
        os.environ["RETINA_VULKAN_TIERED_INTERPRETER"] = "1" if args.tiered_interpreter == "enabled" else "0"
    if args.incremental_specialization:
        os.environ["RETINA_INCREMENTAL_SPECIALIZATION"] = "1" if args.incremental_specialization == "enabled" else "0"
    if args.interpreter_compile_threads:
        os.environ["RETINA_INTERPRETER_COMPILE_THREADS"] = str(args.interpreter_compile_threads)
    if args.interpreter_columns:
        os.environ["RETINA_INTERPRETER_COLUMNS"] = "1" if args.interpreter_columns == "enabled" else "0"
    if args.aquifer_columns:
        os.environ["RETINA_AQUIFER_COLUMNS"] = "1" if args.aquifer_columns == "enabled" else "0"
    if args.material_dispatch:
        os.environ["RETINA_MATERIAL_DISPATCH"] = "1" if args.material_dispatch == "enabled" else "0"
    if args.terrain_execution:
        os.environ["RETINA_SPECIALIZED_TERRAIN"] = "1" if args.terrain_execution == "specialized" else "0"
    if args.specialized_interpolation:
        os.environ["RETINA_SPECIALIZED_INTERPOLATION"] = "1" if args.specialized_interpolation == "enabled" else "0"
    args.out.mkdir(parents=True, exist_ok=False)
    lib = c.CDLL(str(args.library.resolve()))
    flush_caches = getattr(lib, "retina_flush_pipeline_caches", None)
    if flush_caches:
        flush_caches.argtypes = []
        flush_caches.restype = None
        atexit.register(flush_caches)
    if args.startup_debug:
        lib.retina_configure_debug.argtypes = [c.c_int]
        lib.retina_configure_debug.restype = None
        lib.retina_configure_debug(1)
    lib.retina_initialize.restype = c.c_int
    lib.retina_generate_region.argtypes = [c.POINTER(Request), c.c_char_p, c.c_uint64, c.c_int32, c.c_char_p, c.c_uint64, c.POINTER(Report)]
    lib.retina_timing_snapshot.argtypes = [c.c_uint32, c.POINTER(Snapshot)]
    lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
    def check(status):
        if status:
            buf = c.create_string_buffer(8192); lib.retina_last_error(buf, len(buf))
            raise RuntimeError(buf.value.decode())
    performance = getattr(lib, "retina_performance_snapshot", None)
    if performance:
        performance.argtypes = [c.c_uint32, c.POINTER(c.c_void_p), c.POINTER(c.c_uint64)]
        lib.retina_free_bytes.argtypes = [c.c_void_p, c.c_uint64]
        lib.retina_free_bytes.restype = None
    def performance_snapshot():
        if not performance:
            return None
        pointer, length = c.c_void_p(), c.c_uint64()
        check(performance(profile, c.byref(pointer), c.byref(length)))
        try:
            return json.loads(c.string_at(pointer, length.value))
        finally:
            lib.retina_free_bytes(pointer, length)
    startup = time.perf_counter()
    check(lib.retina_initialize())
    initialize_ms = (time.perf_counter()-startup)*1000
    loading = time.perf_counter()
    with SavedDefinition(lib, args.definition, args.dimension, args.mca_compression, args.program_execution) as definition:
        load_and_bind_ms = (time.perf_counter()-loading)*1000
        profile = definition.profile
        seed = definition.metadata["seed"]
        identity = definition.request(0, 0)
        request_context = {"min_y": identity.min_y, "height": identity.height,
                           "base_height": identity.base, "amplitude": identity.amplitude,
                           "frequency": identity.frequency, "fallback_biome": definition.fallback_biome,
                           "storage": definition.dimension["parameters"].get("storage", {"min_y": identity.min_y, "height": identity.height}),
                           "data_version": definition.metadata["data_version"]}
        fallback_biome = definition.fallback_biome.encode()
        program = getattr(lib,"retina_gpu_program_snapshot",None)
        if program:
            program.argtypes=[c.c_uint32,c.POINTER(ProgramSnapshot)]
        interpreter = getattr(lib, "retina_gpu_interpreter_snapshot", None)
        if interpreter:
            interpreter.argtypes = [c.c_uint32, c.POINTER(ProgramSnapshot)]
        def generate(item):
            name, x, z = item
            path = str((args.out / f"{name}.mca").resolve()).encode()
            req = definition.request(x*32, z*32)
            report = Report(); start = time.perf_counter()
            check(lib.retina_generate_region(c.byref(req), path, len(path), definition.metadata["data_version"],
                                             fallback_biome, len(fallback_biome), c.byref(report)))
            elapsed_ms=(time.perf_counter()-start)*1000
            status=None
            if program:
                snapshot=ProgramSnapshot();check(program(profile,c.byref(snapshot)));status=snapshot.status
            return dict(name=name, x=x, z=z, ms=elapsed_ms, generated=report.generated, program_status=status,
                        gpu_ms=report.gpu/1e6, assembly_ms=report.assembly/1e6, write_ms=report.write/1e6)
        warmups = [generate((f"warm{i}",8+i,8)) for i in range(args.warmups)]
        readiness_wait_ms = 0.0
        if args.readiness != "driver-warm":
            readiness_probe = interpreter if args.readiness == "optimized-interpreter" else program
            if not readiness_probe:
                raise RuntimeError("The loaded library does not expose program readiness diagnostics")
            ready = ProgramSnapshot(); check(readiness_probe(profile, c.byref(ready)))
            wait_start = time.perf_counter()
            while ready.status == 1 and time.perf_counter() - wait_start < args.specialization_timeout:
                time.sleep(.1); check(readiness_probe(profile, c.byref(ready)))
            readiness_wait_ms = (time.perf_counter() - wait_start) * 1000
            final_states = (2,) if args.readiness == "optimized-interpreter" else (2, 3, 4)
            if ready.status not in final_states:
                raise RuntimeError(f"GPU program did not reach a final readiness state: status={ready.status}")
        before = Snapshot(); check(lib.retina_timing_snapshot(profile, c.byref(before)))
        before_performance = performance_snapshot()
        before_program=ProgramSnapshot()
        if program:
            check(program(profile,c.byref(before_program)))
        pipeline = getattr(lib, "retina_gpu_pipeline_snapshot", None)
        before_pipeline = PipelineSnapshot()
        if pipeline:
            pipeline.argtypes = [c.POINTER(PipelineSnapshot)]
            check(pipeline(c.byref(before_pipeline)))
        # Adjacent tiles exercise shared structure halos and cold full-region terrain.
        coords = region_coordinates(args.count, args.origin_x, args.origin_z, args.grid_width,
                                    args.request_order, seed)
        batch_start_unix = time.time()
        usage_before = process_usage.snapshot()
        start = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(args.parallel) as workers:
            regions = list(workers.map(generate, coords))
        wall = time.perf_counter()-start
        usage_after = process_usage.snapshot()
        batch_end_unix = time.time()
        user_seconds = usage_after.ru_utime - usage_before.ru_utime
        system_seconds = usage_after.ru_stime - usage_before.ru_stime
        generation_peak_rss_bytes = process_usage.peak_rss_bytes(usage_after)
        after = Snapshot(); check(lib.retina_timing_snapshot(profile, c.byref(after)))
        after_performance = performance_snapshot()
        chunks = after.chunks-before.chunks
        durations = [r["ms"] for r in regions]
        try:
            revision = subprocess.run(["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
            dirty_state = subprocess.run(["git", "status", "--porcelain"], check=True, capture_output=True, text=True).stdout
            dirty = bool(dirty_state)
            diff = subprocess.run(["git", "diff", "--binary", "HEAD"], check=True, capture_output=True).stdout
            dirty_fingerprint = hashlib.sha256(diff + dirty_state.encode()).hexdigest() if dirty else None
        except (OSError, subprocess.CalledProcessError):
            revision, dirty, dirty_fingerprint = None, None, None
        data = dict(schema=RESULT_SCHEMA, schema_version=RESULT_SCHEMA_VERSION,
                    mca_compression=args.mca_compression,
                    benchmark_scope=args.scope, lighting=args.lighting,
                    readiness=args.readiness, readiness_wait_ms=readiness_wait_ms,
                    build={"harness_revision": revision, "harness_dirty": dirty,
                           "harness_dirty_fingerprint": dirty_fingerprint,
                           "library_revision": args.library_revision, "library": str(args.library.resolve()),
                           "library_sha256": hashlib.sha256(args.library.read_bytes()).hexdigest()},
                    host={"system": platform.system(), "release": platform.release(), "machine": platform.machine(),
                          "python": platform.python_version()},
                    library=str(args.library), parallel=args.parallel, seed=seed, dimension=args.dimension, request=request_context,
                    coordinates={"origin_x": args.origin_x, "origin_z": args.origin_z, "grid_width": args.grid_width},
                    request_order=args.request_order,
                    regions=regions, program_execution=args.program_execution, interpolation_cache=args.interpolation_cache, density_composition=definition.dimension["profile_metadata"].get("registry_program", {}).get("density_composition"), cached_density_masks=args.cached_density_masks, terrain_execution=args.terrain_execution, interpreter_dispatch=args.interpreter_dispatch, interpreter_preload=args.interpreter_preload,
                    lake_point_cache=args.lake_point_cache,
                    lake_primed_corners=args.lake_primed_corners,
                    lake_sparse_fields=args.lake_sparse_fields or os.environ.get("RETINA_LAKE_SPARSE_FIELDS"),
                    interpreter_columns=args.interpreter_columns or os.environ.get("RETINA_INTERPRETER_COLUMNS"),
                    interpreter_compile_threads=args.interpreter_compile_threads or os.environ.get("RETINA_INTERPRETER_COMPILE_THREADS", "1"),
                    tiered_interpreter=args.tiered_interpreter or os.environ.get("RETINA_VULKAN_TIERED_INTERPRETER", "0"),
                    incremental_specialization=args.incremental_specialization or os.environ.get("RETINA_INCREMENTAL_SPECIALIZATION", "0"),
                    aquifer_columns=args.aquifer_columns or os.environ.get("RETINA_AQUIFER_COLUMNS"),
                    material_dispatch=args.material_dispatch or os.environ.get("RETINA_MATERIAL_DISPATCH"),
                    specialized_interpolation=args.specialized_interpolation or os.environ.get("RETINA_SPECIALIZED_INTERPOLATION"),
                    total_ms=wall*1000, chunks_per_second=chunks/wall, median_ms=statistics.median(durations),
                    measured_process_usage={
                        "schema_version": 1,
                        "start_unix": batch_start_unix, "end_unix": batch_end_unix,
                        "user_ms": user_seconds * 1000, "system_ms": system_seconds * 1000,
                        "total_ms": (user_seconds + system_seconds) * 1000,
                        "cpu_ms_per_region": (user_seconds + system_seconds) * 1000 / len(regions),
                        "average_scheduled_cores": (user_seconds + system_seconds) / wall,
                        "counter_source": process_usage.SOURCE,
                        "voluntary_context_switches": process_usage.difference(usage_after, usage_before, "ru_nvcsw"),
                        "involuntary_context_switches": process_usage.difference(usage_after, usage_before, "ru_nivcsw"),
                        "minor_faults": process_usage.difference(usage_after, usage_before, "ru_minflt"),
                        "major_faults": process_usage.difference(usage_after, usage_before, "ru_majflt"),
                        "scope": "RUSAGE_SELF deltas for the whole process and all its threads around the measured batch, including executor creation/join and host driver work. Excludes startup, warmups, readiness wait and later snapshot/hash/NBT validation work. GPU device execution and other processes are not CPU time here. Context-switch/fault counts are platform-reported observations, not wait durations. Average scheduled cores is CPU seconds divided by elapsed batch seconds, not worker occupancy or an elapsed-time share.",
                        "clock_scope": "Unix timestamps bracket the batch and CPU samples for observation alignment only; wall-clock adjustments may affect them. Latency uses the existing monotonic perf_counter interval. No per-request CPU attribution is implied.",
                    },
                    p95_region_ms=percentile(durations, 95), maximum_region_ms=max(durations),
                    average_region_ms=statistics.mean(durations),
                    startup=dict(initialize_ms=initialize_ms, load_and_bind_ms=load_and_bind_ms, warmups=warmups),
                    definition=str(args.definition.resolve()),
                    definition_sha256=hashlib.sha256(args.definition.read_bytes()).hexdigest(),
                    region_file_bytes=sum((args.out/f"{name}.mca").stat().st_size for name,_,_ in coords),
                    generation_peak_rss_bytes=generation_peak_rss_bytes,
                    memory_peak_scope={
                        "generation_peak_rss_bytes": "Process lifetime through measured generation, including startup and warmups; excludes later snapshot processing and output validation",
                        "peak_rss_bytes": "Process lifetime through output hashing, optional NBT comparison and optional specialization regeneration; retained legacy field",
                        "note": "High-water marks, not current RSS or isolated phase allocations; neither field proves sustained memory stability",
                    },
                    gpu_timestamps=bool(after.flags & 1),
                    timing_version=after.version, columns_include_lakes=after.version < 7,
                    stage_ms_chunk={name:(after.nanos[i]-before.nanos[i])/max(chunks,1)/1e6 for i,name in enumerate(STAGES)})
        if after_performance:
            data["performance_diagnostics"] = performance_interval(before_performance, after_performance)
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
        if interpreter:
            optimized = ProgramSnapshot(); check(interpreter(profile, c.byref(optimized)))
            data["interpreter_optimization"] = {name: getattr(optimized, name) for name, _ in ProgramSnapshot._fields_}
        groups = getattr(lib, "retina_gpu_specialization_snapshot", None)
        if groups:
            groups.argtypes = [c.c_uint32, c.POINTER(SpecializationSnapshot)]
            snapshot = SpecializationSnapshot(); check(groups(profile, c.byref(snapshot)))
            data["specialization_groups"] = {name: getattr(snapshot, name) for name, _ in SpecializationSnapshot._fields_}
        names = [name for name, _, _ in coords]
        data["corpus"] = corpus_manifest(args.out, names)
        if args.compare:
            data["comparison"] = compare_corpora(args.compare, args.out, names)
            data["identical_nbt_chunks"] = data["comparison"]["identical_nbt_chunks"]
        if args.await_specialization:
            if not program: raise RuntimeError("The loaded library does not expose program diagnostics")
            awaited=ProgramSnapshot();check(program(profile,c.byref(awaited)))
            status_before_wait=awaited.status
            wait_start=time.perf_counter()
            print(f"Awaiting specialization after measuring {len(regions)} regions; status={awaited.status}",flush=True)
            while awaited.status==1 and time.perf_counter()-wait_start<args.specialization_timeout:
                time.sleep(.1);check(program(profile,c.byref(awaited)))
            if awaited.status not in (2,4): raise RuntimeError(f"Specialization did not become ready: status={awaited.status}")
            wait_after_measurement_ms=(time.perf_counter()-wait_start)*1000
            _,x,z=coords[0]
            regenerated=generate(("compiled_check",x,z))
            old, new = mca_records(args.out/f"{coords[0][0]}.mca"), mca_records(args.out/"compiled_check.mca")
            if old != new: raise AssertionError("Compilation changed region NBT")
            data["specialization_warmup_check"]={"status_before_wait":status_before_wait,"wait_after_measurement_ms":wait_after_measurement_ms,
                "compile_ms":awaited.compile_nanos/1e6,"identical_nbt_chunks":1024,"regenerated_region":regenerated}
        data["peak_rss_bytes"]=process_usage.peak_rss_bytes(process_usage.snapshot())
        write_json(args.out/"measurements.json", data)
        print(json.dumps(data, indent=2))
if __name__ == "__main__": main()
