#!/usr/bin/env python3
"""Small complete-region campaigns; hash/compare each batch, then remove its disposable MCA files.

Each trial is an independently warmed process. Its generation timing excludes hashing
and file removal. Repeated-process startup is reported, never folded into generation.
Existing benchmark evidence and caller-supplied reference directories are never removed.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys

from benchmark_support import percentile, write_json


def discard_generated_regions(directory: Path, count: int, warmups: int) -> dict:
    """Only named outputs in this newly owned trial; retain reports, logs and unexpected files."""
    removed = []
    for name in [*(str(i) for i in range(count)), *(f"warm{i}" for i in range(warmups))]:
        path = directory / f"{name}.mca"
        size = path.stat().st_size
        path.unlink()
        removed.append({"file": path.name, "bytes": size})
    receipt = {"scope": "disposable regions created by this trial only; after successful hashing/comparison",
               "files": removed, "bytes": sum(row["bytes"] for row in removed)}
    write_json(directory / "region-disposal.json", receipt)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--library-revision")
    parser.add_argument("--baseline-library", type=Path)
    parser.add_argument("--baseline-revision")
    parser.add_argument("--definition", type=Path, required=True)
    parser.add_argument("--dimension", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--category", required=True, choices=("void", "outer-islands", "dense-or-structures",
                                                              "overworld-control", "nether-control"))
    parser.add_argument("--readiness", choices=("driver-warm", "shader-ready"), default="driver-warm")
    parser.add_argument("--origin-x", type=int, default=-1)
    parser.add_argument("--origin-z", type=int, default=-1)
    parser.add_argument("--grid-width", type=int, default=2)
    parser.add_argument("--regions", type=int, default=4)
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--trials", type=int, help="Defaults to 3 independent timing trials, or 4 for ABBA")
    parser.add_argument("--callers", type=int, choices=(1, 2, 4, 8), default=1)
    parser.add_argument("--workers", type=int, choices=range(1, 17), default=16)
    args = parser.parse_args()
    trials = args.trials if args.trials is not None else (4 if args.baseline_library else 3)
    if min(args.regions, trials, args.grid_width, args.workers) < 1 or args.warmups < 0:
        parser.error("regions, trials, grid width and workers must be positive; warmups cannot be negative")
    args.out.mkdir(parents=True, exist_ok=False)
    runner = Path(__file__).with_name("native-region-benchmark.py")
    environment = {**os.environ, "RETINA_REGION_THREADS": str(args.workers), "RAYON_NUM_THREADS": str(args.workers)}
    order = (["baseline", "candidate", "candidate", "baseline"] * ((trials + 3) // 4))[:trials] \
        if args.baseline_library else ["candidate"] * trials
    first_reference = None
    reference_manifest = None
    rows = []
    for index, variant in enumerate(order):
        destination = args.out / f"{index:02d}-{variant}"
        library = args.baseline_library if variant == "baseline" else args.library
        revision = args.baseline_revision if variant == "baseline" else args.library_revision
        command = [sys.executable, str(runner), "--library", str(library.resolve()),
                   "--definition", str(args.definition.resolve()), "--dimension", args.dimension,
                   "--out", str(destination.resolve()),
                   "--count", str(args.regions), "--warmups", str(args.warmups),
                   "--parallel", str(args.callers),
                   "--origin-x", str(args.origin_x), "--origin-z", str(args.origin_z),
                   "--grid-width", str(args.grid_width), "--scope", "lit-mca", "--lighting", "enabled",
                   "--readiness", args.readiness]
        if revision:
            command.extend(["--library-revision", revision])
        if first_reference is not None:
            command.extend(["--compare", str(first_reference.resolve())])
        write_json(args.out / f"{index:02d}-command.json", {"argv": command,
                   "environment": {key: environment[key] for key in ("RETINA_REGION_THREADS", "RAYON_NUM_THREADS")}})
        with (args.out / f"{index:02d}-stdout.log").open("w") as log:
            subprocess.run(command, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
        measurement = json.loads((destination / "measurements.json").read_text())
        if any(row["generated"] != 1024 for row in measurement["regions"]) \
                or measurement["corpus"]["records"] != args.regions * 1024:
            raise AssertionError("Benchmark did not actually generate and hash complete regions; outputs retained")
        manifest = measurement["corpus"]
        if reference_manifest is None:
            reference_manifest = manifest
        elif manifest != reference_manifest:
            raise AssertionError("Full decompressed chunk-NBT manifest changed; outputs retained")
        rows.append({"trial": index, "variant": variant, "path": str(destination.resolve()),
                     "measurements": measurement})
        # ABBA retains only its first small corpus until all direct comparisons finish.
        # Timing-only campaigns retain compact manifests between trials.
        if args.baseline_library and first_reference is None:
            first_reference = destination
        else:
            discard_generated_regions(destination, args.regions, args.warmups)
    if first_reference is not None:
        discard_generated_regions(first_reference, args.regions, args.warmups)
    summary = {}
    for variant in sorted(set(order)):
        selected = [row["measurements"] for row in rows if row["variant"] == variant]
        durations = [region["ms"] for trial in selected for region in trial["regions"]]
        summary[variant] = {"samples": len(durations), "median_ms": statistics.median(durations),
                            "p95_ms": percentile(durations, 95), "maximum_ms": max(durations),
                            "chunks_per_second": sum(trial["corpus"]["records"] for trial in selected)
                            / (sum(trial["total_ms"] for trial in selected) / 1000)}
    result = {"schema": "retina.end.bounded-region-campaign.v1", "category": args.category,
              "category_scope": "caller-selected coordinates; category must be established by terrain/feature coverage",
              "scope": "independently warmed full-region trials; startup, hashing and disposal outside generation timing",
              "native_workers": args.workers, "callers": args.callers,
              "maximum_active_callers": min(args.callers, args.regions), "order": order,
              "regions_per_trial": args.regions, "summary": summary, "trials": rows,
              "correctness": "all measured chunk NBT hashed; ABBA also compares decompressed bytes to its first corpus",
              "storage": "successful disposable MCA files removed; logs, manifests, commands and failures retained"}
    write_json(args.out / "campaign.json", result)
    print(json.dumps({"category": args.category, "summary": summary, "path": str(args.out)}, indent=2))


if __name__ == "__main__":
    main()
