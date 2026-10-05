# Registered surface-relative placement filters

`surface_relative_threshold_filter` now retains the loaded heightmap and optional
minimum/maximum offsets in top-level and nested patch placement programs. Rust
reads that heightmap from the GPU material runs plus earlier ordered feature
writes. The filter includes both limits, uses 64-bit arithmetic for offset sums,
and keeps the candidate's position unchanged. It needs no new spatial sampling,
GPU dispatch, readback or timing stage.

This follows Minecraft 26.3's installed `SurfaceRelativeThresholdFilter` source.
The codec permits reversed limits; those reject every candidate. Omitted limits
retain `Integer.MIN_VALUE` / `Integer.MAX_VALUE`. All six heightmap kinds are
supported, including underwater floor and motion-blocking semantics.

The reference harness loads every relevant registry modifier and adds exact,
one-sided, reversed and extreme-offset cases on eight controlled terrain
fixtures. All 50,688 vanilla and 115,200 Terralith placement cases match Minecraft,
including 72,058 rejections. Existing feature references, forced cave/surface
region boundaries, final heightmaps, paired plants and native unit tests pass.
The release build and `blockFeatureTest` log are
`build/surface-relative-validation.log`.

The complete exported vanilla and Terralith profiles are byte-for-byte equivalent
as JSON values to the patch milestone profiles. In these loaded registries, the
new filter reaches recipes whose geometry is still unsupported, such as geodes,
multiface growth and underwater magma; their specific omissions remain visible.
The capability does not invent replacement geometry or change existing recipe
budgets. Other filters and spatial block-state providers remain required.

Twenty-region serial checks for each full profile match all 40,960 decompressed
NBT records from the retained patch milestone. Libraries and results are under
`build/goal-baseline/surface-relative/`. Performance figures from those runs are
not used to claim a gain or regression: an unrelated Java process consumed over
five CPU cores, and a retained-library control also slowed substantially. This
is output-compatibility evidence, not a controlled performance comparison.

The tested native and bundled JAR library SHA-256 is
`68c1f493e8c761c2e2a88789fedf6b71d1a1b8be53661ddf40ed61458e961d8b`.

Later [cuboid placement support](REGISTERED_CUBOID_PLACEMENTS.md) extends these
programs with loaded inclusive dimensions and face/edge/interior selection. It
also retains the same heightmap filters after cuboid expansion, with full
Minecraft reference coverage. Other enclosing feature geometry remains required.
