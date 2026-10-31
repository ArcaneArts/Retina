# Universal Retina distribution

`./gradlew universalJar` assembles `universal/build/libs/retina-<minecraft>-<version>.jar`.
Use `-PretinaHostOnly` for a local artifact containing only this host's native library.
The universal task compiles the existing adapters without producing their separate
installable distributions or source JARs. Existing loader development tasks remain available.

The assembly includes the Fabric and NeoForge descriptors, Bukkit plugin descriptor,
common/client mixin resources, shared core, and each adapter's classes. The merge reads
actual compiler outputs and accepts duplicate entries only if their bytes are identical.
A conflicting class fails with its path and both producers; there is no preferred-loader
fallback. The input hashes and duplicate producers are recorded in
`META-INF/retina/runtime-inputs.json` before dependency relocation.

Bukkit's Gson and Adventure dependencies are relocated in an internal payload before
assembly, so its shading cannot rewrite the mod compiler's Minecraft-facing Gson APIs.
Night Config and SnakeYAML are relocated in the universal artifact. Minecraft, Bukkit,
Fabric and NeoForge APIs are supplied by the host, not embedded in the distribution.
The neutral core appears once. Each host selects its own existing entry point.

Catalog resources preserve both binary and recompiled vanilla snapshots and Bukkit's
API delivery variant. The catalog is reusable input; world-specific `retina.dat` remains
seed-bound and authoritative. Packaging does not expand Bukkit's API capabilities.

The assembly script has small standalone checks:
`python3 scripts/test-merge-universal.py`.
Actual three-loader acceptance and the release workflow are separate from assembly;
a successful ZIP build alone does not establish runtime compatibility.

## Generated vanilla data

`./gradlew :data:generateCatalogs --build-cache` initializes the actual Minecraft
registries on two Java-only tool classpaths: the binary runtime through Fabric,
and the recompiled runtime through common/NeoForm. It does not launch a server,
create a world, load a GPU, or compile Rust. Outputs live under
`data/build/generated/catalogs/<minecraft>/` and universal resource processing
consumes those task outputs directly. Explicit developer exports remain available.

Each JavaExec task tracks its actual exporter/runtime classpath, Java launcher,
Minecraft version, and execution options. Its relative output argument makes the
Gradle build-cache entry reusable across checkouts. The generated catalog and
manifest are restored together. A cache miss executes the exporter normally;
committed catalog assets are not a release fallback. Binary/recompiled snapshots
remain separate to preserve their exact noise offsets. Common's NeoForm runtime
explicitly enables recompilation, including when `CI=true`; ModDevGradle otherwise
skips it by default in CI. Export manifests record the actual observed RNG snapshot,
and final JAR inspection checks it against each catalog's resource location.

## Native release jobs and caching

The release workflow builds macOS ARM64, Windows x64 (static MSVC CRT), and Linux
x64 (the existing glibc 2.17 Zig target), using Rust 1.93.0. Native jobs run once per
target and upload libraries with content manifests. `native-release.py` fingerprints
Rust source/shaders, Cargo inputs, compiler configuration, build script, actual
toolchain versions, target, and relevant build environment. Exact native output
cache hits are accepted only after checking both current inputs and library bytes.
Cargo dependency/compilation caches accelerate misses but do not skip compilation.
Native cache identities contain no Minecraft version or Java release commit.

Assembly downloads these artifacts and supplies `-PretinaNativeDirectory=<directory>`.
With this property, native packaging reads supplied files and has no compiler task
dependency. The final JAR includes exactly the three release platforms and their
manifests. Ordinary loader developer builds retain their existing native setup.
Gradle's dependency and build caches cover Java/Minecraft tooling and generated data.

Blacksmith is the default provider. Set repository variable `RETINA_GITHUB_RUNNERS`
to `true` to use the corresponding GitHub-hosted runners if Blacksmith execution is
unavailable. This is an explicit provider selection, not a speculative runtime
availability check. The publish job uses a GitHub-hosted Linux runner.

After assembly, each native platform runs `UniversalNativeSmoke.java` against the
same actual JAR on a JDK-only classpath. It loads the embedded library, materializes
and reopens both vanilla snapshots, verifies dimensions/seed and unchanged saved
bytes, then deletes its temporary definitions. This is a CPU-side native loading
check; it does not claim GPU-generation or game-loader acceptance.
`UniversalCpuSmoke.java` additionally generates a few adjacent/End chunks through
the packaged library, exercises biome/height queries and lighting, then compares
reopen/reverse-order output for both snapshots. All native platforms run with all
graphics APIs disabled. Linux also runs `auto` in a JDK container without GPU
access. CPU opcode/shader-contract checks execute in the Linux native job. See
[CPU acceptance](CPU_ACCEPTANCE.md) for the commands and evidence boundaries.

## Publishing

Run `./gradlew publish` (or the compatibility alias `releaseRetina`). A publish-only
invocation selects a minimal Gradle build with no Minecraft plugins or toolchains.
It fetches remote branches, captures the fetched `origin/main` commit, and pushes
that commit to `origin/release` without force. The current branch, tracked edits,
and untracked files remain untouched. Initial branch creation works; an unchanged
release is a no-op; divergence is rejected by the real Git push.

Only a push to `release` starts the workflow. Every job checks out that event's SHA;
artifacts are named by SHA. A failed run can be rerun without another branch push.
`version.json` supplies major and minor as integers or integer strings, and full
Git history supplies the patch count: major 1, minor 1, 450 commits produces
`1.1.450`, tag `v1.1.450`, and title `Retina 1.1.450`.

Publication creates a draft, uploads the one universal JAR, then publishes it after
native checks pass. Notes use a “What's Changed” list with PR titles, author mentions,
and PR links, plus direct mainline commits and a Full Changelog link. Merge commits
represent their PR once. The first release links its complete history. Minecraft,
platforms, source commit, and artifact SHA-256 follow the change list.

A rerun verifies an already published tag and downloads its asset to compare actual
bytes; it never replaces a different published artifact. Draft recovery can replace
an incomplete upload. Each publisher reconciles Latest against the newest published
Retina commit count after writing, so a late older run cannot simply mark itself
Latest. Concurrent builds retain distinct artifact identities and do not cancel
one another merely because another release advances.

`python3 scripts/test-publish.py` exercises the actual Gradle task using disposable
local Git repositories, including remote advancement, dirty work preservation,
initial branch creation, no-op repeat, and rejected divergence.

## Installed-loader acceptance

`scripts/universal-loader-smoke.py` accepts an actual downloaded release JAR,
Java 25, preinstalled Fabric/NeoForge servers, Fabric API, a Bukkit server, and
`:bukkit:serverProbeJar`. Run it in disposable server directories. It creates and
reopens a small world on each loader, checks automatic Retina activation and saved
edits in all three dimensions, and checks the world definition remains unchanged.
The Bukkit observer verifies the actual plugin code-source hash. All installed
JAR hashes must match the release asset; native overrides are not used.

The observer is a separate developer test artifact and is never a release asset.
Commands, logs, definition hashes, and results are recorded under `--output`.
Delete these disposable worlds after recording results; retain compact evidence.
The mod checks use existing production QA hooks and stop the headless server after
the bounded save check. No GUI or client settings are involved.

## Executable headless JAR

The same universal JAR is also a Java 25 CLI. Its manifest enables native access;
no Minecraft installation, mod loader or GUI is needed. Loaders still select their
normal entry points. The native engine does the batch region generation and
file publication; Java does not transfer individual blocks.

```sh
java -jar retina-26.3-<version>.jar --help
java -jar retina-26.3-<version>.jar init --world ./world --seed 12345
java -jar retina-26.3-<version>.jar inspect --world ./world
java -jar retina-26.3-<version>.jar generate --world ./world \
  --dimension minecraft:the_end --region-radius 0
# Or create and generate in one command (only for a new definition):
java -jar retina-26.3-<version>.jar generate --world ./new-world --seed 12345 \
  --dimension minecraft:overworld --region-min=-1,0 --region-max=0,1
```

Bounds are inclusive region coordinates; a radius N selects a square of
(2N+1)² regions centered at 0,0. Each region is 32×32 chunks. Overworld output goes
to `region/`, Nether to `DIM-1/region/`, End to `DIM1/region/`, and custom dimensions
to `dimensions/<namespace>/<path>/region/`. `--compression zlib|lz4` controls new
records. Existing records are preserved byte-for-byte.

The default catalog snapshot is `vanilla-binary`. Creation also accepts
`--snapshot vanilla-recompiled`, `--catalog FILE`, and `--variant` for the catalog's
normal/structures/bonus-chest/Bukkit variants. A saved definition always wins:
resuming requires omitting creation options, so a different seed is never silently
accepted. `--definition FILE` can use an existing external definition with the
chosen output world; that definition must match existing output chunks.

Use offline worlds. The Java CLI holds Minecraft's actual `session.lock` while
writing, preventing another Java server or Java CLI from opening that world.
Do not concurrently run the standalone Rust CLI against the same output; its
separate locking protocol is unchanged. The command writes generation data and
MCA files, not `level.dat`, player data, or a fully initialized playable save.
Generation prefers a supported GPU and uses native CPU when none is available.
Set `RETINA_GPU_BACKEND=cpu` or `-Dretina.gpuBackend=cpu` for explicit CPU execution.
Initialization and inspection do not initialize a generation backend.
Ctrl-C/SIGTERM requests stopping after the active region and closes the definition.
Progress goes to stderr, final JSON to stdout; argument errors exit 2 and operation
failures exit 1. Signal exits retain the JVM/platform's signal exit status.

`./gradlew :core:cliTest` checks parsing, bounds and dimension paths without a GPU.
`scripts/test-universal-cli.py --jar <actual-jar> --java <java25> --output <logs>`
checks real native generation, reopening/preservation, output locks and graceful
cancellation in a disposable world (at most four generated regions), then removes
that world. Release jobs also launch `java -jar ... --help` on all three platforms.

## Startup diagnostics

Concise preparation progress is enabled by default. Set `debug = true` in the
mod configuration, or `debug: true` in Bukkit configuration, and restart for the
detailed stages described below. Prism's console captures native stderr lines
prefixed `[Retina startup]`; mod adapter/storage messages also use the normal
Minecraft logger. No Java debugging tools are required.

Native stages have a numbered `BEGIN` and `END` with elapsed seconds and thread
name. Logs identify instance creation, adapter selection (backend, device and
driver), device creation, individual shader modules and compute pipelines,
world-profile registration, and the first terrain query/region operation. Lazy
world-pipeline and lighting compilation is logged too, so progress remains
visible after Minecraft announces spawn selection. `END` means the call exited;
errors still follow the existing error path, and panic exits say `UNWIND`.

While stages remain active, an independent reporter prints their `WAIT` messages
every ten seconds with elapsed time. These indicate an unfinished operation, not
an ETA or proof that the driver is making progress. The reporter stops when no
stages remain; it never times out, cancels or changes the actual operation. Shader
sources, seed-dependent profile contents and per-chunk steady-state activity are
not dumped. A stalled startup can be reported by copying the last startup lines
from the launcher console, including the selected adapter and unfinished stages.

### Selecting the compute backend

Retina bundles Metal, Vulkan and DirectX 12 support. To test a particular backend
in Prism, add one JVM argument to the instance's Java settings:

```text
-Dretina.gpuBackend=dx12
```

Use `vulkan`, `metal`, or `auto` in place of `dx12` for those choices; remove the
argument to restore automatic selection. This works in Fabric, NeoForge, Bukkit,
and the executable JAR. Native CLI users can set `RETINA_GPU_BACKEND=dx12`.
An explicit JVM property takes precedence over that environment variable.
The startup log reports the requested backend and actual selected adapter.
An explicitly requested backend is attempted directly and reports adapter/device
creation errors if unavailable; Retina does not silently choose another backend.
OpenGL/OpenGL ES is not compiled into this distribution.

On Vulkan, automatic execution prepares its fallback pipelines before enqueueing
specialized compilation for that profile. Explicit interpreter/specialized modes
retain their meanings. `RETINA_DEFER_SPECIALIZATION=0` restores overlapping
compilation for comparison; `1` enables fallback-first scheduling on other
backends for validation. This ordering does not guarantee faster driver compilation
and does not create a persistent disk shader cache.

### Windows pipeline diagnostics and standalone probe

Pipeline creation now reports wgpu's internal phases using its existing profiler
hooks: SPIR-V emission, HLSL emission, FXC/DXC compilation, and Vulkan/DX12 driver
pipeline creation. These are real nested operations; shader code and compiler
options are unchanged. If a wait occurs between instrumented phases, the enclosing
pipeline stage remains visible. The observer is thread-local to pipeline creation
and does not replace a host application's global tracing subscriber.

On Windows, `WAIT` lines include `thread_id` and cumulative `thread_cpu_ms` since
that stage began. This measures the calling thread's kernel plus user CPU time;
it does not count work delegated to internal compiler/driver worker threads. A
flat counter is evidence that this thread is not consuming CPU, not proof of a
deadlock. Counter lookup failures print `unavailable` and never stop generation.
No threads are suspended, stack dumps collected, or shader source printed.

The developer probe is not included in regular releases. Build it on Windows
with `python scripts/build-gpu-probe.py` and the MSVC toolchain, then place
`retina-gpu-probe-windows-x64.exe` in a writable folder, double-click, and choose
Vulkan or DirectX 12. It launches a native child
process running the same Retina engine without Java/Minecraft. A uniquely named
log beside the executable updates while the probe runs. Ctrl+C stops the child;
the window remains open after a completed or failed test. No debugger is required.

Double-clicking alone exercises initial pipelines (including `cave_nodes`). To
also reproduce world-specific pipeline creation, drag a world's `retina.dat`
onto the executable. It reads the definition and generates Overworld chunk
`-3,-3` in memory; it does not write chunks or change the source world.
For automated testing, `--worker <auto|vulkan|dx12|metal> [retina.dat]` runs directly
with output on stdout/stderr and no prompt. The probe is diagnostic, not a startup
speed fix; its Windows GPU result still needs to be compared with the mod's log.

### Bundled Windows x64 DXC

Windows x64 builds link DXC statically and explicitly select it for DX12. This
avoids falling back to the system FXC compiler, which the reported RTX 3060 log
showed consuming 87 seconds of calling-thread CPU time in a 90-second pipeline
creation wait. No extra compiler DLLs need to be installed or copied by players.
The log reports `DX12 shader compiler: bundled static DXC` and the inner compiler
phase should be `compile_dxc`. Keep `-Dretina.gpuBackend=dx12` for the comparison.
Vulkan, Metal, shader arithmetic, and optimization settings are unchanged. This
addresses the observed DX12 compiler path; it does not establish the cause of the
separate Vulkan delay. Windows runtime performance still needs to be measured.

The Mac Windows-x64 cross-build includes ATL for DXC's static library. If an older
cargo-xwin SDK cache produces an actual `atls.lib` linker error, refresh it with
`cargo xwin cache xwin --xwin-include-atl --update`, then rerun the build.
