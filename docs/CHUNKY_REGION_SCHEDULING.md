# Chunky and MCA scheduling

Chunky Fabric 1.5.3 uses Minecraft's chunk status scanner, then requests chunks
through `ServerChunkCache` at `FULL` status. Retina's `IOWorker`/region-storage
integration supplies the MCA data for those loads. The region-mode terrain
callback also reads the temporary MCA cache. Chunky's chunks/second includes
Minecraft finishing and loading chunks; it is not the same measurement as
Retina's native terrain production rate. MCA records intentionally retain their
real generation/lighting status, rather than claiming to be fully loaded chunks.

Previously, region preparation began before a read was enqueued, but that read
then waited for preparation on Minecraft's consecutive I/O executor. A slow
region could therefore block Chunky's subsequent status scans, writes and reads
for unrelated ready regions.

`RegionLoadScheduler` now waits before invoking the original `IOWorker.loadAsync`.
Requests sharing a region share its preparation and pin. The existing two
preparation threads and two native-generation permits remain unchanged. At most
four distinct preparations per coordinator are admitted (including ready regions
whose reads have not finished); further requested regions wait asynchronously
for capacity. No neighboring regions are generated speculatively. Live region
handles, pending writes and MCA publication remain on the original I/O queue.

The lease lasts through every attached read, including the pending-write shortcut
that never invokes `RegionFileStorage.read`. Cancelled observers cannot abandon
cleanup. Preparation/queue failures propagate to callers. Storage shutdown fails
outstanding observers and releases leases, including preparation finishing later.
Existing destinations keep the serialized repair/merge path; the file-existence
hint only selects background preparation and does not skip actual generation or
validation of the save.

Chunky's default iterator groups nearby chunks, and its default working limit is
50 chunks. Those requests can all fall in one 1,024-chunk region. Parallel regions
are available when requests actually span regions; this change does not imply
that Chunky always keeps two native regions busy, nor bypass its normal tickets,
completion events, Minecraft finalization or Distant Horizons integration.

## Validation

- `:common:regionPreparationTest`: deterministic scheduling and lease tests cover
  ready-first completion, two concurrent preparation workers, distinct-region
  bounds, coalescing, cancellation, pending-write bypass, preparation/enqueue
  failure, executor rejection, shutdown and late lease release.
- `:fabric:test --tests art.arcane.retina.worldgen.RegionIoMixinTest`: real Fabric
  mixin transformation, reads/writes/status scans, and a controlled preparation
  gate around the real I/O worker. A second region and a concurrent write/scan
  finish while the first region remains gated; its subsequent read sees the edit.
- Common production and GPU-test sources compile, including the updated native
  preview/promotion integration fixture. That full terrain fixture was not run.
- Checks ran locally at reduced priority with two JVM processors and bounded
  processes. No native rebuild, terrain benchmark or game session was launched.

These checks establish scheduling behavior and storage compatibility, not an
end-to-end Chunky speedup. Test gameplay with `./gradlew runClient` and resume
Chunky normally. Compare its completion rate separately from F3's completed
terrain production and promotion counts.
