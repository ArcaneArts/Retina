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
