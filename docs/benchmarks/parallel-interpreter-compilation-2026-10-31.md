# Bounded interpreter compilation experiment

`RETINA_INTERPRETER_COMPILE_THREADS=2` opts into concurrent required world/cave
interpreter preparation. `1` (the default) preserves sequential preparation.
The native region runner accepts `--interpreter-compile-threads 1|2` and records
the effective setting with tiering and incremental-specialization settings.
Metal can exercise the same experiment for correctness; no Metal default changes.

The two domain workers compile disjoint entry sets from the same source. Their
pipeline maps and selectors join before publication. Each has a separate driver
cache object, checkpoint lock and filename, with separate foreground/preload and
fast/normal lanes and distinct parent-worker identities. No cache merging is attempted: wgpu exposes no public merge
operation for these caches. Shader identities remain exact and driver-managed.

The child threads explicitly inherit interpreter policy, required/optional
priority, and retirement context. Each creates and pops its own validation
scopes. wgpu 30 scopes are [thread-local guards](https://docs.rs/wgpu/30.0.1/wgpu/struct.ErrorScopeGuard.html);
an error in one worker cannot pop its sibling's scope. Scoped joins keep device,
source and layouts alive until both children return, including failure.

When parallel mode is enabled, all pipeline creation through the startup wrapper
shares a two-call admission budget. Optional work can occupy at most one call,
and required waiters take the next free slot. Optimized interpreter promotion and
specialization remain sequential background bundles. A required-only startup
lane can accept preloads while the original compiler is busy with an optional
bundle. Failure to create that extra lane retains the original worker; domain
worker creation failures fall back to compilation on the caller. Existing driver calls
cannot be preempted. Debug diagnostics report admission waits and worker names.
Parallel mode may help or hurt a given driver; overlap is not promised.

The compiler source queue is nonblocking and bounded to 32 jobs / 64 MiB of
queued source. One larger valid graph can enter an empty queue so its size does
not prevent generation. Required preloads precede optional jobs, dead weak
owners are removed, and rejection/shutdown completes waiting states with an
error. Automatic specialization can retry on a later request; a rejected
optional optimization retains the usable initial interpreter. Required preload
failure retains on-demand compilation. No driver timeout is introduced.

Retirement is checked between module/pipeline calls. Once pruning removes idle
pending leases and no profile owns a shared state, an active worker stops
scheduling further calls after its current call returns. Other profiles sharing
that state keep it alive. Direct on-demand compilation has no background owner
and is unaffected.

The supplied NVIDIA baseline is 95.3 seconds for world and 53.2 seconds for cave
creation. Ideal overlap alone would reduce about 149 seconds to about 95 seconds;
it cannot explain or promise a reduction to DirectX's approximately 21 seconds.
Windows tests must compare startup, CPU contention and final throughput.

## Validation

Unit fixtures exercise priority, slot release on unwind, source/memory bounds,
oversized-graph progress, stale owners, queue shutdown and shared-owner retirement.
The headless GPU parity fixture compares parallel required compilation against
sequential compilation for Overworld/Nether, negative coordinates, expanded
height and material output. Preload and on-demand modes are exercised separately.
An additional GPU fixture pauses specialization after its field cohort, retires
the last owner, and verifies later cohorts stop while subsequent generation on
the same device still succeeds.
Portability Vulkan tests explicitly disable materials while the baseline
MoltenVK workgroup-array translation limitation remains; Metal tests include them.
These are correctness checks, not NVIDIA performance measurements.

Eight headless GPU runs passed: Metal/Vulkan with preload on/off, promotion on
both backends, full-region incremental parity on Metal, and retirement with a
new required preload completing while an optional cohort was paused. The three
Vulkan runs recorded two overlapping world/cave driver calls. Metal runs include
materials; Vulkan runs explicitly use terrain/caves only. Driver caches are
uncontrolled beyond private application directories, so elapsed test durations
are not a cold/warm speed comparison. Before integration of the concurrent CPU
material backend, 432 native tests and four startup-report tests passed.
After rebasing onto that backend, 434 native tests passed, all eight GPU cases
passed again, and the normal shipping configuration passed `cargo check --lib`
without warnings.
