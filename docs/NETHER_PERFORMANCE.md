# Nether throughput and memory

Two repeated native Nether workloads on 2026-10-20 generated identical complete
chunk NBT at 91.6 and 90.9 chunks/s. This establishes a baseline for the delivered
Nether implementation. It does not measure an optimization against an earlier
Nether build.

## Workload and completion boundary

Each separate process generated five warmup regions, followed by twenty adjacent
new regions (20,480 measured chunks), with one concurrent caller. The seed was
123456789; measured region coordinates were X=-2 through 2 and Z=-2 through 1.
Warmups used X=8 through 12, Z=8. All output paths were new private directories.

The profile came from Minecraft's loaded Normal Nether definition: all five
biomes, 39 ordered decoration roots, enabled structures, full storage `[0,256)`,
noise terrain `[0,128)`, lava, no skylight and the ordinary ceiling. Lighting and
zlib MCA encoding were enabled. Normal GPU program selection and driver-warm
readiness were used, without forced shader or worker overrides.

The measured wall interval covers native generation, features and structures,
lighting, NBT, compression and durable region publication. It excludes native
initialization, profile registration, the five warmups and subsequent corpus
validation. It also excludes Java activation, Minecraft loading, portal travel,
rendering and optional-mod scheduling.

The host was an Apple M4 Max, 128 GiB unified memory, macOS 27.0.1, with Python
3.12.9. Both processes ran at nice priority 10, sequentially, without overlapping
Minecraft, builds or other Retina GPU tests. Unrelated desktop load was not
controlled. These measurements do not establish Vulkan or DX12 performance.

## Measurements

| Metric | Run A | Run B |
| --- | ---: | ---: |
| Measured wall time | 223.543 s | 225.226 s |
| Chunks/s | 91.62 | 90.93 |
| Median region latency | 11.143 s | 11.107 s |
| p95 region latency | 12.528 s | 12.817 s |
| Maximum region latency | 12.549 s | 13.085 s |
| Peak process RSS | 4.557 GiB | 4.555 GiB |
| Measured MCA bytes | 108,212,224 | 108,212,224 |

Latency percentiles are across the twenty region calls in each run. Peak RSS is
the process high-water mark, including startup, warmups and corpus validation.
It is not a GPU-only allocation measurement or an estimate of total Minecraft
memory. Unified-memory driver accounting can differ from application RSS.

Run B throughput was 0.75% lower, with nearly identical peak RSS. No arbitrary
pass/fail performance threshold was applied. There is no matched earlier
complete-Nether baseline, so the old Overworld tables cannot establish a Nether
regression or speedup.

The NBT comparison passed all 20,480 chunks, including their blocks, biomes,
heightmaps, lighting and structure metadata; Anvil timestamps are excluded.
Both corpora have SHA-256
`e4b7f9002991cc8908187055cec044a06e63c60b7b4b9db9a49c24f715eeb9df`.
The decoded output contains all five biomes and 156 fossil, 11 fortress, 17
bastion and 15 Nether ruined-portal starts. Every chunk retains all sixteen
storage sections, including those above the terrain roof.

## Cost investigation

The region report's assembly phase includes source-ordered dependency generation.
The outer `vegetation_plan` span averages 10.80–10.89 ms per output chunk, against
10.92–11.00 ms of overall amortized region time. That span includes structure
planning, feature dependency evaluation and the GPU queries they request; it is
not just plant placement.

The profile counters report 18.07–18.14 ms/chunk of structure-planning worker
time, 16.51–16.65 ms/chunk of queue time, 7.74–7.79 ms/chunk of wait/copy time
and 4.57–4.59 ms/chunk of material device time. These counters overlap and
accumulate concurrent work. They cannot be added into wall-time percentages.
Native source inspection confirms that dependency evaluation calls structure
planning within the outer decoration span. The broad assembly field therefore
must not be interpreted as CPU-only work.

Logical GPU readback is 1.60–1.61 GiB per measured region, including dependency
queries; it is cumulative traffic, not resident memory. The initial terrain
report's roughly 40 ms GPU phase excludes the later dependency queries. Ordered
dependency planning and its material queries are the useful next profiling
targets. This cycle makes no production performance-policy change.

## Reproduction and provenance

Build the native library for the host and compile a seed-bound Nether definition:

```sh
./gradlew -PretinaHostOnly :common:exportNetherBenchmarkDefinition
```

Use the built library's platform filename in the commands below. The two output
directories must not exist before running:

```sh
nice -n 10 python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --definition /absolute/path/retina.dat --dimension minecraft:the_nether \
  --origin-x -2 --origin-z -2 --grid-width 5 \
  --count 20 --warmups 5 --parallel 1 --scope lit-mca --lighting enabled \
  --out build/nether-performance/run-a
nice -n 10 python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --definition /absolute/path/retina.dat --dimension minecraft:the_nether \
  --origin-x -2 --origin-z -2 --grid-width 5 \
  --count 20 --warmups 5 --parallel 1 --scope lit-mca --lighting enabled \
  --out build/nether-performance/run-b --compare build/nether-performance/run-a
```

`-PbenchmarkPack=/absolute/path/pack.zip` loads compatible datapack overrides;
`-PbenchmarkDefinitionRoot=...` selects a different export path. The exporter takes an
explicit loaded Nether dimension when supplied, otherwise the loaded Normal
preset's Nether. Native and storage bounds come from the saved definition, independently of
noise bounds. Use `exportBenchmarkDefinition` for the established Overworld
benchmark corpus, and `-PbenchmarkSeed=...` when compiling a different seed.

The two measured runs used the unchanged native tree
`6f371c9f9a2ee37555bbb3cb13089d201bfd048b`, retained library SHA-256
`7f8cc9ef4c2110c8e908a6e8370c6bb8c0c5122f2fea135b9388895f16459b78`, and profile
SHA-256 `0cf913a69f3faceb1c8719fb238dbd90961586262af4cd2cfe0c0fd62953a2ff`.
The harness was the cycle's uncommitted diff on `c3b0b68`; its identical dirty
fingerprint and source hashes are retained with the measurements. No native
rebuild or new Rust-unit-test result is claimed for this harness-only change.

[Recorded measurements](benchmarks/nether-throughput-2026-10-20.json) include both
complete result objects, the host, commands and corpus audit. Full MCA outputs,
profiles, measured source patch and logs are retained in the goal's cycle 6c
evidence. An additional old/new harness comparison preserves all 1,024 Overworld
chunk NBT records. Actual custom-profile exports retain fixed Warped Forest,
storage `[0,256)`, terrain `[16,112)`, scale 4, logical height 192 and no ceiling.
Python request-context/comparison tests pass; their optional LZ4 reader fixture
is skipped because its Python dependencies are absent. The measured workload
uses zlib.
