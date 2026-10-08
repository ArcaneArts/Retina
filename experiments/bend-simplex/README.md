# Bend 2 simplex experiment

Retina's current Rust + WGSL implementation remains the better fit. Bend 2.0.36 runs real Metal compute on this Apple M4 Max, and its CPU/GPU portability is useful for prototypes. It did not beat the existing shader on this workload after tuning, and it lacks the Vulkan/AMD and native Windows coverage Retina needs.

This is an isolated experiment: no generator, JNI, MCA format, worldgen configuration, or saved-world behavior is changed.

## Setup and reproduction

Bend 2.0.36 was installed in `~/.local/share/retina-bend/2.0.36`. No shell/PATH files were changed; telemetry is disabled for every invocation. The runner can install the same official arm64 macOS release, verifying the archive against the SHA256 published in its installer.

From the repository root on macOS with Xcode and Rust installed:

```sh
# Only needed if the pinned Bend release is missing:
python3 experiments/bend-simplex/bench.py --install

# Compile first, then benchmark; never compile concurrently with measurements:
python3 experiments/bend-simplex/bench.py --build
python3 experiments/bend-simplex/bench.py --run --repetitions 20 --rounds 2

# Optional, separate diagnostic proving actual Metal command execution:
python3 experiments/bend-simplex/probe.py 256
```

`--bend /absolute/path/to/bend` selects another installation. The benchmark is currently a **Metal** comparison, not a cross-platform GPU harness. Rust builds use two Cargo jobs, two codegen units, and nice +10. Bend's single native compiler invocation also runs at nice +10. Outputs stay under ignored `build/bend-experiment/`. The runner uses Xcode's compiler and SDK when present to avoid this host's Command Line Tools/linker SDK mismatch; it changes no system configuration.

## What was measured

- The same 2D simplex hash, eight gradients, skew constants and four-octave fBm in all three implementations. Coordinates include negative lattice cells. Seeds are 123456789 / 987654321, frequency 0.0035.
- WGSL functions are extracted directly at compile time from `native/src/simplex.wgsl` (`mix_hash` through `fbm`), from source baseline `8d41f8d`. This includes the production shader's arithmetic, not an alternative noise library. The Rust scalar reference is a literal port; Rayon supplies CPU parallelism.
- Independent F32 samples over 512-column strips. The 262,144-sample case is exactly 512x512 columns (one MCA region); larger sizes are four and sixteen regions' worth of columns. The 256-sample case is a partial row, not a literal 16x16 chunk, and none of these are full chunk-generation timings.
- Primary timing includes generation, materialization for CPU consumption, an observable quantized checksum, and output reclamation. WGSL returns a contiguous F32 buffer, maps/copies readback and consumes it. Bend returns a balanced tree of per-leaf F32 arrays and consumes that tree. Bend does **not** additionally flatten it into a Rust/JNI buffer, so that integration cost would be extra.
- Shader/pipeline/runtime initialization and all compilation are excluded from warm times. Each process performs three warm-ups; two shuffled rounds record 20 further iterations each, giving 40 samples per result. `results.json` retains individual samples, medians, p95s, cold-call/setup observations, versions and validation. Build-step timings are cache-dependent observations, not a compiler speed comparison.
- Bend uses the same kernel with `--gpu on` or `--gpu off`, and 1 or 4 CPU workers. `--gpu on` refuses an unusable backend rather than silently allowing CPU fallback. A separate instrumented generated-C diagnostic records actual Metal command-buffer GPU times; it is never substituted for the normal timed executable.
- Bend's built-in clock has millisecond resolution. The experiment adds a tiny CPU IO effect using the runtime's existing monotonic clock in microseconds. Rust uses `Instant`.

The desktop remained active. GPU clock changes and other applications cause variation (visible in p95s and between rounds), so treat these as local comparative results, not universal speed claims. Nothing was benchmarked on CUDA or an AMD GPU. No native compiler workload was running concurrently with these measurements.

## Results on Apple M4 Max

Median warm **total milliseconds**, lower is better. Both Bend and Rust GPU paths use up to four host workers; the CPU columns use four workers.

| F32 samples (four octaves each) | Retina WGSL / Metal | Bend / Metal | Rust / 4 CPU workers | Bend / 4 CPU workers |
| ---: | ---: | ---: | ---: | ---: |
| 256 | 0.187 | 0.529 | 0.009 | 0.007 |
| 262,144 | 0.301 | 0.583 | 1.422 | 2.269 |
| 1,048,576 | 0.726 | 1.118 | 5.553 | 9.118 |
| 4,194,304 | 1.281 | 2.124 | 22.585 | 35.718 |

One region: Bend Metal was about 1.9x slower than the existing WGSL path, while still faster than the four-worker CPU references. Four regions: the gap was about 1.5x. Sixteen regions: Bend was about 1.7x slower. The single-worker Rust/Bend results and device-only WGSL timestamps are in the raw report.

### Tuning that mattered

The first layout returned a tree of height arrays and walked them serially on the CPU. For 4,194,304 values, this took **7.302 ms**. A parallel CPU traversal and larger 1,024-sample GPU leaves reduced it to **2.124 ms**, about **3.4x faster** within Bend. That is a real improvement with the same materialized outputs. It still trails WGSL's **1.281 ms**.

We swept 16, 64, 256 and 1,024 samples per leaf, with serial and parallel consumption, independently for each output size. Final measurements use fresh iterations after selecting a layout. Selected configurations:

| Samples | Samples per GPU leaf | CPU consumption |
| ---: | ---: | --- |
| 256 | 64 | serial |
| 262,144 | 256 | parallel |
| 1,048,576 | 1024 | parallel |
| 4,194,304 | 1,024 | parallel |

`serial-baseline-summary.json` records the first Bend GPU matrix; `parallel-consume.json` records the intermediate experiment. `results.json` contains the final tuning sweep and independent final rounds.

### Correctness checks

The validation executable dumps 1,024 actual GPU-produced F32 values, separately from timed runs, and compares them with the Rust reference. Bend CPU and Metal both had maximum absolute error about **2.98e-8** (decimal printing precision). Their quantized full-output checksums also matched the Rust CPU reference at all four final sizes. WGSL checks every generated value against Rust on its first run; its modest floating-point differences remain below the 1e-4 acceptance tolerance. The Metal and Rust checksums therefore need not match exactly. This respects the project's lack of a cross-backend determinism requirement.

This tests numerical agreement; it is not a formal floating-point proof. Bend's F32 operations are axiomatic, as its own documentation states.

## How Bend actually runs

Bend 2 is a new language/runtime, not old Bend 1/HVM with an updated package name. Ordinary pure functions compile into native CPU code. Balanced parallel calls create a fork/join tree:

```python
a b = batch(depth, left) batch(depth, right)
Node{a, b}
```

Calling `batch!(...)` sends that call and its parallel descendants to the GPU. Calling it normally, or running with `--gpu off`, uses the CPU pool. You still choose the CPU/GPU boundary and task grain. The runtime does not automatically decide which arbitrary parts of the generator should run where, and poorly balanced or excessively fine tasks can lose heavily to scheduling overhead.

On Apple hardware the runtime shares a heap between CPU and GPU, avoiding an explicit PCIe-style transfer. Ownership, allocation, cache visibility, task scheduling and traversal still cost time. Arrays have one owner: a single mutable array cannot simply be handed to all forked tasks. Our safe implementation gives each GPU leaf its own array and returns an owned tree. The parallel traversal experiment demonstrates both the convenience of moving logic between executors and the performance cost of the chosen data shape.

File writes and other IO remain CPU effects. We used a foreign C IO effect only for accurate timing, not to implement the noise or bypass Bend's GPU execution.

## Fit for Retina

**Useful:** experimenting with balanced pure algorithms, running the same logic on CPU and Metal/CUDA, and prototyping larger fused GPU stages without maintaining a separate shader language. The noise implementation itself is portable between Bend's CPU and GPU executors.

**Costs for this project:**

- Current GPU targets are Metal and CUDA; there is no listed Vulkan/AMD GPU backend. Native Windows is also unsupported (WSL is the documented route). Retina already uses wgpu's wider backend coverage.
- Jigsaw assembly and registry-driven feature traversal have variable work and branching. Bend's current scheduler expects balanced calls; those tasks do not automatically become efficient GPU work.
- Dense mutable lighting/frontier algorithms need a suitable ownership/layout design. Shared atomic arrays are experimental and require `@unsafe`; changing languages does not remove that algorithm work.
- MCA packing, compression, disk writes, registry transfer and Minecraft's JNI lifecycle still need implementation/integration. Moving pure encoders into Bend is conceivable; this noise benchmark establishes no speedup for those stages.
- The documented numeric types are Nat/U32/F32, not U64/I64/F64. Minecraft seeds and packed NBT longs would need multiple words or foreign code. There is no separate/incremental native compilation, and the compiler/runtime and tooling are young.

**Recommendation:** retain Rust + WGSL for production. Bend is a viable research tool here, but there is no measured performance reason to rewrite Retina in it, and the platform/integration tradeoffs are substantial. The successful tuning reinforces a useful direction for Retina itself: larger fused GPU jobs, compact output layouts and parallel CPU consumption where it actually helps. This experiment does not claim a full-generator speedup.

## Primary references

- [Bend 2 repository and limitations](https://github.com/bendlang/bend)
- [Execution, ownership, parallelism and foreign effects](https://github.com/bendlang/bend/blob/main/guide/GUIDE.md)
- [GPU scheduling and shader-layout guide](https://github.com/bendlang/bend/blob/main/guide/SHADERS.md)
- [Pinned release](https://github.com/bendlang/bend/releases/tag/v2.0.36)

The upstream checkout used for investigation was `059266225b77c8ca256ac6b25ee5c21449bab151`; executables were compiled by the installed **2.0.36 release**, not by that checkout. The shader guide identifies itself as an AI-written tutorial, so its performance advice was tested locally rather than treated as a guarantee.
