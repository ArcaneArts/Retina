#!/usr/bin/env python3
"""Compare real first-profile interpreter compilation with fresh shader identities.

Use the release native test executable, an actual exported registry profile and
a fresh private output directory. No driver caches or live saves are modified.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import statistics
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
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--comparison", choices=("dispatch", "preload"), default="dispatch")
    parser.add_argument("--order", choices=("direct-first", "shared-first", "reference-first", "candidate-first"), default="reference-first")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    reference, candidate = ("direct", "shared") if args.comparison == "dispatch" else ("demand", "preload")
    modes = (candidate, reference) if args.order in ("shared-first", "candidate-first") else (reference, candidate)
    reports = {}
    for mode in modes:
        output = args.out / mode
        env = dict(os.environ, RETINA_COMPILE_PROBE="1", RETINA_COMPILE_REUSE="1",
                   RETINA_DENSITY_COMPOSITION="0", RETINA_SPECIALIZED_TERRAIN="0",
                   RETINA_INTERPRETER_DISPATCH="1" if args.comparison == "preload" or mode == "shared" else "0",
                   RETINA_INTERPRETER_PRELOAD="1" if mode == "preload" else "0",
                   RETINA_GENERIC_PIPELINE_TAG=f"retina-compile-{uuid.uuid4().hex}",
                   RETINA_COMPILE_REGIONS=str(args.count),
                   RETINA_PROGRAM_PARITY_PROFILE=str(args.profile.resolve()),
                   RETINA_COMPILE_OUT=str(output.resolve()))
        log = args.out / f"{mode}.log"
        with log.open("w") as stream:
            subprocess.run(["/usr/bin/time", "-l" if platform.system() == "Darwin" else "-v",
                            "nice", "-n", "10", str(args.test_binary.resolve()),
                            "actual_interpreter_pipeline_compile", "--ignored", "--nocapture",
                            "--test-threads=1"], env=env, stdout=stream,
                           stderr=subprocess.STDOUT, check=True)
        text = log.read_text()
        calls = [json.loads(line.removeprefix("RETINA_COMPILE "))
                 for line in text.splitlines() if line.startswith("RETINA_COMPILE ")]
        (output / "compile.json").write_text(json.dumps(calls, indent=2))
        measured = json.loads((output / "measurements.json").read_text())
        peak = re.search(r"(\d+)\s+maximum resident set size", text)
        if platform.system() != "Darwin":
            peak = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", text)
        reports[mode] = dict(
            pipelines=sum(call["phase"] == "pipeline" for call in calls),
            bundle_ms=next(call["ms"] for call in calls if call["phase"] == "bundle"),
            initialize_ms=measured["initialize_ms"], registration_ms=measured["registration_ms"],
            first_region_ms=measured["regions"][0]["ms"],
            init_to_first_region_ms=measured["initialize_ms"] + measured["registration_ms"] + measured["regions"][0]["ms"],
            subsequent_mean_ms=statistics.mean(row["ms"] for row in measured["regions"][1:])
                if args.count > 1 else None,
            bytes=sum(row["bytes"] for row in measured["regions"]),
            peak_rss_bytes=int(peak.group(1)) * (1 if platform.system() == "Darwin" else 1024)
                if peak else None,
        )
        print(json.dumps(dict(mode=mode, **reports[mode])), flush=True)
    matched = 0
    for index in range(args.count):
        for slot, (a, b) in enumerate(zip(records(args.out / reference / f"{index}.mca"),
                                        records(args.out / candidate / f"{index}.mca"), strict=True)):
            assert a == b, (index, slot)
            matched += 1
    result = dict(comparison=args.comparison, profile=str(args.profile.resolve()), modes=reports, identical_nbt_chunks=matched)
    (args.out / "comparison.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(dict(identical_nbt_chunks=matched)), flush=True)


if __name__ == "__main__":
    main()
