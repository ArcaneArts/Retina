#!/usr/bin/env python3
"""Offline same-backend lighting transport experiment; never touches live saves.

The parent does not load the native library. Every trial is a fresh child running
the production saved-definition/MCA benchmark. No backend defaults are changed.
"""
import argparse
from dataclasses import asdict, dataclass
import itertools
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
import uuid
import zipfile

from benchmark_support import percentile, write_json


@dataclass(frozen=True)
class Setting:
    input: str
    output: str
    palettes: int

    @property
    def name(self):
        return f"{self.input}-{self.output}-{'gpu' if self.palettes else 'cpu'}"


SETTINGS = [Setting(*row) for row in itertools.product(("dense", "compact"), ("dense", "compact"), (0, 1))]
BASELINE = SETTINGS[0]
EVENT_LOG = None


def trials(settings, pairs):
    for candidate in settings:
        if candidate == BASELINE:
            continue
        for pair in range(pairs):
            order = (BASELINE, candidate) if pair % 2 == 0 else (candidate, BASELINE)
            for setting in order:
                yield candidate.name, pair, setting


def child_environment(setting, cache, budget):
    env = {k: v for k, v in os.environ.items()
           if not k.upper().startswith("RETINA_") and k.upper() not in ("PYTHONPATH", "PYTHONHOME")}
    env.update(RETINA_LIGHTING_INPUT=setting.input, RETINA_LIGHTING_OUTPUT=setting.output,
               RETINA_GPU_PALETTES=str(setting.palettes), RETINA_PIPELINE_CACHE_DIR=str(cache),
               RETINA_REGION_THREADS=str(budget), RAYON_NUM_THREADS="2", PYTHONUNBUFFERED="1")
    return env


def event(event_name, status="info", **context):
    line = "QA_EVT " + json.dumps(dict(event=event_name, status=status, context=context))
    print(line, flush=True)
    if EVENT_LOG is not None:
        with EVENT_LOG.open("a", encoding="utf-8") as log:
            log.write(line + "\n")


def observe_host():
    result = dict(system=platform.platform(), processor=platform.processor(),
                  logical_processors=os.cpu_count(), python=sys.version,
                  driver_cache="Uncontrolled; application cache state is recorded separately")
    if os.name == "nt":
        script = ("$ErrorActionPreference='Stop'; @{ "
                  "gpu=@(Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,AdapterRAM); "
                  "cpu=@(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors); "
                  "load=@(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process | "
                  "Where-Object { $_.Name -ne '_Total' -and $_.Name -ne 'Idle' } | "
                  "Sort-Object PercentProcessorTime -Descending | Select-Object -First 8 Name,PercentProcessorTime); "
                  "power=@(powercfg /getactivescheme) } | ConvertTo-Json -Depth 4")
        try:
            run = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script],
                                 capture_output=True, text=True, timeout=20, check=True)
            result["windows"] = json.loads(run.stdout)
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            result["windows_observation_unavailable"] = str(error)
    else:
        result["load_average"] = os.getloadavg() if hasattr(os, "getloadavg") else None
    return result


def stop_child(child):
    # Only this controller's child. No process-name or system-wide termination.
    if child.poll() is None:
        child.terminate()
        try:
            child.wait(timeout=10)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()


def run_child(command, env, directory, log_path, job, total):
    started = time.perf_counter()
    with log_path.open("wb") as log:
        child = subprocess.Popen(command, env=env, cwd=directory, stdout=log, stderr=subprocess.STDOUT)
        try:
            while True:
                try:
                    code = child.wait(timeout=15)
                    return code, time.perf_counter() - started
                except subprocess.TimeoutExpired:
                    event("waiting", job=job, total_jobs=total,
                          elapsed_seconds=round(time.perf_counter() - started, 1),
                          note="Native operation still running; no driver compile percentage is inferred")
        except BaseException:
            stop_child(child)
            raise


def verify_measurement(data, backend, reference):
    # Verify actual results after generation, rather than gating its execution.
    prefix = dict(cpu="CPU:", metal="Metal:", vulkan="Vulkan:", dx12="Dx12:")[backend]
    if not data["backend_selected"].startswith(prefix):
        raise ValueError(f"Requested {backend}; executed {data['backend_selected']}")
    if any(row["generated"] != 1024 or row["preserved"] != 0 for row in data["regions"]):
        raise ValueError("Expected complete fresh regions, with no preserved chunks")
    if reference is not None and data["corpus"] != reference:
        raise ValueError("Corpus membership or decompressed NBT hashes changed")


def summary(records):
    groups = {}
    for record in records:
        if record["status"] != "pass":
            continue
        key = (record["dimension"], record["comparison"], record["phase"], record["setting"])
        groups.setdefault(key, []).append(record["measurement"])
    result = []
    for (dimension, comparison, phase, setting), values in groups.items():
        rates = [v["chunks_per_second"] for v in values]
        result.append(dict(dimension=dimension, comparison=comparison, phase=phase, setting=setting,
                           processes=len(values), median_chunks_per_second=statistics.median(rates),
                           descriptive_p95_batch_ms=percentile([v["total_ms"] for v in values], 95),
                           sample_stdev_chunks_per_second=statistics.stdev(rates) if len(rates) > 1 else None,
                           disposition="Measurements only; paired analysis and broader coverage required before retention"))
    return result


def optional_manifest(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Bundle metadata is not an object")
        return value
    except (OSError, ValueError) as error:
        return {"unavailable": str(error)}


def package_results(root):
    destination = root / "SEND-THIS-results.zip"
    # Explicit allowlist: no MCA, definition, pipeline cache, executable or dump.
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root)
            if path.is_file() and path.suffix in (".json", ".log", ".txt") and "cache" not in relative.parts:
                archive.write(path, str(relative))
    return destination


def parse_args():
    bundle = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--interactive", action="store_true")
    parser.add_argument("--library", type=Path, default=bundle / "native/retina_worldgen.dll")
    parser.add_argument("--definition", type=Path, default=bundle / "definitions/vanilla/retina.dat")
    parser.add_argument("--backend", choices=("vulkan", "dx12", "metal", "cpu"), default="vulkan")
    parser.add_argument("--mode", choices=("screen", "qualify"), default="screen")
    parser.add_argument("--dimensions", nargs="+", default=["minecraft:overworld", "minecraft:the_nether"])
    parser.add_argument("--settings", nargs="+", choices=[s.name for s in SETTINGS], default=[s.name for s in SETTINGS])
    parser.add_argument("--workers", type=int, choices=range(1, 17), default=min(16, os.cpu_count() or 1))
    parser.add_argument("--count", type=int, default=2)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--attribution", action="store_true", help="Separate instrumented pass; excluded from headline timings")
    parser.add_argument("--cache-mode", choices=("shared", "fresh"), default="shared")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--keep-regions", action="store_true")
    args = parser.parse_args()
    if args.count < 1 or args.warmups < 1:
        parser.error("Use at least one measured region and one warmup")
    if not any(name != BASELINE.name for name in args.settings):
        parser.error("Select at least one candidate setting; the dense baseline is always included")
    if args.interactive:
        print("Retina offline transport test. No Minecraft, Java or Python installation needed.")
        print("Keep the PC plugged in; pause other generation/benchmarks. Ctrl+C saves partial results.")
        args.backend = "dx12" if input("Backend: 1 Vulkan [default], 2 DirectX 12: ").strip() == "2" else "vulkan"
        args.mode = "qualify" if input("Mode: 1 screen [default], 2 qualification: ").strip() == "2" else "screen"
        custom = input("Optional custom retina.dat path (Enter for bundled vanilla): ").strip().strip('"')
        if custom:
            args.definition = Path(custom)
        args.note = input("Other active workloads / power or GPU notes (optional): ")
    return args


def main():
    global EVENT_LOG
    args = parse_args()
    root = (args.out or Path(__file__).resolve().parent.parent / "results" /
            (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])).resolve()
    root.mkdir(parents=True, exist_ok=False)
    EVENT_LOG = root / "controller.log"
    event("session_started", output=str(root), note="Collecting optional host observations before trials")
    records = []
    status, error = "running", None
    settings = [s for s in SETTINGS if s.name in args.settings and s != BASELINE]
    pairs = 2 if args.mode == "screen" else 5
    plan = [(dimension, candidate, pair, setting, "headline")
            for dimension in args.dimensions for candidate, pair, setting in trials(settings, pairs)]
    if args.attribution:
        plan += [(d, "attribution", 0, s, "instrumented") for d in args.dimensions for s in (BASELINE, *settings)]
    references = {}
    reference_digests = {}
    run_directories = []
    manifest_path = Path(__file__).resolve().parent.parent / "BUNDLE.json"
    session = dict(schema="retina.transport-matrix", schema_version=1, started_unix=time.time(),
                   options={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                   plan_jobs=len(plan), host_start=observe_host(),
                   bundle=optional_manifest(manifest_path),
                   limits=["Offline lit-MCA boundary; excludes live Java delivery/FPS",
                           "Driver caches are uncontrolled; startup samples require cache-state matching",
                           "Screening is not acceptance; qualification and wider correctness coverage still required",
                           "Instrumented timings are separate from headline observations"])
    write_json(root / "session.json", session)
    try:
        for index, (dimension, candidate, pair, setting, phase) in enumerate(plan, 1):
            job = f"{index:03d}-{dimension.rsplit(':', 1)[-1]}-{setting.name}-{phase}"
            directory = root / job
            directory.mkdir()
            out = directory / "regions"
            cache = root / "cache" / (job if args.cache_mode == "fresh" else args.backend)
            cache_before = {str(p.relative_to(cache)): p.stat().st_size for p in cache.rglob("*") if p.is_file()}
            cache.mkdir(parents=True, exist_ok=True)
            reference = references.get(dimension)
            command = [sys.executable, str(Path(__file__).with_name("native-region-benchmark.py")),
                       "--library", str(args.library.resolve()), "--definition", str(args.definition.resolve()),
                       "--dimension", dimension, "--backend", args.backend, "--assembly-threads", str(args.workers),
                       "--warmups", str(args.warmups), "--count", str(args.count), "--grid-width", str(args.count),
                       "--scope", "lit-mca", "--lighting", "enabled", "--mca-compression", "zlib",
                       "--origin-x", "0", "--origin-z", "0", "--readiness", "shader-ready",
                       "--specialization-timeout", "1800", "--out", str(out)]
            if session["bundle"].get("native_revision"):
                command += ["--library-revision", session["bundle"]["native_revision"]]
            if reference:
                command += ["--compare", str(reference)]
            if phase == "instrumented":
                command += ["--diagnostics", "--gpu-interval", "--startup-debug"]
            record = dict(job=job, dimension=dimension, comparison=candidate, pair=pair,
                          setting=setting.name, transport=asdict(setting), phase=phase, command=command,
                          cache_files_before=cache_before, status="running")
            records.append(record)
            write_json(root / "progress.json", records)
            event("trial_started", job=index, total_jobs=len(plan), completed=index-1,
                  remaining=len(plan)-index+1, name=job, output=str(root))
            code, seconds = run_child(command, child_environment(setting, cache, args.workers), directory,
                                      directory / "native.log", index, len(plan))
            record.update(exit_code=code, child_elapsed_seconds=seconds)
            if code:
                record["status"] = "fail"
                raise RuntimeError(f"{job} exited {code}; see its native.log")
            data = json.loads((out / "measurements.json").read_text())
            record["measurement"] = data
            verify_measurement(data, args.backend, reference_digests.get(dimension))
            if reference is None:
                references[dimension] = out
                reference_digests[dimension] = data["corpus"]
            record["status"] = "pass"
            run_directories.append(out)
            # Retain exact reference MCA until every same-backend comparison ends.
            if out != references[dimension] and not args.keep_regions:
                for file in out.glob("*.mca"):
                    file.unlink()
            event("trial_completed", "pass", job=index, total_jobs=len(plan),
                  completed=index, remaining=len(plan)-index,
                  chunks_per_second=round(data["chunks_per_second"], 1),
                  nbt="exact same-backend comparison" if reference else "reference corpus recorded")
        status = "completed"
    except KeyboardInterrupt:
        status, error = "cancelled", "User interrupted; completed trials retained, no automatic restart"
        event("cancelled", "warn", details=error)
    except Exception as failure:
        status, error = "failed", str(failure)
        event("experiment_failed", "fail", details=error)
    finally:
        if records and records[-1]["status"] == "running":
            records[-1]["status"] = "cancelled" if status == "cancelled" else "fail"
        write_json(root / "progress.json", records)
        event("collecting_final_observations", result=status)
        write_json(root / "summary.json", dict(status=status, error=error, completed_unix=time.time(),
                   groups=summary(records), host_end=observe_host(),
                   acceptance="Pending independent paired analysis and required hardware/fixture coverage"))
        if not args.keep_regions:
            for directory in run_directories:
                for file in directory.glob("*.mca"):
                    file.unlink()
        package = package_results(root)
        print(f"\nStatus: {status}\nSend this file: {package}", flush=True)
    return 0 if status == "completed" else 1


if __name__ == "__main__":
    sys.exit(main())
