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
normal entry points. The native GPU engine does the batch region generation and
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
GPU generation requires a supported device/driver; init and inspect are CPU-only.
Ctrl-C/SIGTERM requests stopping after the active region and closes the definition.
Progress goes to stderr, final JSON to stdout; argument errors exit 2 and operation
failures exit 1. Signal exits retain the JVM/platform's signal exit status.

`./gradlew :core:cliTest` checks parsing, bounds and dimension paths without a GPU.
`scripts/test-universal-cli.py --jar <actual-jar> --java <java25> --output <logs>`
checks real native generation, reopening/preservation, output locks and graceful
cancellation in a disposable world (at most four generated regions), then removes
that world. Release jobs also launch `java -jar ... --help` on all three platforms.

## Startup diagnostics

Startup logging is enabled by default in all hosts, including release JARs.
Prism's console captures the native stderr lines prefixed `[Retina startup]`;
mod adapter/storage messages also use the normal Minecraft logger. No Java debug
commands, environment variables or debug setting are required.

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
