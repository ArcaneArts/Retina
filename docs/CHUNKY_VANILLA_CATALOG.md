# Chunky and the bundled vanilla definition

On Minecraft 26.3, these exact official artifacts can retain the bundled vanilla
catalog optimization:

| Loader | Chunky | SHA-256 of original JAR |
| --- | --- | --- |
| Fabric | 1.5.3 | `ecdfc55a0f67f3ec6f40850687615c941ae226bd88f4e06188a7b268fd3da942` |
| NeoForge | 1.5.4 | `6ed601bd2c2942cd537f1732805bc786e70b57d988039c416c3bc27ba97162b9` |

The files are published by [Chunky's author on Modrinth](https://modrinth.com/plugin/chunky/versions).
The Fabric artifact is version `4Eotm6ov`, NeoForge `EyCqftOK`. Their downloaded
bytes were also checked against the publisher's SHA-512 metadata. Chunky is not
bundled with Retina and is not a dependency.

Retina checks the actual loader origin, Minecraft version, mod ID/version and
artifact bytes once per running process. A matching file can use the catalog
only if every other loaded mod and enabled pack also passes the existing rules,
and the selected world has normal vanilla dimensions/options. Unknown releases,
repacked JARs, development directories, additional content mods, custom datapacks
and unknown combinations continue through live registry export. An unreadable
origin declines this optimization and leaves normal export available. No new
startup gate prevents the actual generation path from running.

Adding a future version requires checking that artifact and its initialization,
not just changing a version string. Review entrypoints, mixins, registry hooks
and resources, then run the initialized-loader parity checks. The audited
artifacts' entrypoints register commands and lifecycle/task handling; their
mixins expose chunk/server operations and flushing. The exact entrypoint/mixin
bytecode was inspected in addition to the upstream source. The full live data
comparison below is the decisive check of registry neutrality.

## Headless validation

Place the official Chunky JAR in a fresh server run directory's `mods/`, accept
the EULA there, and use an ordinary seed/options. The existing Gradle development
server tasks initialize the real loader and Chunky without opening a client:

```sh
./gradlew :fabric:runServer --no-watch-fs \
  -PretinaDatapackQa=/path/to/fresh-fabric-server \
  -PretinaNativePath=/path/to/native-library \
  -PretinaQa=true -PretinaAutomaticQa=true -PretinaEndActivationQa=true \
  -PretinaCatalogCompareQa=true

./gradlew :neoforge:runNeoForgeServer --no-watch-fs \
  -PretinaDatapackQa=/path/to/fresh-neoforge-server \
  -PretinaNativePath=/path/to/native-library \
  -PretinaQa=true -PretinaAutomaticQa=true -PretinaEndActivationQa=true \
  -PretinaCatalogCompareQa=true
```

The QA-only compare writes a separate `world/retina-qa-live/retina.dat`, compares
the complete NBT definition against `world/retina.dat`, and emits
`vanilla_catalog_live_parity: pass`. Only root `exporter` and `datapacks`
provenance fields are excluded: seed, options, palettes, programs, all dimensions,
templates and every other field remain compared. It does not affect ordinary
startup. The automatic checks exercise generation, edits in all three dimensions,
save and drained shutdown. For terrain parity, feed each of the two files to
`scripts/native-region-benchmark.py` with identical coordinates and use `--compare`
against the first output. Use a fresh directory for each create test.

On October 31 both real loader runs passed the full NBT comparison with the
artifacts above, generated all three dimensions, saved edits and shut down after
draining. Artifact policy tests cover the original bytes, modified copies,
incorrect versions/Minecraft targets, missing/multiple/directory origins and
unknown IDs. Existing world-selection tests retain custom-pack/custom-world
fallback coverage. This removes unnecessary live export from eligible Chunky
startup; it does not itself shorten Vulkan driver compilation.

Twelve fresh native processes also generated one measured full region each from
the paired files: bundled/live × Overworld/Nether/End × Fabric/NeoForge. All six
pairs passed strict structural comparison of their 1,024 chunks (6,144 compared
chunks total), at negative region coordinates after independent warmups. The
benchmark now accepts the End's successful no-registry-program readiness state;
it continues to require shader readiness for registry-driven terrain.
