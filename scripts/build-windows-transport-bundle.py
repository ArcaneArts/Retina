#!/usr/bin/env python3
"""Package the offline Windows x64 transport experiment; not a mod or release.

Uses the official isolated CPython embed distribution with a pinned SHA-256.
Build the Windows native through the existing Gradle buildNativeWindowsX64 task
or its cargo-xwin equivalent; pass that DLL here. Nothing downloads at test time.
"""
import argparse
import ctypes as c
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

PYTHON_VERSION = "3.14.10"
PYTHON_URL = "https://www.python.org/ftp/python/3.14.10/python-3.14.10-embed-amd64.zip"
PYTHON_SHA256 = "90905e64a840557d8c1bc4f5b74db3d7ee85e3ffb217611cac1c7813acea4159"
PYTHON_SOURCE = "https://www.python.org/downloads/release/python-31410/"
SCRIPTS = ("lighting-transport-matrix.py", "native-region-benchmark.py", "benchmark_support.py",
           "saved_definition.py", "process_usage.py")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def materialize(library, catalog, root, seed):
    lib = c.CDLL(str(library.resolve()))
    function = lib.retina_materialize_world_definition
    function.argtypes = [c.c_char_p, c.c_uint64, c.c_char_p, c.c_uint64, c.c_int64, c.c_char_p, c.c_uint64]
    function.restype = c.c_int
    source, variant, target = str(catalog.resolve()).encode(), b"normal", str(root.resolve()).encode()
    root.mkdir(parents=True)
    status = function(source, len(source), variant, len(variant), seed, target, len(target))
    if status:
        lib.retina_last_error.argtypes = [c.c_void_p, c.c_uint64]
        error = c.create_string_buffer(8192)
        lib.retina_last_error(error, len(error))
        raise RuntimeError(error.value.decode(errors="replace"))


def main():
    repo = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True, help="Built Windows x64 retina_worldgen.dll")
    parser.add_argument("--native-revision", required=True, help="Actual source commit used for that native build")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--definition", type=Path, help="Existing saved definition; copied without mutation")
    source.add_argument("--materializer", type=Path, help="Host native DLL/dylib used only to materialize the committed catalog")
    parser.add_argument("--catalog", type=Path, default=repo / "assets/world-definitions/26.3/vanilla-binary/retina-catalog.dat")
    parser.add_argument("--seed", type=int, default=123456789)
    parser.add_argument("--runtime-archive", type=Path, default=Path.home() / ".cache/retina/qa-runtimes/python-3.14.10-embed-amd64.zip")
    parser.add_argument("--out", type=Path, required=True, help="Fresh owned staging directory")
    parser.add_argument("--zip", type=Path, required=True, help="Stable handoff file; replaced after successful packaging")
    args = parser.parse_args()
    stage = args.out.resolve()
    stage.mkdir(parents=True, exist_ok=False)
    runtime = args.runtime_archive
    if not runtime.is_file():
        runtime.parent.mkdir(parents=True, exist_ok=True)
        temporary = runtime.with_suffix(".download")
        with urllib.request.urlopen(PYTHON_URL, timeout=60) as response, temporary.open("wb") as target:
            shutil.copyfileobj(response, target)
        temporary.replace(runtime)
    if sha256(runtime) != PYTHON_SHA256:
        raise ValueError("CPython archive differs from the official release SHA-256; not packaging it")
    with zipfile.ZipFile(runtime) as archive:
        for member in archive.infolist():
            target = (stage / "runtime" / member.filename).resolve()
            if not target.is_relative_to(stage / "runtime"):
                raise ValueError("Archive path escapes runtime directory")
        archive.extractall(stage / "runtime")
    # Embeddable Python intentionally ignores user PATH/registry/site packages.
    (stage / "runtime/python314._pth").write_text("python314.zip\n.\n../harness\n", encoding="utf-8")
    (stage / "harness").mkdir()
    for script in SCRIPTS:
        shutil.copy2(repo / "scripts" / script, stage / "harness" / script)
    (stage / "native").mkdir()
    shutil.copy2(args.library, stage / "native/retina_worldgen.dll")
    definition_root = stage / "definitions/vanilla"
    if args.definition:
        definition_root.mkdir(parents=True)
        shutil.copy2(args.definition, definition_root / "retina.dat")
    else:
        materialize(args.materializer, args.catalog, definition_root, args.seed)
    shutil.copytree(repo / "common/src/main/resources/META-INF/licenses", stage / "licenses")
    shutil.copy2(repo / "LICENSE", stage / "licenses/RETINA-LICENSE")
    (stage / "Run-tests.cmd").write_bytes(
        b'@echo off\r\nsetlocal\r\n"%~dp0runtime\\python.exe" -u "%~dp0harness\\lighting-transport-matrix.py" --interactive %*\r\n'
        b'set "RETINA_QA_EXIT=%ERRORLEVEL%"\r\necho.\r\npause\r\nexit /b %RETINA_QA_EXIT%\r\n')
    shutil.copy2(repo / "scripts/windows-transport-README.txt", stage / "README.txt")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    manifest = dict(schema="retina.windows-transport-bundle", schema_version=1,
                    native_revision=args.native_revision, harness_base_revision=revision,
                    python=dict(version=PYTHON_VERSION, url=PYTHON_URL, source=PYTHON_SOURCE, sha256=PYTHON_SHA256),
                    definition_source=str(args.definition.resolve()) if args.definition else dict(
                        catalog_sha256=sha256(args.catalog), variant="normal", seed=args.seed,
                        materializer_sha256=sha256(args.materializer)),
                    files={str(p.relative_to(stage)): sha256(p) for p in sorted(stage.rglob("*")) if p.is_file()},
                    validation="Cross-built and packaged only; actual Windows execution/acceptance must be recorded separately")
    (stage / "BUNDLE.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    destination = args.zip.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".pending.zip")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(stage.rglob("*")):
            if path.is_file():
                archive.write(path, str(Path("retina-windows-transport-test") / path.relative_to(stage)))
    temporary.replace(destination)
    print(json.dumps(dict(zip=str(destination), bytes=destination.stat().st_size, sha256=sha256(destination),
                          stage=str(stage), native_sha256=sha256(args.library)), indent=2))


if __name__ == "__main__":
    main()
