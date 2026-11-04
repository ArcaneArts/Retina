# Cycle 5 — reproducible Windows transport experiment

Area O4 remains **pending real Windows results**. This cycle adds an offline experiment runner and a separately built Windows x64 ZIP. It changes no generation code, transport defaults, release workflow, or ordinary mod JAR. Local controller validation establishes the experiment's behavior; it is not Windows Vulkan acceptance or a performance optimization claim.

The native comes from main `47f9488ac875727eba4d427e3ce0ba804f59bdca`, native SHA-256 `0050a1ca30152486b3e02f7672ff4fd0cfe0b0b7a0cbd49431588b1870de4c28`. The Windows x64 MSVC cross-build completed in 1m46s using Rust 1.93.0, the existing cargo-xwin pathway, locked release settings and static CRT. The resulting PE is AMD64; its imports are Windows system libraries. This is build evidence, not successful Windows execution.

## What is measured

`scripts/lighting-transport-matrix.py` runs all eight combinations of existing lighting-input, lighting-output and GPU-palette controls. Each of seven candidates is paired with dense/dense/CPU-palette baseline trials. Screening uses two pairs in AB/BA order; qualification uses five pairs with alternating order. The default dimensions are Overworld and Nether. An optional custom/tall `retina.dat` uses the same production reader and request binding.

Each fresh child process warms one complete region, awaits a final shader state, then measures two complete lit, decorated, zlib MCA regions by default. Workers, coordinates, global Rayon budget, completion boundary and requested flags are explicit and recorded. Unrelated `RETINA_*` and Python search-path overrides are removed from the child environment. The parent never loads a native library.

Every result is checked after generation for the actual backend and 1,024 fresh chunks per region. Later trials compare exact decompressed NBT and membership to the dimension's retained same-backend reference, then check its corpus digest. No CPU/GPU or Vulkan/DX12 floating-point equivalence is assumed. GPU terrain-job counts do not gate valid mixed paths.

Startup, first warmup, readiness wait and measured generation are separate fields. Default trials share a private application cache, recording files present before each trial; the first starts empty. Driver caches remain uncontrolled. These startup observations must be matched by cache state before drawing a startup-speed conclusion. `--cache-mode fresh` gives every child its own empty application cache without clearing system driver caches.

`--attribution` adds a separate pass with opt-in detailed counters, interval capture and startup logs. These timings are labeled instrumented and excluded from headline groups. Program transfer-byte snapshots remain available in ordinary runs. Summary groups are descriptive observations, not automatic retention decisions.

## Artifact and user workflow

The stable handoff location is:

```text
build/release/retina-windows-transport-test.zip
```

Extract the whole ZIP into a writable folder and double-click `Run-tests.cmd`. Choose Vulkan and screening; accept bundled vanilla or provide a saved custom definition. No Python/Java installation is required by the bundle design. Return the printed `SEND-THIS-results.zip`, including on failure. Optional DirectX is a separate run.

The ZIP includes the current Windows native, five existing/new benchmark modules, a production-materialized vanilla definition at seed 123456789, component licenses and official CPython 3.14.10 Windows x64 embeddable runtime. The runtime's [official release SHA-256](https://www.python.org/downloads/release/python-31410/) is pinned, and its original license is retained. Its isolated `._pth` explicitly adds the harness directory, following the [embedding documentation](https://docs.python.org/3/using/windows.html#the-embeddable-package). Nothing downloads when the tester runs it.

`BUNDLE.json` pins the native source identity, runtime download/hash, materialization source and every packaged file hash. The final handoff ZIP hash and packaging record remain with local evidence. This is an explicit test artifact, not a release asset or a replacement for `retina-windows-x64.jar`.

The runner prints structured `QA_EVT` checkpoints, completed/remaining jobs and a 15-second heartbeat while an operation is pending. That heartbeat does not infer internal driver-compiler progress. A failed trial stops and preserves its log/partial output; no timeout-driven restart occurs. Ctrl+C terminates only the owned child and packages completed evidence. Completed MCA files are deleted only within newly owned run directories, after exact comparison; failed partial files can remain for investigation.

Return archives allow only JSON/log/text evidence and exclude definitions, MCA data, caches, executables and dumps. They include local paths and machine information. Optional Windows CIM observations collect adapter/driver, CPU, power scheme and competing-process snapshots; unavailable observations do not block generation. Windows peak working-set bytes are optional diagnostics through `K32GetProcessMemoryInfo`, with unavailable counters left null.

## Reproduction

Cross-build from the isolated cycle checkout:

```sh
cargo xwin build --xwin-include-atl --release --locked --lib \
  --target x86_64-pc-windows-msvc --target-dir "$NATIVE_TARGET" \
  --config 'target.x86_64-pc-windows-msvc.rustflags=["-C","target-feature=+crt-static"]'
```

Run that command in `native/`. Package from the repository root:

```sh
python3 scripts/build-windows-transport-bundle.py \
  --library "$WINDOWS_DLL" --native-revision "$NATIVE_COMMIT" \
  --materializer "$HOST_NATIVE" --out "$FRESH_STAGING_DIRECTORY" \
  --zip "$STABLE_HANDOFF_ZIP"
```

The builder can instead copy an existing `--definition`. `--runtime-archive` accepts a predownloaded official archive and still verifies the pinned hash. This host's Python certificate store initially rejected the HTTPS download. A normal certificate-verified `curl` download populated that cache; TLS verification was never disabled. The initial failure is retained in the experiment record.

## Validation and disposition

The machine-readable [cycle record](refinement-cycle-05-windows-transport-2026-11-04.json) contains individual controller results, hashes, actual backends, correctness counts and limitations. Large logs, native binaries, ZIPs and profiler/world data remain outside Git.

Local production-native validation completed **80 processes / 80 measured regions**, plus one untimed warmup per process: four CPU Overworld controller checks, 72 Metal trials (56 ordinary and 16 separately instrumented across Overworld/Nether), and four final Metal End checks using the current controller. **77,824 strict same-backend NBT comparisons pass**; the four initial references are recorded rather than counted as comparisons. All eight transport combinations pass on Metal in both primary dimensions. End records the actual Metal backend and positive lighting transfers without requiring terrain GPU jobs. These small local screens are not five-pair performance qualification.

Successful MCA cleanup leaves no region files in those completed experiment directories; return ZIPs contain logs/JSON evidence. The machine-readable Git record retains individual metrics and nonzero diagnostic counters; full event arrays, terrain-tile rows and zero-valued counters remain in the external per-trial files identified there. The post-validation host observation reports AC attached and power mode 0; other host workloads varied and no machine settings or unrelated processes were changed.

Focused tests cover factorial pairing, leaked environment overrides, wrong-backend/partial-region/NBT rejection, return-archive exclusions, optional metadata failure, Windows-sized memory counters, diagnostic API failure and owned-child termination. A real missing-native child also exercises failure packaging. The first controller attempt exposed a checkpoint argument collision; it was corrected before the successful native matrix. These failed setup runs are not generation successes.

Retention is for a reproducible experiment and accurate optional Windows counters. No Windows Vulkan or DirectX speed claim is made, and no backend default changes. Real Windows runs, tall/custom coverage and qualification remain required. Continue O2/O3/O5/O6/O9 independently while those results are pending. No release is part of this cycle.
