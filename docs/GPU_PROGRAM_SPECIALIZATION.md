# Registered GPU program specialization

Rust specializes the validated resident density/material DAG into profile-specific
WGSL. The opcode expressions come from the interpreter, so both paths share noise
and rule calculations. Parameters, octave data, spline knots and world data remain
in resident buffers. Constant expressions are folded, unreachable expressions are
omitted, identical generated graph bodies share functions, and pure conditional
arms evaluate lazily with scope-aware dependency reuse.

The original interpreter's 1,024-value private scratch array is replaced by
scalar SSA values and small spline knot arrays. A runtime integer identity keeps
each node's f32 boundary intact: removing it let the Metal compiler contract or
propagate arithmetic across nodes and changed some biome decisions. Exact root-bit
checks and whole-MCA comparisons verify the retained implementation.

## Horizontal field reuse

The first three registered graphs are analyzed for coordinate dependencies.
Expensive X/Z-only subgraphs entering Y-dependent calculations, and horizontal
climate roots, become cached fields. Semantically identical nodes are interned
across graphs. Spline locations, derivatives and child expressions participate in
identity; the location of a spline in the point table does not. Context-dependent
material operations and unknown operations are conservatively excluded.

A GPU prepass computes those fields once at each global density X/Z grid point.
The values occupy additional space in the existing GPU-only density/surface/lake
scratch buffer. Subsequent climate and density layers read those values instead
of recalculating noise and nested splines. Vanilla selects 9 fields; the actual
Terralith profile selects 23. Height/climate device timing includes this prepass.

Queries outside the grid or between its points evaluate the same expression on
the GPU. A reuse also requires exact equality with the stored point's converted
world coordinates. Checking the relative grid index alone was insufficient for
odd spacing beyond f32's exact integer range; a far-coordinate test reproduced
158 root-bit mismatches before this check and zero afterward. Values are never
clamped to tile edges or approximated with a different noise implementation.

The added scratch stays on the device. There is no additional density/cache
readback, input upload or CPU noise simulation. Each submission chooses one
complete execution path before calculating scratch addresses; a compilation
finishing midway through a batch cannot change that batch's layout or pipelines.

## Compilation and diagnostics

A single compiler thread builds world and cave pipelines in the background.
Generated source is the cache key, with exact string equality preventing hash
collisions from reusing unrelated code. The material-layer entrypoint flag is part
of that identity, so retained profiles avoid compiling unused material pipelines.
Compatible profiles share pipelines while
binding their own resident inputs. Compilation errors are caught and logged.

Normal `program_execution: "auto"` jobs use the GPU interpreter while compilation
is pending or failed. They keep generating terrain. Diagnostic `"interpreter"`
disables specialization; `"specialized"` waits and returns a real compilation
error instead of silently benchmarking the interpreter. These are native profile
diagnostics, not changes to saved world configuration.

F3 reports execution state, compilation time, registered/emitted node counts,
horizontal field count and cumulative actual upload/readback bytes for the profile.
The separate 64-byte diagnostics ABI leaves the request ABI unchanged.
Subsequent material and aquifer milestones extend the native timing snapshot
to version 3 with 23 stages. Network round-trip and display tests cover the added information.

Cold automatic-mode checks use real exported profiles with identity clamps
appended to the roots, forcing new shader code without changing terrain. Both
generated 20 regions before compilation completed. First native region latency
was 146 ms vanilla / 172 ms Terralith while compilation took 12.2 / 50.7 seconds in
the background. All 20,480 measured NBT records matched the reference; a region
regenerated after specialization also matched all 1,024 records. Java registry
export and profile registration are separate from those first-region times.

## Validation and remaining work

Real GPU root checks compare all six outputs of every graph bit-for-bit with the
interpreter, varying seeds, Y levels and material contexts. Cache-mode checks
cover aligned nodes, fractional/outside queries, separate request seeds, negative
coordinates, far coordinates and odd grid spacing. Unit checks cover dependency
scopes, dead noise, shared horizontal graphs and spline identity.

Matched production benchmarks include actual structures and decorations, 20
regions, five warmups and one/two callers. They compare every decompressed chunk
NBT record with the pre-specialization reference. Measurements and startup-check
artifacts are retained locally under `build/goal-baseline/`.

Controlled Apple M4 Max runs before the final far-coordinate cache guard measured:

| Profile / callers | Interpreter chunks/s | Specialized + column cache chunks/s | Mean request ms, before → after |
| --- | ---: | ---: | ---: |
| Vanilla / 1 | 7,522 | 9,693 | 135.82 → 105.35 |
| Terralith / 1 | 6,920 | 9,362 | 147.72 → 109.09 |
| Vanilla / 2 | 8,687 | 10,592 | 226.71 → 191.68 |
| Terralith / 2 | 8,264 | 12,324 | 246.93 → 165.62 |

These are native generation rates, excluding game loading, lighting and rendering.
Every candidate matched all 20,480 reference NBT records. File sizes stayed at
137,617,408 bytes vanilla / 113,623,040 bytes Terralith. Serial peak RSS increased
from 820 to 899 MiB vanilla and 918 to 1,438 MiB Terralith, including compiled
pipelines and compiler memory. Added cache fields stay on the GPU: measured
transfers were about 3.3 / 7.5 KB uploaded and 20.0 / 20.1 MB read back per region.
The final guard is covered by exact far-coordinate and integration checks.
Later performance repeats overlapped an active game client and are excluded from
speedup claims; the table is not a measured speedup for that final guard revision.

Specialization preserves the exported graph's current semantics. Registered
`slice` operations now retain their coordinate scopes; see
[coordinate scopes](GPU_COORDINATE_SCOPES.md). Per-expression interpolation
wrappers remain flattened into the shared final-density lattice approximation.
Complete [material layers](GPU_MATERIAL_LAYERS.md) now use the same specialization
path. Their first Terralith compilation measured about 200–208 seconds; automatic
mode continues generating with the interpreter but runs more slowly until it is
ready. Reducing that compilation and interpreter cost remains required, alongside
broader feature recipes and remaining density semantics.
[Local aquifer passes](GPU_AQUIFERS.md) now use direct specialized graph calls,
keeping material-dispatch branches out of their field and pressure shaders.

## Rejected material compiler experiments

Two release experiments used the current full profiles (56 / 151 biomes,
170 / 463 decoration recipes and 30 / 58 structure definitions). Both kept
the original output, but neither demonstrated a reliable startup improvement.
Their production changes were removed and the packaged library was restored.

Leaving nonzero material constants in resident bytecode instead of folding them
into WGSL did not reduce vanilla's 31 unique graph bodies; Terralith dropped from
104 to 101. Generated source grew from 1,174,728 to 1,248,768 bytes vanilla and
6,035,772 to 6,392,072 bytes Terralith. The candidate took 5.35 / 72.05 seconds to
compile, compared with 13.34 / 53.00 seconds in the recent original-source runs.
These are different cold shader identities and single observations, not an
isolated compilation-speed comparison. Twenty full regions per profile matched
all 40,960 decompressed reference chunk records. The small function-count change
and larger source did not justify retaining this variant.

Grouping every equivalent material dispatch label into one switch arm also
retained all original program IDs and salts. Two pairs of identity-clamped
profiles forced new shader code without changing terrain. Order was reversed
in the second pair; each run generated twenty full regions.

| Profile / cold variant | Run order | Original compilation seconds | Grouped compilation seconds |
| --- | --- | ---: | ---: |
| Vanilla / 1 | Original, grouped | 14.49 | 5.31 |
| Vanilla / 2 | Grouped, original | 4.92 | 16.43 |
| Terralith / 1 | Original, grouped | 80.34 | 59.26 |
| Terralith / 2 | Grouped, original | 64.91 | 84.04 |

The apparent win reversed with execution order. Only material dispatch changed;
the shared density entrypoints had the same identity within each pair. The first
process paid for those common cold shaders, and the second could reuse the
driver's cache. Inspecting the installed wgpu 30 Metal backend confirms that it
translates/compiles by selected entrypoint. Comparing one process's total cold
compilation with a later process therefore confounds shared shader-cache warmth
with the proposed material change. No consistent warmed region improvement was
observed either. All 163,840 measured chunk records from these eight runs matched
the unchanged reference. User applications remained active; no builds/tests ran
concurrently with the benchmarks.

Evidence and retained experimental libraries/sources are under
`build/goal-baseline/material-code-sharing/` and
`build/goal-baseline/material-dispatch-grouping/`. The restored `build` passes,
and both the release dylib and packaged JAR contain native SHA-256
`57a6a5cc61d6ec2cebf23701f76fbdfa4c679df46e3723de460876c3c47819b1`.

## Interpreter value pressure

`scripts/native-program-register-pressure.py` models the actual eager bytecode
interpreter's dependencies, including spline child references, unused
instructions and implicit root-zero outputs. It constructs a conservative
lowest-free-slot allocation: operands stay live through the output write, and
outputs stay live until the final root reads. It checks that no dependency or
output root has been overwritten. This is a standalone diagnostic; it does not
gate generation or modify a profile.

The full vanilla profile has 4,965 instructions across its programs and needs at
most 37 simultaneous scratch values. Terralith has 26,283 instructions and needs
at most 64. Their largest individual programs have hundreds of instructions;
allocating one scratch value for every instruction needlessly requires the
current fixed 1,024-value array. These counts describe bytecode values, not the
backend's total physical registers or helper-function temporaries.

```sh
python3 scripts/native-program-register-pressure.py \
  build/goal-baseline/shore-width/vanilla.json \
  build/goal-baseline/shore-width/terralith.json
```

`--include-slots` prints the proposed per-instruction slot maps. Captured reports
are in `build/goal-baseline/material-dispatch-grouping/register-pressure.json`.
The next implementation target is a native liveness allocator and a compact
GPU interpreter, with a full-capacity path for valid profiles that need more
slots. This has not been integrated or benchmarked yet; no improvement from
register reuse is claimed. Cold automatic-mode generation still needs work.
