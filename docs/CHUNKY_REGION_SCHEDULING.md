# Chunky and MCA scheduling

Chunky Fabric 1.5.3 checks chunk status, then requests chunks through Minecraft at
`FULL` status. Retina supplies MCA terrain, while Chunky still owns tickets,
loading, remaining generation stages and completion events. Its chunks/second
therefore differs from F3's native terrain production rate, measured over a
ten-second wall-clock window (elapsed observation time during startup). MCA records
retain their actual generation/lighting status; they are not falsely marked FULL.

## Generate the selection ahead

Starting or resuming a Chunky task now starts an independent Retina region pump
for Retina MCA worlds. It snapshots the task's selection and completed-count
resume offset, asks Chunky to create and process a separate iterator, and applies
Chunky's own shape predicate at the same chunk block centers used by its task.
Region coordinates use floor division, including negative coordinates. Each
intersecting region is admitted once, in the iterator's order. This also uses
Chunky's own handling of CSV/world patterns and registered custom shapes; no
circle, rectangle or bounding-box approximation replaces its selection.

The pump keeps up to **eight region jobs in flight**, refilling on MCA publication
rather than waiting for Chunky's individual chunk completions. It can finish the
selection's terrain while Chunky continues loading it. It keeps one deduplication
key per selected region and streams the iterator instead of collecting all chunk
coordinates. Full-MCA granularity includes the remainder of intersecting edge
regions outside the exact selection, as ordinary Retina region generation does.

Pause, cancellation and task exit stop new admissions. Already-admitted jobs may
finish their publication. Errors stop the ahead pump and are logged; ordinary
chunk loading remains active and still surfaces real generation/read failures.
No extra command or launch flag is required. Restart the client, then start or
resume Chunky normally. Logs announce the eight-region pump and its completion
or stop, separately from Chunky's FULL-chunk progress.

## Concurrency and save ownership

The shared Java preparation pool has eight threads. A global region budget allows
up to eight native region generations at all times, covering ordinary loads, DH
temporary generation, direct save repair and Chunky. Pausing Chunky stops its new
admissions while admitted jobs drain; it does not reduce the shared limit.
Multiple worlds/tasks share this fair budget. Rust's existing
shared assembly pool and GPU submission slots are reused, not multiplied eight
fold. More overlap does not imply eight times the throughput.

The coordinator admits at most sixteen distinct region preparations/reads, so
foreground requests have room alongside the eight ahead jobs. Requests for the
same region coalesce, including races between Chunky, foreground loads and DH's
temporary cache. Each job pins its immutable temporary MCA until its attached
operations finish. Cancellation, failures and shutdown release the lease exactly
once, including late completion after shutdown.

Prepared regions are published by an explicit task on Minecraft's consecutive
I/O executor. This neither loads a chunk nor decodes its NBT. The task closes the
cached live region handle, performs the existing preserve-and-merge publication,
and invalidates cached misses. Pending Minecraft writes stay on the same queue
and retain their normal ordering. Publication admission and the I/O shutdown
barrier are ordered so no ahead task is submitted to an already-closed queue.
Existing destinations retain the actual serialized repair/merge path; merely
finding a file does not mark it generated or skip validation. Partial-save repairs
can still occupy the I/O queue while they generate missing data.

Ordinary loads continue to wait for fresh preparation **before** enqueueing their
read, so a slow region does not hold up unrelated status scans and ready reads.
The previous request-only pipeline could remain underused because Chunky's default
50 working chunks often belonged to one 1,024-chunk region. Selection-driven
admission removes that dependency; Minecraft's loading/finalization throughput can
still limit Chunky's own displayed rate after terrain is already on disk.

## Validation

- `:common:regionPreparationTest` covers bounded scheduling, ready-first reads,
  deduplication, cancellation, failures and late cleanup. The new pump fixture
  verifies eight immediate admissions, independent refill, pause/drain, failure,
  10,000 already-completed requests without recursion, and the always-on
  eight-region budget (including blocking a ninth job and idempotent release).
- Actual `Chunky-Fabric-1.5.3.jar` tests compare the region sequence against its
  own chunk iteration for six shapes, negative coordinates, boundary crossings
  and resumed offsets. They also load the actual GenerationTask mixin.
- Fabric I/O tests exercise actual mixins, reads, writes, scans and delayed reads.
- A native publication fixture generates two 16-block-tall regions. It verifies
  all 1,024 MCA slots exist before a chunk load, duplicate requests coalesce,
  loaded NBT matches the cached result, and an edit survives concurrent publication.
- Common/GPU-test sources compile. Checks use reduced priority, two JVM processors,
  two native assembly workers and bounded processes. The eight-way limit is tested
  with sleeping permit holders, not eight full terrain regions. No game, native
  rebuild, sustained benchmark or GitHub Actions run is needed for these checks.

Optional integration fixtures accept `-PretinaChunkyJar=<installed jar>` and
`-PretinaNativePath=<existing native library>` on `:fabric:test`. They do not bundle
Chunky or the test library into the mod. No end-to-end Chunky speedup is claimed
from these correctness checks; compare F3's completed terrain production separately
from Chunky's load-completion rate during gameplay.


The simulated provider-startup transition uses a controllable test provider and
runs without `-PretinaChunkyJar`. When the installed JAR is supplied, its real
provider replaces that fixture, so only the simulated transition is disabled in
that lane. Run `:fabric:test --tests art.arcane.retina.compat.ChunkyStartupTest`
without the property to exercise it separately. Both lanes passed in the
[combined validation](OPTIMIZATION_INTEGRATION_2026_10.md); production startup and
error handling are unchanged.
