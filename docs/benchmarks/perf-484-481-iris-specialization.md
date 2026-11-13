# Iris specialization startup on Metal

The frozen 768-block Iris world definition has SHA-256 `96a1cc33d8dc6dda511be96a62dec6d915063504648745867242e4344dc8f527`. The benchmark uses a native Apple M4 Max process with one region caller, four assembly workers, Rayon two, and normal power mode on AC. Each launch starts from a fresh output directory and writes one complete lit zlib MCA region. It then waits up to 240 seconds for specialization to finish. [Raw measurement summaries](perf-484-481-iris-specialization.json) preserve startup phases, memory samples, and commands.

The ordinary policy saved its first MCA at 21.34 seconds. Existing incremental publication saved at 19.25 seconds. Both background compilers entered the first large shader-module call within four seconds of launch. Each compiled about 127.6 MB of WGSL with 330 outlined graph bodies. The module call remained pending at the observation deadline, so neither process reached its first specialized pipeline call. The incremental run published no cohort. A four-second sample of the ordinary policy found 644 of 644 captured optional compiler stacks in the Naga WGSL front end, almost all within the lowering pass. The sampled stack includes repeated lowering of nested constructors. Metal driver phases for the independently usable interpreter completed before terrain submission.

The separately saved Java export contains 16,585 spline instructions and 807,744 spline-knot incidences, with as many as 793 knots per instruction. The specialized emitter builds a three-component vector per knot even though positions and derivatives already reside in the same immutable bytecode table. This introduces multiple nested WGSL constructors and loads for each knot before the original linear selection. The test candidate emits an array of computed values, reads position and derivative from the resident table, and preserves the original knot selection and Hermite arithmetic.

## Prototype results

| Cohort mode | First module WGSL | First MCA | First optional parse | First optional validation |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 127.61 MB | 19.25 s | Pending at deadline | Unreached |
| Resident spline positions and derivatives | 48.95 MB | 21.19 s | 88.07 s | 0.63 s |
| Resident spline data and shared equivalent interpolation bodies | 32.84 MB | 24.43 s | 76.08 s | 1.00 s |

After the first modified module passed validation, the trivial horizontal-field entry completed. This direct-height profile has no horizontal fields, and the existing incremental code publishes only after the full field group. In both prototypes, the next entry (`interpolation_nodes_1`) reached Metal library compilation. The first prototype timed out inside that call. These measurements distinguish Rust source emission, Naga parsing and validation, and Metal compiler execution. Queue counts alone do not distinguish them.

Peak process RSS was 5.35 GiB in the default baseline and 3.34 GiB in the cohort baseline. The processes ran at different host loads with substantial system memory pressure, and the sampled RSS excludes Metal compiler services. The first prototype's external sampler stalled after the generation process exited, so that run has a first-region hash and native stage log but no RSS series.

Sharing matching interpolation graph bodies reduced the first module to about one quarter of the original size. It also reached the same Metal call and remained there at its 200-second post-generation deadline. Neither prototype published a usable specialized cohort or reached final readiness. Both first-region corpora matched the frozen interpreter reference by decompressed NBT and membership hash. The specialization path itself did not finish, so specialized output and cancellation during its later cohorts could not be qualified. Both shader changes were discarded.

The next experiment should give each independently usable interpolation level a module containing only its reachable graph closure. In the current prepass, every level calls the same switch covering all 152 fields. A request selects just the fields at its own level, but the shared switch exposes the entire graph family to each driver compile. Verify cross-level dependencies and publish useful pipelines with the existing interpreter for unresolved entries. Re-run the same first-MCA, first-useful-cohort, and final-readiness measurements. Vulkan and DX12 still need qualification on hardware.
