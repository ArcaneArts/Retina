# Registered cuboid placements

Retina now exports Minecraft 26.3's `cuboid` placement modifier into top-level
native placement programs and supported nested vegetation-patch programs. Rust
samples the loaded vertical size first, then the two independent horizontal
sizes, and emits positions in X/Y/Z loop order. Dimensions retain the game's
inclusive endpoints and codec range of 1–16: size 16 emits 17 positions per axis,
up to 4,913 candidates. Loaded integer providers supply the dimensions.

`include_edges` and `include_interior` both default to true. An edge lies on at
least two boundary planes; disabling edges keeps face interiors. Disabling the
interior keeps boundary faces. Both false retain the face interiors without the
edges or enclosed volume. Neither flag changes dimension sampling.

One allocation-free offset iterator serves both placement paths. Top-level
expansion keeps Retina's existing independent stream per emitted branch and
ordered command replay. Nested feature replay shares the parent's random stream,
matching Minecraft's downstream chance, offset and block-provider draws. This
does not establish Minecraft seed identity for top-level stochastic branches.

Cuboid geometry stays in parallel Rust; it adds no GPU dispatch, query, transfer
or timing category of its own. Downstream spatial counts/providers continue
using the resident GPU samplers, and predicates use the carved GPU substrate plus
earlier feature writes. Its CPU work is included in plant planning. Enclosing
patches retain the existing complete substrate footprint and halo rules.

The exporter adds the horizontal cuboid extent when checking nested geometry.
Recipes exceeding the existing supported patch halo remain explicitly reported
as `vegetation_patch:geometry_exceeds_halo`; their dimensions are not shortened.
Coral tree/claw geometry and sequence/overlay recipes remain unsupported. Cuboid
support alone therefore does not implement the game's coral reef recipe.

## Diagnostic result sizing

The valid 4,913-candidate maximum exceeded the old 4,096-record diagnostic buffer.
Both native decoration sample entry points now complete the actual sample and
return status 1 with its required length if the caller's buffer is too small,
without copying partial records. The Java bridge allocates that size and retries
the same deterministic sample. Status 0 remains success and -1 remains an error.
Symbol names and record layouts are unchanged; external diagnostic callers must
handle the new positive resize status. Production chunk/MCA generation has no
new retry or sizing pass, and timing ABI 7 is unchanged.

## Validation

The harness compares positions with Minecraft's actual `CuboidPlacement` codec
and implementation. It adds 49 programs covering six dimension configurations,
all four flag combinations, following ocean-floor heightmaps and omitted flags
at maximum size. Across eight terrain fixtures and 64 streams, all 50,176 added
cases pass. The complete placement suite passes 238,080 vanilla/Terralith cases,
including negative coordinates, chunk edges, carved substrates and build bounds.

Eight additional nested patch fixtures use Minecraft's actual feature code and
the production exporter. Chance filters, offsets, weighted log providers and
randomized column heights expose draw-order errors and overlapping writes.
They pass 6,144 added feature cases across both stacks: flat ground, underwater
substrates, reduced build heights, carved cave floors/ceilings and rugged terrain.
The dedicated cave/rugged subset has 1,024 cases per stack, placing 21,886 floor
and 21,732 ceiling log states per stack. The full provider suite now compares
41,984 cases over 78 features per stack, plus 8,064 targeted property-copy cases.

The build passes with 54 native unit tests, three real-GPU chunk tests, actual
Minecraft feature references, forced MCA/per-chunk parity and final heightmaps.
DH temporary regions, promotion, concurrent edits, save isolation, serialization
and frozen-ocean snow checks also pass. All 1,024 slots are decoded in each
forced-region check; twelve independent chunks compare final blocks and all six
heightmaps. Commands used:

```sh
./gradlew build blockFeatureTest nativeGpuTest previewTest regionTest snowTest \
  -PtestPack=/Users/cyberpwn/Downloads/Terralith_v2.6.5+26.3.zip \
  --no-parallel --max-workers=1
```

Logs: `build/cuboid-placement-validation.log` and
`build/cuboid-placement-integration.log`. Compilation ran at nice 10 with two
Cargo jobs/code-generation units; runtime workers were unchanged. Tested release
and bundled macOS native SHA-256:
`6e9758da7108c728e7bd2c500a89c0c0bd33396d0ed26cb8f0049e2d59f505d5`.

## Whole-profile compatibility measurements

Eight sequential Apple M4 Max / Metal processes compared the retained `fcb2eac`
library with this change, seed 123456789, two warmups followed by twenty measured
regions. Vanilla ran before/after and Terralith after/before, with one and two
callers. Actual full profiles remain identical as JSON values: vanilla has 56
biomes, 172 decorations and 2,156 material states; Terralith has 151, 510 and
3,673. Existing unsupported enclosing feature geometry prevents these profiles
from exercising newly accepted cuboids; reference fixtures exercise them instead.
These are production compatibility measurements, not estimates of new recipe cost.

| Profile / callers | Before mean region ms | After mean region ms | Before chunks/s | After chunks/s | Before / after peak RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / 1 | 180.23 | 177.60 | 5,672 | 5,757 | 1,067 / 1,008 |
| Vanilla / 2 | 293.67 | 298.80 | 6,954 | 6,835 | 1,050 / 1,080 |
| Terralith / 1 | 241.76 | 240.46 | 4,231 | 4,254 | 1,542 / 1,525 |
| Terralith / 2 | 394.75 | 385.94 | 5,001 | 5,177 | 1,552 / 1,611 |

Concurrent throughput uses total batch time; region latency includes overlap.
Single pairs on a shared host establish no reliable improvement or regression.
RSS includes the driver/compiler, retained buffers and comparison reader.

| Run | Initialize ms | Register ms | First warmup ms |
| --- | ---: | ---: | ---: |
| Vanilla / 1 before | 52.1 | 584.1 | 787.5 |
| Vanilla / 1 after | 55.4 | 604.8 | 796.8 |
| Vanilla / 2 before | 51.0 | 606.3 | 813.0 |
| Vanilla / 2 after | 53.0 | 595.1 | 801.0 |
| Terralith / 1 before | 57.2 | 1,186.2 | 3,150.6 |
| Terralith / 1 after | 54.1 | 1,201.6 | 3,064.8 |
| Terralith / 2 before | 53.3 | 1,213.5 | 3,082.1 |
| Terralith / 2 after | 54.3 | 1,179.0 | 3,141.7 |

Mixed specialization was forced ready, with interpreted terrain and composition
disabled. First warmup includes specialized shader compilation; initialization
benefits from shared driver caches. These do not establish a cold-start improvement.

All 163,840 decompressed NBT records match the retained serial baseline, including
concurrent request orderings. Twenty-region files remain exactly 153,845,760 bytes
for vanilla and 130,899,968 for Terralith. Per-region transfers stay approximately
14.295 MB upload / 45.622–45.623 MB readback vanilla and 12.613–12.615 MB upload /
41.939–41.955 MB readback Terralith. Retained libraries, actual profiles,
reproduction script, measurements and hashes are under ignored
`build/goal-baseline/cuboid-placement/`, including `manifest.json`.

Broader nullable provider transformations, additional feature geometry and the
production density-composition/startup work remain open in the six-workstream goal.
