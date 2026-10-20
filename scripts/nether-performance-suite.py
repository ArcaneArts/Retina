#!/usr/bin/env python3
"""Run complete-region benchmarks serially and retain exact-output evidence.

Profiles are actual loaded Minecraft exports, supplied separately from Git.
Large MCA outputs and retained libraries belong in an external evidence folder.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time

from benchmark_support import compare_corpora, corpus_coverage, write_json


def observe_processes(stream, benchmark_pid):
    """Record actual competing activity; never use process names as a run gate."""
    snapshot = subprocess.run(["ps", "-axo", "pid,pcpu,comm"],
                              text=True, capture_output=True)
    rows = []
    for line in snapshot.stdout.splitlines():
        fields = line.split(None, 2)
        if len(fields) != 3:
            continue
        try:
            pid, cpu = int(fields[0]), float(fields[1])
        except ValueError:
            continue
        if cpu >= 10 or "benchmark" in fields[2] or pid == benchmark_pid:
            rows.append({"pid": pid, "cpu_percent": cpu, "command": fields[2],
                         "role": "benchmark" if pid == benchmark_pid else
                         "suite" if pid == os.getpid() else "external"})
    stream.write(json.dumps({"time": time.time(), "processes": rows,
                             "ps_returncode": snapshot.returncode}) + "\n")
    stream.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--library-revision", required=True)
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--manifest", type=Path,
                        default=Path(__file__).resolve().parents[1] / "qa/nether-performance-corpus.json")
    parser.add_argument("--case", action="append", help="Select named cases; default is the complete corpus")
    parser.add_argument("--compare", type=Path, action="append", default=[],
                        help="Prior suite output; repeat to check original and preceding cycle")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--parallel", type=int, choices=(1, 2, 4, 8), default=1)
    parser.add_argument("--request-order", choices=("forward", "reverse", "shuffled"), default="forward")
    parser.add_argument("--readiness", choices=("driver-warm", "shader-ready"), default="shader-ready")
    parser.add_argument("--diagnostics", action="store_true")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    cases = [c for c in manifest["cases"] if args.case is None or c["name"] in args.case]
    if args.case and set(args.case) - {c["name"] for c in cases}:
        parser.error("unknown case selection")
    if args.repeat < 1 or args.workers < 1:
        parser.error("repeat and workers must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    env = os.environ | {
        "RETINA_REGION_THREADS": str(args.workers),
        "RETINA_PERFORMANCE_DIAGNOSTICS": "1" if args.diagnostics else "0",
    }
    summary = {
        "schema": "retina.nether-performance-suite", "version": 1,
        "manifest": manifest, "host": platform.platform(),
        "library_sha256": hashlib.sha256(args.library.read_bytes()).hexdigest(),
        "library_revision": args.library_revision,
        "workers": args.workers, "parallel": args.parallel,
        "request_order": args.request_order,
        "readiness": args.readiness, "diagnostics": args.diagnostics,
        "runs": [],
    }
    write_json(args.out / "suite.json", summary)
    for repeat in range(args.repeat):
        for case in cases:
            label = f"{case['name']}-run{repeat}"
            destination = args.out / label
            command = [
                sys.executable, str(Path(__file__).with_name("native-region-benchmark.py")),
                "--library", str(args.library.resolve()),
                "--library-revision", args.library_revision,
                "--profile", str((args.profiles / case["profile"]).resolve()),
                "--out", str(destination.resolve()),
                "--seed", str(case["seed"]), "--origin-x", str(case["origin"][0]),
                "--origin-z", str(case["origin"][1]),
                "--grid-width", str(case["grid_width"]),
                "--count", str(manifest["measured_regions"]),
                "--warmups", str(manifest["warmup_regions"]),
                "--parallel", str(args.parallel), "--readiness", args.readiness,
                "--request-order", args.request_order,
                "--scope", "lit-mca", "--lighting", "enabled", "--mca-compression", "zlib",
            ]
            if args.diagnostics:
                command.append("--diagnostics")
            run = {"label": label, "command": command, "start": time.time()}
            summary["runs"].append(run)
            # Evidence, not an admission gate: competing workloads must be
            # considered when interpreting results, not guessed from lock files.
            processes = subprocess.run(["ps", "-axo", "pid,pcpu,comm,args"],
                                       text=True, capture_output=True)
            (args.out / f"{label}-processes.txt").write_text(processes.stdout)
            print(f"START {label}", flush=True)
            write_json(args.out / "suite.json", summary)
            observations = args.out / f"{label}-process-observations.jsonl"
            with (args.out / f"{label}.log").open("w") as log, observations.open("w") as samples:
                process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
                run.update(pid=process.pid, process_observations=observations.name)
                write_json(args.out / "suite.json", summary)
                while True:
                    observe_processes(samples, process.pid)
                    try:
                        returncode = process.wait(timeout=10)
                        break
                    except subprocess.TimeoutExpired:
                        pass
            run.update(end=time.time(), returncode=returncode)
            write_json(args.out / "suite.json", summary)
            if returncode:
                raise subprocess.CalledProcessError(returncode, command)
            measurements = json.loads((destination / "measurements.json").read_text())
            names = [r["name"] for r in measurements["regions"]]
            run["measurements"] = measurements
            run["comparisons"] = []
            try:
                for baseline in args.compare:
                    original = baseline / f"{case['name']}-run0"
                    run["comparisons"].append({
                        "baseline": str(original.resolve()),
                        **compare_corpora(original, destination, names),
                    })
                if repeat:
                    run["repeat_comparison"] = compare_corpora(
                        args.out / f"{case['name']}-run0", destination, names)
                run["coverage"] = corpus_coverage(destination, names)
            except Exception as error:
                run["validation_error"] = str(error)
                write_json(args.out / "suite.json", summary)
                raise
            write_json(args.out / "suite.json", summary)
            print(f"DONE {label}: median={measurements['median_ms']:.3f} ms "
                  f"p95={measurements['p95_region_ms']:.3f} ms "
                  f"chunks/s={measurements['chunks_per_second']:.3f}", flush=True)


if __name__ == "__main__":
    main()
