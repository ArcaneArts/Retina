# Rejected per-voxel GPU cave-mask trials

Two Metal subgroup prototypes were tested and reverted. The one-word prototype
reduces the composed cave-mask stage, but does not establish a dependable
whole-region benefit across vanilla, Terralith and concurrent requests. The
four-word prototype does not consistently improve even that GPU stage. Production
retains the packed-word kernel from `dd15324`; neither prototype nor its diagnostic
switch is shipped.

## Prototype layout

The existing kernel assigns one invocation to a packed 32-bit mask word and
evaluates its voxel decisions sequentially. The first prototype assigns a
32-invocation workgroup to one word. Each invocation evaluates one voxel, then
subgroup OR reductions and a shared-memory reduction assemble the existing volume
and surface words. Padding invocations participate in the reductions. The second
prototype uses 128 invocations for four words, reducing four-component vectors.

The reductions do not assume a relationship between subgroup invocation IDs and
workgroup-local invocation indices. WGSL does not guarantee that mapping; see the
[WGSL subgroup invocation definition](https://www.w3.org/TR/WGSL/#subgroup-invocation-id).
Each subgroup leader writes shared results; a workgroup barrier precedes the
final writers. Both versions account for multiple subgroups.

The optional entrypoints require actual adapter support for `SUBGROUP` and are
selected only from a ready specialized bundle. Pending compilation and unsupported
adapters use the existing GPU kernel. The immutable compiler selection participates
in the source/cache identity, and host dispatch geometry changes only when the new
entry is selected. Cached-density variants retain the existing coverage proofs.

The prototypes change no registered density, carver, entrance, roof or aquifer
decisions. They add no GPU pass, buffer, binding, dense readback or CPU noise.
The existing cave-mask timer includes the exterior stage and the selected mask.
Naga 30 accepts the subgroup operations and builtins with the requested feature;
it rejects the `enable subgroups` directive. A native unit test caught that initial
syntax error; both corrected prototype builds pass all 66 unit tests.

## Twenty-region one-word comparisons

Apple M4 Max / Metal, seed 123456789, complete current vanilla and Terralith
profiles with structures and decorations. Each process has two warmups and twenty
measured adjacent regions, including negative coordinates. Both sides enable the
same experimental [block-position composition](GPU_DENSITY_COMPOSITION.md).
Off/on pairs use the same candidate library. The second serial pair reverses
phase order. Builds, tests and benchmark processes run sequentially at nice 10;
native compilation uses two Cargo jobs. Other desktop activity is uncontrolled.

| Profile / callers | Existing mean region ms | One-word mean region ms | Existing chunks/s | One-word chunks/s | Cave-mask GPU ms, existing → one-word |
| --- | ---: | ---: | ---: | ---: | ---: |
| Vanilla / serial, first pair | 208.63 | 189.48 | 4,901 | 5,396 | 17.05 → 12.02 |
| Vanilla / serial, reversed pair | 213.31 | 196.60 | 4,794 | 5,201 | 17.09 → 12.00 |
| Vanilla / two callers | 334.09 | 355.55 | 5,912 | 5,747 | 17.15 → 14.81 |
| Terralith / serial, first pair | 358.79 | 381.54 | 2,852 | 2,681 | 24.06 → 15.69 |
| Terralith / serial, reversed pair | 418.42 | 416.14 | 2,445 | 2,458 | 27.48 → 16.21 |
| Terralith / two callers | 835.01 | 848.38 | 2,427 | 2,374 | 29.60 → 19.07 |

Serial vanilla throughput improves by about 8–10%, but concurrent vanilla falls
2.8%. Terralith serial results range from a 6% loss to a 0.5% gain; concurrent
throughput falls 2.2%. All six GPU stage comparisons improve, demonstrating why a
stage timer alone is insufficient evidence for enabling the change.

Rust work also varies. In the first Terralith pair, vegetation planning rises
from 130.56 to 153.39 worker ms per region and NBT encoding from 115.53 to 126.43.
Worker totals and GPU stages overlap and cannot be added as latency components.
These observations cannot attribute all whole-region variation to either the
kernel or desktop contention.

Each twenty-region output totals exactly 157,732,864 bytes vanilla or 136,495,104
bytes Terralith. All **245,760** complete chunk NBT comparisons in these twelve
processes match the retained composed baseline, including serialized metadata.

## Short exploratory trials

The following checks have only two measured regions and one warmup. They are
stage experiments, not twenty-region throughput claims.

| Prototype / profile / mode | Existing cave-mask GPU ms | Prototype cave-mask GPU ms |
| --- | ---: | ---: |
| One word / vanilla / composition | 16.83 | 11.82 |
| One word / Terralith / composition | 21.16 | 14.08 |
| One word / vanilla / ordinary | 6.76 | 9.06 |
| One word / Terralith / ordinary | 6.32 | 9.03 |
| Four words / vanilla / composition | 16.78 | 17.81 |
| Four words / Terralith / composition | 20.84 | 20.55 |
| Four words / vanilla / ordinary | 6.72 | 10.75 |
| Four words / Terralith / ordinary | 6.38 | 10.60 |

Ordinary masks regress in both prototypes. Four-word composition does not provide
a dependable stage gain, so it was stopped after the short trials. Their sixteen
processes add **32,768** matching chunk records, for **278,528** comparisons across
all trials. Warmup chunks are excluded from that count.

New shader identities have larger first-activation waits. The checks mix new and
driver-warm identities and do not establish comparative cold-start costs. No
startup improvement is claimed.

## Reproduction and restoration

Private artifacts remain in `build/goal-baseline/voxel-cave-mask/`:

- `manifest.json`, `measurements.json`, `pilot.json`, `pilot128.json`, complete
  MCA outputs and process logs;
- `before.dylib`, `word32.dylib`, `word128.dylib`, full `vanilla.json` and
  `terralith.json` profiles;
- `word32.patch`, `word128.patch` including the added helper/shader sources, and
  `word32.wgsl`;
- `benchmark.py`, the private benchmark CLI with the rejected
  `--cave-voxel-mask enabled|disabled` diagnostic. That switch was removed from
  the public benchmark when the prototypes were reverted.

The retained-library SHA-256 values are:

| Artifact | SHA-256 |
| --- | --- |
| Existing `before.dylib` | `781e3cca5d630fdb808b6773ecc29cfd6a26e18d93af719e1be105140e1b0656` |
| One-word `word32.dylib` | `140fde11541ba4a5879f40a9555602dba663f3f9e8c28013bcd8190b06a3edb1` |
| Four-word `word128.dylib` | `acab92bca8c235f287606f8a961659fb001cdfa8676b7fbe22830c59c2ae60fe` |

After reverting all prototype source and CLI changes, a locked release build
passes. Its native library and the native library embedded in
`build/libs/retina-0.1.0.jar` both have the exact existing `781e3cca…0656` hash.
The restoration log is `build/voxel-cave-mask-restored-build.log`. This milestone
records a rejected experiment and does not change production generation.
