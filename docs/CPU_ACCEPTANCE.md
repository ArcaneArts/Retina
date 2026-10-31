# Headless CPU acceptance

All checks use commands, JDK-only fixtures or dedicated servers. No client or GUI
is launched and no audio/accessibility settings are touched. Use disposable
directories belonging to the test, retain compact logs/hashes/results, then remove
their generated worlds/regions. Preserve reference/user worlds and other agents'
evidence.

## Packaged native checks

The release workflow executes the following against the same assembled JAR on
Windows x64, macOS ARM64 and Linux x64:

```sh
java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -Xmx768m \
  -cp dist/retina-26.3-<version>.jar scripts/UniversalCpuSmoke.java 26.3 disabled
```

This sets all three API permissions false while leaving the backend override on
`auto`. It loads the embedded native image, materializes both actual packaged
catalog snapshots with a negative 64-bit seed, generates two adjacent Overworld
and Nether chunks plus central/outer End chunks, checks storage NBT, biome/height
queries and empty-volume lighting, and verifies zero GPU jobs. Lighting expectations
come from each saved dimension's actual skylight flag, not a dimension-name guess.

Reopening binds dimensions and requests chunks in reverse order, requiring exact
CPU output and unchanged authoritative definition bytes. Every temporary definition
is deleted after extracting compact hashes. The helper prints actual JAR and native
image SHA-256 plus backend/OS/architecture markers. A local classpath resource
fixture is labeled `classpath-fixture`; it is not final release acceptance.

Linux additionally runs the same JAR with all graphics APIs permitted in a JDK
container without GPU devices or an installed graphics stack:

```sh
docker run --rm --network none -e RETINA_REGION_THREADS=4 \
  -v "$PWD:/retina:ro" -w /retina eclipse-temurin:25-jdk-noble \
  java --enable-native-access=ALL-UNNAMED -Djava.awt.headless=true -Xmx768m \
  -cp dist/retina-26.3-<version>.jar scripts/UniversalCpuSmoke.java 26.3 auto
```

`auto` actually attempts permitted adapter/device initialization, then requires a
CPU result. Forced CPU alone is not proof of this entry condition. No software
graphics renderer is installed or selected for this test. The image follows the
[official Adoptium JDK recipe](https://github.com/adoptium/containers/blob/main/25/jdk/ubuntu/noble/Dockerfile).
Blacksmith supports [cached Docker images](https://docs.blacksmith.sh/blacksmith-caching/docker-container-caching);
the existing GitHub runner fallback also supports the command.

Linux also runs `cargo test ... cpu::` and `program::opcode::tests`, including the
actual shader-switch coverage contract. Its compilation/dependency cache restores
are accelerators; tests execute regardless of cache hits. Exact native-output reuse
retains the existing complete input/byte verification. No release triggers, assets,
versioning or publish permissions change.

## Installed adapters

Use `scripts/universal-loader-smoke.py --cpu` with the downloaded published JAR,
the normal probe and preinstalled dedicated Fabric/NeoForge/Bukkit server binaries.
The script's `--help` lists paths. Stage binaries/libraries into this goal's own
server directories; do not overwrite unrelated installations or worlds.

The driver writes actual all-false adapter configs and keeps the explicit backend
override on `auto`. It requires a real CPU selection log, generation/adapter/save
events for all dimensions, the installed artifact hash, persisted edits, unchanged
definitions and saved-file reopen without materializing/recompiling a replacement.
This checks each adapter's existing delivery capabilities, including Bukkit limits.

After collecting results, remove only those disposable world directories. Keep
logs, command JSON, artifact/definition hashes and result JSON.

## Final acceptance

Run the executable JAR and standalone Rust CLI on CPU, preserving existing records
on reopen and retaining meaningful error/cancellation behavior. Measure bounded
all-dimension cold/warm generation, thread scaling, memory and reopen costs. Keep
diagnostic stage sums separate from elapsed latency and identify any contention.
Native-loading/CPU checks do not establish GPU execution. Hardware regressions
are separately recorded on real supported hardware.

After implementation PRs merge, use the real Git-only `./gradlew publish`, follow
Actions publication, download its JAR and verify identity. Use that artifact for
adapter/CLI acceptance. A bounded rerun of the same workflow can establish warm
cache reuse and immutable release/asset identity without artificial commits.
