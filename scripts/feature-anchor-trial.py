#!/usr/bin/env python3
"""Bounded opt-in complete feature-program GPU diagnostics; never launches Minecraft."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

TESTS = {
    "recipes": ("gpu_complete_plant_anchor_probe", "ANCHOR_PROBE"),
    "census": ("gpu_anchor_workload_census", "ANCHOR_CENSUS"),
    "live": ("gpu_vine_anchor_live_probe", "VINE_LIVE_PROBE"),
    "batch": ("gpu_vine_anchor_batch_probe", "VINE_BATCH_PROBE"),
}
ROOT = Path(__file__).resolve().parent.parent


def run(command: list[str], env: dict[str, str], seconds: int) -> dict:
    started = time.monotonic()
    process = subprocess.Popen(
        ["nice", "-n", "10", *command], cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        start_new_session=True,
    )
    timeout = False
    try:
        stdout, stderr = process.communicate(timeout=seconds)
    except subprocess.TimeoutExpired:
        timeout = True
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            stdout, stderr = process.communicate(timeout=1)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            stdout, stderr = process.communicate()
        # A descendant may close its pipes and survive the first signal even
        # after its parent exits. Finish cleanup for the owned process group.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    return {"command": command, "deadline_seconds": seconds,
            "elapsed_seconds": time.monotonic() - started,
            "returncode": 124 if timeout else process.returncode,
            "timed_out": timeout, "stdout": stdout, "stderr": stderr}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True, type=Path)
    parser.add_argument("--test", required=True, choices=TESTS)
    parser.add_argument("--binary", type=Path,
                        help="Reuse the executable printed by a completed library-test build")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    env = dict(os.environ, CARGO_BUILD_JOBS="2", RAYON_NUM_THREADS="2",
               RETINA_REGION_THREADS="2", RETINA_ANCHOR_PROFILE=str(args.profile.resolve()))
    report = {"schema": "retina.feature-anchor-trial", "version": 1,
              "test": args.test, "cpu_workers": 2, "nice": 10,
              "scope": "diagnostic only; no production offload or whole-MCA speed claim"}
    binary = args.binary.resolve() if args.binary else None
    try:
        if binary is None:
            build = run(["cargo", "test", "--release", "--locked", "--manifest-path",
                         "native/Cargo.toml", "--lib", "--no-run", "--message-format=json"], env, 60)
            report["build"] = build
            if build["returncode"]:
                return build["returncode"]
            for line in build["stdout"].splitlines():
                try:
                    item = json.loads(line)
                except ValueError:
                    continue
                if (item.get("reason") == "compiler-artifact"
                        and item.get("target", {}).get("name") == "retina_worldgen"
                        and item.get("profile", {}).get("test") and item.get("executable")):
                    binary = Path(item["executable"])
            if binary is None:
                raise RuntimeError("Cargo completed without reporting the library test executable")
        name, marker = TESTS[args.test]
        result = run([str(binary), name, "--ignored", "--nocapture", "--test-threads=1"], env, 30)
        report["execution"] = result
        print(result["stdout"], end="")
        print(result["stderr"], end="", file=sys.stderr)
        if result["returncode"]:
            return result["returncode"]
        matching = [line.split(marker + " ", 1)[1] for line in result["stdout"].splitlines()
                    if marker + " " in line]
        if len(matching) != 1:
            raise RuntimeError("Test finished without exactly one diagnostic result")
        report["result"] = json.loads(matching[0])
        report["profile_sha256"] = hashlib.sha256(args.profile.read_bytes()).hexdigest()
        report["binary_sha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
        return 0
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Saved diagnostic: {args.output}")


if __name__ == "__main__":
    sys.exit(main())
