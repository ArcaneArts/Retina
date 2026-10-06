#!/usr/bin/env python3
"""Compare material sharing or input-field specialization with fresh GPU identities.

Run sequentially against an exported profile and a built release test executable.
Use private output paths. Driver caches and live world saves are left intact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import uuid
import zlib


def records(path):
    data = path.read_bytes()
    for slot in range(1024):
        location = int.from_bytes(data[slot * 4:slot * 4 + 4], "big")
        offset = (location >> 8) * 4096
        size = int.from_bytes(data[offset:offset + 4], "big")
        assert location and data[offset + 4] == 2, (path, slot)
        yield zlib.decompress(data[offset + 5:offset + 4 + size])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-binary", type=Path, required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=20, help="Measured regions after warmups")
    parser.add_argument("--warmups", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=2, help="Reverse order on alternate repeats")
    parser.add_argument("--comparison", choices=("material", "interpolation"), default="material")
    parser.add_argument("--order", choices=("separate-first", "shared-first"), default="separate-first")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    total = args.count + args.warmups
    results = []
    reference_phase, candidate_phase = ("separate", "shared") if args.comparison == "material" else ("interpreted", "specialized")
    for repeat in range(args.repeats):
        phases = (reference_phase, candidate_phase) if (args.order == "separate-first") != bool(repeat % 2) else (candidate_phase, reference_phase)
        for phase in phases:
            name = f"{phase}-r{repeat + 1}"
            destination = args.out / name
            log = args.out / f"{name}.log"
            tag = f"retina-{args.comparison}-{uuid.uuid4().hex}"
            env = dict(os.environ, RETINA_COMPILE_MODE="specialized", RETINA_COMPILE_PROBE="1",
                       RETINA_SPECIALIZED_PIPELINE_TAG=tag, RETINA_INTERPRETER_DISPATCH="1",
                       RETINA_MATERIAL_DISPATCH="1" if args.comparison == "interpolation" or phase == "shared" else "0",
                       RETINA_SPECIALIZED_INTERPOLATION="1" if args.comparison == "interpolation" and phase == candidate_phase else "0",
                       RETINA_SPECIALIZED_TERRAIN="0", RETINA_DENSITY_COMPOSITION="0",
                       RETINA_AQUIFER_COLUMNS="0", RETINA_COMPILE_REGIONS=str(total),
                       RETINA_PROGRAM_PARITY_PROFILE=str(args.profile.resolve()),
                       RETINA_COMPILE_OUT=str(destination.resolve()))
            print(f"Starting {name}", flush=True)
            with log.open("w") as stream:
                subprocess.run(["/usr/bin/time", "-l" if platform.system() == "Darwin" else "-v",
                                "nice", "-n", "10", str(args.test_binary.resolve()),
                                "tests::actual_interpreter_pipeline_compile", "--exact", "--ignored",
                                "--nocapture", "--test-threads=1"], env=env, stdout=stream,
                               stderr=subprocess.STDOUT, check=True)
            text = log.read_text()
            measured = json.loads((destination / "measurements.json").read_text())
            assert len(measured["regions"]) == total and measured["program"]["status"] == 4, measured["program"]
            probes = [json.loads(line.removeprefix("RETINA_COMPILE "))
                      for line in text.splitlines() if line.startswith("RETINA_COMPILE ")]
            pipelines = [call for call in probes if call["specialized"] and call["phase"] == "pipeline"]
            assert any(call["entry"] == "retina_material_runs" for call in pipelines) == (args.comparison == "interpolation" or phase == "shared")
            assert any(call["entry"].startswith("interpolation_nodes_") for call in pipelines) == (args.comparison == "interpolation" and phase == candidate_phase)
            peak = re.search(r"(\d+)\s+maximum resident set size", text)
            if platform.system() != "Darwin":
                peak = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", text)
            warm_ms = sum(region["ms"] for region in measured["regions"][args.warmups:])
            result = dict(name=name, tag=tag, compile_ms=measured["program"]["compile_ms"],
                          material_compile_ms=sum(call["ms"] for call in pipelines
                              if call["entry"] in ("material_counts", "material_emit", "retina_material_runs")),
                          interpolation_compile_ms=sum(call["ms"] for call in pipelines if call["entry"].startswith("interpolation_nodes_")),
                          first_region_ms=measured["regions"][0]["ms"],
                          average_region_ms=warm_ms / args.count,
                          chunks_per_second=args.count * 1024 * 1000 / warm_ms,
                          initialize_ms=measured["initialize_ms"], registration_ms=measured["registration_ms"],
                          peak_rss_bytes=int(peak.group(1)) * (1 if platform.system() == "Darwin" else 1024) if peak else None,
                          file_bytes=sum((destination / f"{i}.mca").stat().st_size for i in range(args.warmups, total)))
            results.append(result)
            (destination / "compile.json").write_text(json.dumps(probes, indent=2) + "\n")
            print(json.dumps(result), flush=True)
    reference = args.out / f"{reference_phase}-r1"
    for result in results:
        destination = args.out / result["name"]
        if destination == reference:
            continue
        matched = 0
        for index in range(total):
            for slot, (a, b) in enumerate(zip(records(reference / f"{index}.mca"),
                                            records(destination / f"{index}.mca"), strict=True)):
                assert a == b, (result["name"], index, slot)
                matched += 1
        result["identical_nbt_chunks"] = matched
    report = dict(comparison=args.comparison, profile=str(args.profile.resolve()), profile_sha256=hashlib.sha256(args.profile.read_bytes()).hexdigest(),
                  test_binary_sha256=hashlib.sha256(args.test_binary.read_bytes()).hexdigest(),
                  count=args.count, warmups=args.warmups, repeats=args.repeats, results=results)
    (args.out / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
