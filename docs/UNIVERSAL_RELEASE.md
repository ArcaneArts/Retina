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
remain separate to preserve their exact noise offsets.

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
