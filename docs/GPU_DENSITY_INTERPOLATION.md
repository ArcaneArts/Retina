# GPU density interpolation

Retina interpolates the registered final solid density in 3D before extracting
surface heights. This replaces interpolation of already-extracted corner heights.
Vanilla's dominant cell is 4 x 8 x 4; datapack cell sizes are exported from the
registered density expressions. Terrain-shaping Hermite splines still run in the
GPU bytecode interpreter.

The density lattice, extracted surface heights, slope halo, and lake-bank probes
stay on the GPU. Cave generation reuses the solid field. Eligible lake probes
sample their density corners and Y levels in parallel before extracting waterlines.
The public Java/Rust request and timing ABIs and readback formats are unchanged.

[Registered interpolation scopes](GPU_INTERPOLATION_SCOPES.md) now preserve
individual operators, nested cell sizes and slices in direct graph samples.
The shared final-density lattice at the first imported cell size remains a
terrain-pipeline approximation. GPU noise remains an approximation of Minecraft's
CPU sampler. Existing generated terrain retains its old shape.

## Validation

Actual Metal tests passed on an Apple M4 Max, including:

- Analytic plane and weighted XYZ density fields; the latter distinguishes
  density interpolation from height interpolation.
- Custom 4 x 8, 6 x 12, and 8 x 4 cells, negative coordinates, mixed vertical
  bounds, and a partial cell at the world ceiling.
- Independent chunk/region border comparisons, including distant coordinates.
- Full 1024-chunk MCA decoding and parity with the native chunk path.
- Surface preservation, ores, caves, decorations, structures, DH temporary cache
  promotion, and Terralith registry integration.

The full Gradle build and GPU, biome, geology, feature, preview, datapack,
structure, and region test tasks passed. The user also confirmed improved slopes
and coastlines in fresh vanilla Retina terrain.

## Region benchmark

Two six-region runs per version used seed 123456789, the same exported vanilla
structure profile, one warmup, and one region request at a time. Each request
still assembles its chunks in parallel. Results include structures, decorations,
ores, caves, MCA encoding, compression, and file output.

| Measurement | Previous version | Density interpolation |
| --- | ---: | ---: |
| Mean reported GPU phase per region | 58.99 ms | 36.22 ms |
| Mean total region time, first run | 276.37 ms | 253.38 ms |
| Mean total region time, second run | 297.80 ms | 449.48 ms |

The reported GPU phase fell by about 39%. Total time varied in Rust assembly,
so these measurements do not establish a consistent end-to-end speedup. Terrain
intentionally differs between versions, and background CPU load was uncontrolled.
A run overlapping the package build was excluded.

Reproduce with a freshly exported `build/structure-profile.json`:

```sh
python3 scripts/native-region-benchmark.py \
  --library build/native-target/release/libretina_worldgen.dylib \
  --profile build/structure-profile.json \
  --out build/density-benchmark-new-run --count 6 --warmups 1
```

Use a new output directory for each run and the appropriate native library path
for the platform. Baseline library SHA-256 was
`513b1bf46cddb695096f46ff166fc46e888489311567643ae501ca15ca5887c7`;
the final tested macOS library was
`a3fc54f752e4b1949718658d7bc6170406c99ac9840cff88d309eb5b22987227`.
