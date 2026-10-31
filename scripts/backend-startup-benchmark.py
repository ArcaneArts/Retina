#!/usr/bin/env python3
"""Run matched headless fresh/repeated processes with a private Retina cache."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from startup_report import summarize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", required=True, type=Path)
    parser.add_argument("--definition", required=True, type=Path)
    parser.add_argument("--dimension", default="minecraft:overworld")
    parser.add_argument("--backend", required=True, choices=("vulkan", "dx12", "metal"))
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--count", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=600)
    args = parser.parse_args()
    if args.repeats < 2 or args.count < 1 or args.timeout <= 0:
        parser.error("repeats must be >=2; count and timeout must be positive")
    args.out.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, RETINA_GPU_BACKEND=args.backend,
               RETINA_PIPELINE_CACHE_DIR=str((args.out/"pipeline-cache").resolve()))
    reports = []
    for index in range(args.repeats):
        destination = args.out/f"process-{index:02d}"
        log = args.out/f"process-{index:02d}.log"
        command = [sys.executable, str(Path(__file__).with_name("native-region-benchmark.py")),
                   "--library", str(args.library.resolve()), "--definition", str(args.definition.resolve()),
                   "--dimension", args.dimension, "--out", str(destination.resolve()),
                   "--count", str(args.count), "--warmups", "0", "--startup-debug",
                   "--scope", "native-region", "--lighting", "disabled"]
        if index:
            command += ["--compare", str((args.out/"process-00").resolve())]
        began = time.monotonic()
        with log.open("w") as stream:
            try:
                run = subprocess.run(command, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                     timeout=args.timeout)
                outcome = {"exit_code": run.returncode}
            except subprocess.TimeoutExpired:
                outcome = {"timeout_seconds": args.timeout}
        report = {"process": index, "command": command, "wall_ms": (time.monotonic()-began)*1000,
                  "application_cache": "new private directory" if index == 0 else "retained from previous process",
                  "driver_cache": "uncontrolled", **outcome,
                  "startup": summarize(log.read_text(errors="replace"))}
        measurement = destination/"measurements.json"
        if measurement.exists():
            report["measurement"] = json.loads(measurement.read_text())
        reports.append(report)
        (args.out/"comparison.json").write_text(json.dumps({"schema_version": 1, "backend": args.backend,
            "runs": reports, "scope": "Diagnostic startup runs; output hashing and NBT comparison are included in process wall time. Not a sustained-throughput benchmark. Driver cache state is uncontrolled."}, indent=2)+"\n")
        if outcome.get("exit_code") != 0:
            raise SystemExit(f"Process {index} did not complete; evidence saved to {log}")


if __name__ == "__main__":
    main()
