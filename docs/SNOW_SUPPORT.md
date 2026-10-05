# Registered snow support

The final Rust snow pass now uses a palette-aligned support table exported from
the loaded Minecraft block states. It shares the predicate used by registered
snow-layer decoration placement: `CANNOT_SUPPORT_SNOW_LAYER` takes precedence,
then `SUPPORT_OVERRIDE_SNOW_LAYER`, a full upper collision face, or eight snow
layers permits support. Regular freezing ice is excluded by the loaded tag.
Datapack tag changes and registered state shapes participate in the same export.

The earlier final pass only checked heightmap solidity and excluded water/lava.
Ice satisfies that heightmap predicate, so freezing an ocean followed by the
final snow pass covered the ice with an unsupported snow layer. The new pass
checks actual support before placing snow, after vegetation and structures, in
both individual chunks and MCA assembly. Frozen water remains ice; nearby cold
land and supported tree crowns still receive snow. Occupied space, warm columns
and synthetic lake columns retain their existing handling.

The small boolean table uploads with the profile once and remains in Rust. It
adds no spatial CPU noise, GPU dispatch or GPU readback. Older standalone JSON
profiles without the table still parse and explicitly exclude their normal
freezing-ice material. They retain their other legacy support assumptions;
runtime registry exports provide the complete table after a client restart.
Existing saved chunks keep their blocks. The correction applies to newly
generated terrain.

Support shapes use the existing export-time empty block getter. This retains
state geometry and support tags, but does not simulate neighbor-dependent custom
collision shapes or replace the generator's current GPU temperature approximation.

The original `snowTest` compared all 201 fixture palette states against Minecraft 26.3's actual
`SnowLayerBlock.canSurvive`, then generates cold land and frozen ocean with the
real GPU/native paths. Ocean output contains 256 exposed ice blocks and no snow
layers; land contains 256 snow layers. It compares 196,608 decoded MCA blocks
with individual-chunk output at negative coordinates. The same harness with the
retained previous native library fails at the ocean snow assertion, reproducing
the reported defect. The later nullable-provider validation reruns this oracle over 483 loaded
fixture states with the same bare-ice, snowy-land and MCA/chunk results. Native
tests also exercise support overrides, unsupported
materials, tree leaves, warm/lake columns, occupied space and old profile parsing.

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./gradlew build snowTest --no-parallel --max-workers=1 \
  -PcargoExecutable=/Users/cyberpwn/.cargo/bin/cargo
```

`snowTest` is included in `gpuTest`. Local evidence is retained in
`build/snow-support-validation.log` and `build/snow-support-before.log`.
