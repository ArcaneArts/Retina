#!/usr/bin/env python3
"""Sweep native caller/assembly/compiler budgets using matched fresh processes.

This controls native work only. It does not simulate Minecraft rendering or Java
preparation, and does not change production defaults or GPU queue depth.
"""
import argparse
import itertools
import json
import os
from pathlib import Path
import random
import statistics
import subprocess
import sys
import time

from benchmark_support import write_json
from startup_report import summarize


def configurations(regions, assembly, compiler, repeats):
    cells = list(itertools.product(regions, assembly, compiler))
    random.Random(0x524554494E41).shuffle(cells)
    # Alternating direction reduces assigning a monotonic load trend to a cell.
    return [(repeat, *cell) for repeat in range(repeats)
            for cell in (cells if repeat % 2 == 0 else reversed(cells))]


def summarize_cells(rows):
    grouped = {}
    for row in rows:
        if row.get("exit_code") == 0 and "measurement" in row:
            grouped.setdefault((row["regions"], row["assembly_threads"], row["compiler_threads"]), []).append(row["measurement"])
    return [{"regions":key[0], "assembly_threads":key[1], "compiler_threads":key[2], "trials":len(values),
             "median_chunks_per_second":statistics.median(v["chunks_per_second"] for v in values),
             "median_region_ms":statistics.median(v["median_ms"] for v in values),
             "median_p95_region_ms":statistics.median(v["p95_region_ms"] for v in values),
             "median_scheduled_cores":statistics.median(v["measured_process_usage"]["average_scheduled_cores"] for v in values)}
            for key, values in sorted(grouped.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--definition", type=Path, required=True)
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--backend", choices=("metal", "vulkan", "dx12"), required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--region-concurrency", nargs="+", type=int, choices=(1,2,4,8), default=[2,4,8])
    parser.add_argument("--assembly-threads", nargs="+", type=int, choices=range(1,17), default=[1,2,4])
    parser.add_argument("--compiler-threads", nargs="+", type=int, choices=(1,2), default=[1,2])
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--readiness", choices=("driver-warm","shader-ready"), default="shader-ready")
    parser.add_argument("--lighting", choices=("disabled","enabled","dense"), default="disabled")
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--diagnostics", action="store_true", help="Stage/debug summaries; separate these from headline timing sweeps")
    args = parser.parse_args()
    if args.repeats < 1 or args.count < 1 or args.warmups < 1 or args.timeout <= 0:
        parser.error("positive repeats, count, warmups and timeout required")
    args.out.mkdir(parents=True, exist_ok=False)
    runner = Path(__file__).with_name("native-region-benchmark.py").resolve()
    plan = configurations(args.region_concurrency, args.assembly_threads, args.compiler_threads, args.repeats)
    rows, reference = [], None
    metadata = {"schema":"retina.generation-concurrency", "schema_version":1,
                "backend":args.backend, "dimension":args.dimension, "readiness":args.readiness,
                "diagnostic":args.diagnostics, "logical_processors":os.cpu_count(),
                "scope":"Fresh native processes, fixed definition/seed/coordinates. Hash/NBT checks are outside measured batches. Does not model Java or rendering; driver cache uncontrolled. No production budgets or GPU depth changed."}
    for index, (repeat, regions, assembly, compiler) in enumerate(plan):
        destination = args.out/f"{index:02d}-r{regions}-a{assembly}-c{compiler}"
        command = [sys.executable, str(runner), "--library", str(args.library.resolve()),
                   "--definition", str(args.definition.resolve()), "--dimension", args.dimension,
                   "--out", str(destination.resolve()), "--parallel", str(regions),
                   "--assembly-threads", str(assembly), "--interpreter-compile-threads", str(compiler),
                   "--count", str(args.count), "--warmups", str(args.warmups), "--readiness", args.readiness,
                   "--scope", "native-region" if args.lighting=="disabled" else "lit-mca",
                   "--lighting", args.lighting]
        if args.diagnostics: command += ["--startup-debug", "--diagnostics"]
        if reference is not None: command += ["--compare", str(reference)]
        environment = dict(os.environ, RETINA_GPU_BACKEND=args.backend,
                           RETINA_REGION_THREADS=str(assembly), RETINA_INTERPRETER_COMPILE_THREADS=str(compiler),
                           RETINA_PIPELINE_CACHE_DIR=str((args.out/"pipeline-cache").resolve()))
        log = args.out/f"{index:02d}.log"
        started = time.monotonic()
        with log.open("w") as stream:
            try:
                result = subprocess.run(command, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=args.timeout)
                outcome = {"exit_code":result.returncode}
            except subprocess.TimeoutExpired:
                outcome = {"timeout_seconds":args.timeout}
        row = {"repeat":repeat, "regions":regions, "assembly_threads":assembly, "compiler_threads":compiler,
               "command":command, "process_wall_ms":(time.monotonic()-started)*1000, "log":str(log), **outcome}
        measurement = destination/"measurements.json"
        if measurement.exists(): row["measurement"] = json.loads(measurement.read_text())
        if args.diagnostics: row["startup"] = summarize(log.read_text(errors="replace"))
        rows.append(row)
        write_json(args.out/"comparison.json", {**metadata, "runs":rows, "summary":summarize_cells(rows)})
        if outcome.get("exit_code") != 0:
            raise SystemExit(f"Cell {index} did not complete; evidence: {log}")
        reference = reference or destination.resolve()
        measured = row["measurement"]
        print(json.dumps({"completed":index+1,"total":len(plan),"regions":regions,"assembly":assembly,"compiler":compiler,
                          "chunks_per_second":measured["chunks_per_second"],"p95_ms":measured["p95_region_ms"]}),flush=True)


if __name__ == "__main__":
    main()
