# Rejected explicit-stack GPU interpreter trial

The current registered interpolation interpreter emits one evaluator per nesting
level, because WGSL does not permit recursive functions. Each level contains the
same opcode switch and calls only an earlier interpolation evaluator. A profile's
first request synchronously creates the corresponding depth/capacity pipeline
bundle when it is not already cached. Cold generic activation remains required
work, separately from background profile specialization.

Two prototypes tested whether a single opcode evaluator with an explicit GPU
evaluation stack could replace those duplicated bodies. Neither demonstrated a
useful region-throughput benefit, so both were removed. The production code and
packaged library are restored to the validated interval-priming milestone.

## Implementations

Both prototypes used the original interpreter's verbatim opcode arithmetic and
resident register map. Interpolation became a state machine: suspend the parent's
instruction, visit only required corners, evaluate uncached input graphs in child
frames, restore the parent and perform the original trilinear mix. Each field used
its registered cell spacing; fully aligned calls retained the original point.
Resident corner hits bypassed child evaluation. Request and material context stayed
immutable, and nested field dependencies bounded the generated stack depth.

The direct-frame version addressed values in an array of `capacity × (depth + 1)`
floats. Each frame held its program, point, instruction cursor and interpolation
state. The working-register version kept one graph's register array local and
saved/restored parent values only when entering a child graph. Both executed on
the GPU and added no binding, dispatch, CPU spatial evaluation or readback.

The experimental `RETINA_STACK_INTERPRETER=1` selection applied only to the
depth-specific interpreter bundle. It did not change profile specialization or
the initial shared depth-zero shaders. Full-profile tests exercised direct,
resident-cache and far-coordinate queries against the independently retained
original interpreter. The prototype option and QA hook are removed from production.

## Small counterbalanced trials

Apple M4 Max / Metal, seed 123456789, complete vanilla/Terralith profiles with
structures and decorations (56/151 biomes and 172/510 decoration recipes). Each
process measured two adjacent regions after one warmup, with composition disabled
and all GPU stages interpreted. For each implementation, vanilla ran original
then stack; Terralith reversed that order. Each pair used the same native library
and changed only the experimental selection. Builds and measurements ran
sequentially, at reduced priority, with two Cargo jobs.

| Implementation / profile | Mean region ms, original → stack | Chunks/s, original → stack | Height/climate GPU ms/region, original → stack |
| --- | ---: | ---: | ---: |
| Direct frames / vanilla | 254.43 → 322.78 | 4,007 → 3,160 | 23.22 → 28.95 |
| Direct frames / Terralith | 474.38 → 612.00 | 2,154 → 1,670 | 41.40 → 51.11 |
| Working registers / vanilla | 255.98 → 343.72 | 3,983 → 2,968 | 22.00 → 30.04 |
| Working registers / Terralith | 478.96 → 674.41 | 2,133 → 1,516 | 40.61 → 55.94 |

Whole-region latency increases 27–29% for direct frames and 34–41% for working
registers in these trials. The height/climate stage also regresses on both profiles.
Desktop activity and driver-cache warmth are uncontrolled. These small trials
filter candidates; they are not twenty-region regression estimates or proof of a
universal slowdown. They provide no throughput reason to retain either prototype.
Removing duplicated source does not establish lower hardware instruction count,
private-memory traffic or physical register pressure. Their additional stack
bookkeeping was not separately isolated as the cause of the measured regression.

Every one of the eight runs' **16,384** complete decompressed chunk NBT records
matches the production reference, including final metadata and structures.
The nested irregular-grid fixture and six full-profile GPU oracle modes per
prototype also pass: **519,168 exact root float-bit comparisons** in total.
The oracle bypasses the resident cache in the reference and compares each root
separately, so a shared cache error or checksum cancellation cannot hide a mismatch.
The fixture retains its independent Minecraft/noise reference checks.

## Startup interpretation

The first direct-frame vanilla region takes 4,923 ms after initialization and
registration; its first Terralith region takes 778 ms. The working-register version
observes 5,834 / 861 ms. Original first regions take 322 / 638 ms and 329 / 629 ms
in the respective pairs.

The new interpreter source identities had not previously compiled their entire
generic pipeline sets, while the original sets were driver-warm. Vanilla and
Terralith share the same depth/capacity generic source in these profiles, so the
second process can reuse shaders compiled by the first. These are not matched cold
startup comparisons, and no cold-start gain or regression is attributed to the
stack design. The trial did not collect per-entrypoint cold compiler timings.
Generation correctness and a shorter evaluator source alone do not satisfy the
goal's startup requirement.

Further startup work should measure the actual generic entrypoints and their
synchronous activation, then preserve complete graph semantics while reducing
that critical path. It must also preserve the currently faster steady-state
interpreter and continue generating during profile specialization. Merely moving
the same stall to another required startup step is not a solution.

## Evidence and restoration

Evidence is in `build/goal-baseline/stack-interpreter/`: complete profiles,
`small.py`, `working-small.py`, eight measurement directories, `evidence.json`,
both retained source variants, the integration patch, nested-fixture logs and
six full-profile parity logs per variant. The direct-frame library
`direct-frame.dylib` is SHA-256
`805610a636a097ca1a4cc58104cd5fa44c02abb2a9dce377a2cfa1699583e21e`.
The working-register library `working-registers.dylib` is SHA-256
`5375656189d57cfe1f64a7e5aa04579f448917a364943faad136f06b714676ba`.

The restored native output and packaged JAR member are byte-identical to the
validated [interval-priming library](GPU_PRIMED_LAKE_CORNERS.md), SHA-256
`600de4bbc617b978dc61eb0105556d69aac14a5853b78b8409d68efbfc0c5544`.
The restoration build passes its 54 native unit checks; its log is
`build/stack-interpreter-restored-build.log`. Cold startup, fast production
composition and remaining registered feature/provider coverage are still open.
