#!/usr/bin/env python3
"""Run alternating Retina baseline/candidate region trials and summarize them."""
from __future__ import annotations

import argparse
import json
import random
import statistics
import subprocess
import sys
from pathlib import Path

from benchmark_support import percentile, write_json


def bootstrap_change(baseline, candidate, samples=10_000):
    randomizer = random.Random(0x524554494E41)
    changes = []
    for _ in range(samples):
        old = statistics.median(randomizer.choice(baseline) for _ in baseline)
        new = statistics.median(randomizer.choice(candidate) for _ in candidate)
        changes.append(100.0 * (new / old - 1.0))
    return {"method": "independent median bootstrap", "samples": samples,
            "low_percent": percentile(changes, 2.5), "high_percent": percentile(changes, 97.5)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-library", type=Path, required=True)
    parser.add_argument("--candidate-library", type=Path, required=True)
    parser.add_argument("--baseline-revision")
    parser.add_argument("--candidate-revision")
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--trials", type=int, default=4)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--warmups", type=int, default=5)
    parser.add_argument("--parallel", type=int, choices=(1, 2), default=1)
    parser.add_argument("--seed", type=int, default=123456789)
    parser.add_argument("--origin-x", type=int, default=-1)
    parser.add_argument("--origin-z", type=int, default=-1)
    parser.add_argument("--grid-width", type=int, default=3)
    parser.add_argument("--lighting", choices=("enabled", "disabled", "dense"), default="enabled")
    parser.add_argument("--readiness", choices=("driver-warm", "shader-ready"), default="shader-ready")
    args = parser.parse_args()
    if args.trials < 2:
        parser.error("--trials must be at least two")
    args.out.mkdir(parents=True, exist_ok=False)
    scope = "native-region" if args.lighting == "disabled" else "lit-mca"
    # A/B/B/A repeated avoids assigning a monotonic thermal/load trend to one build.
    order = (["baseline", "candidate", "candidate", "baseline"] * ((args.trials + 3) // 4))[:args.trials]
    results = []
    first_baseline = None
    runner = Path(__file__).with_name("native-region-benchmark.py")
    for index, variant in enumerate(order):
        destination = args.out / f"{index:02d}-{variant}"
        library = args.baseline_library if variant == "baseline" else args.candidate_library
        command = [sys.executable, str(runner), "--library", str(library), "--profile", str(args.profile),
                   "--out", str(destination), "--count", str(args.count), "--warmups", str(args.warmups),
                   "--parallel", str(args.parallel), "--seed", str(args.seed), "--scope", scope,
                   "--lighting", args.lighting, "--readiness", args.readiness,
                   "--origin-x", str(args.origin_x), "--origin-z", str(args.origin_z),
                   "--grid-width", str(args.grid_width)]
        revision = args.baseline_revision if variant == "baseline" else args.candidate_revision
        if revision:
            command.extend(["--library-revision", revision])
        if variant == "candidate" and first_baseline is not None:
            command.extend(["--compare", str(first_baseline)])
        subprocess.run(command, check=True)
        if variant == "baseline" and first_baseline is None:
            first_baseline = destination
        measurement = json.loads((destination / "measurements.json").read_text())
        results.append({"trial": index, "variant": variant, "path": str(destination),
                        "chunks_per_second": measurement["chunks_per_second"],
                        "median_region_ms": measurement["median_ms"],
                        "p95_region_ms": measurement["p95_region_ms"],
                        "peak_rss_bytes": measurement["peak_rss_bytes"],
                        "region_file_bytes": measurement["region_file_bytes"]})
    grouped = {variant: [item for item in results if item["variant"] == variant]
               for variant in ("baseline", "candidate")}
    summary = {}
    for variant, items in grouped.items():
        rates = [item["chunks_per_second"] for item in items]
        summary[variant] = {"trials": len(items), "median_chunks_per_second": percentile(rates, 50),
                            "minimum_chunks_per_second": min(rates), "maximum_chunks_per_second": max(rates)}
    baseline = summary["baseline"]["median_chunks_per_second"]
    candidate = summary["candidate"]["median_chunks_per_second"]
    baseline_rates = [item["chunks_per_second"] for item in grouped["baseline"]]
    candidate_rates = [item["chunks_per_second"] for item in grouped["candidate"]]
    comparison = {"schema": "retina.native-region-benchmark-ab", "schema_version": 1,
                  "scope": scope, "lighting": args.lighting, "readiness": args.readiness,
                  "order": order, "results": results,
                  "summary": summary, "median_throughput_change_percent": 100.0 * (candidate / baseline - 1.0),
                  "throughput_change_confidence_95": bootstrap_change(baseline_rates, candidate_rates)}
    write_json(args.out / "comparison.json", comparison)
    print(json.dumps(comparison, indent=2))


if __name__ == "__main__":
    main()
