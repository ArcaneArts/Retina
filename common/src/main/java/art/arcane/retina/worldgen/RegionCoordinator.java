package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Requests share region preparation before enqueueing their serialized MCA publication/read jobs. */
public final class RegionCoordinator {
    private final RetinaChunkGenerator generator;
    private final Path folder;
    private final TemporaryRegions previews;
    private final RegionGenerationBudget.Scope publicationAdmission = RegionGenerationBudget.SHARED.scope();
    // Budget waiters must not occupy all preparation workers before portal work
    // can join the queue. Native execution still uses the same global limit.
    // Native foreign calls pin virtual-thread carriers. Platform tasks preserve
    // configured concurrency above the carrier count; each loader still bounds
    // admitted preparations and the shared budget bounds actual native work.
    private static final ExecutorService PREPARERS = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("retina-region-prepare-", 0).factory());
    private final RegionLoadScheduler loads = new RegionLoadScheduler(PREPARERS,
            RegionGenerationBudget.SHARED.admissionCapacity(), RegionCoordinator::release);
    // Written on the I/O queue; request admission can read this from other threads.
    private final Set<Long> prepared = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RegionCoordinator(RetinaChunkGenerator generator, Path folder) {
        this.generator = generator;
        this.folder = folder;
        this.previews = generator.previewCache();
    }

    /** Await immutable preparation outside the I/O queue; only publication accesses live MCA files. */
    public <T> CompletableFuture<T> loadAsync(
            ChunkPos position, Supplier<CompletableFuture<T>> read) {
        return loadAsync(position, true, read);
    }

    public <T> CompletableFuture<T> loadAsync(
            ChunkPos position, boolean foreground, Supplier<CompletableFuture<T>> read) {
        long key = generator.cacheKey(position);
        return loads.load(key, foreground, () -> previews != null && !prepared.contains(key) && Files.notExists(
                        folder.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca")),
                (priority, cancellation) -> previews.prepare(position, priority, cancellation),
                () -> { if (previews != null) previews.promote(position); }, read);
    }

    /** Stop queued generation before the I/O worker waits for its shutdown barrier. */
    public void stopAdmission() { publicationAdmission.close(); }

    public void close() {
        stopAdmission();
        NetherUnloadQa.storageClosing(generator);
        loads.close();
    }
    private static void release(AutoCloseable lease) {
        try { if (lease != null) lease.close(); }
        catch (Exception error) { Retina.LOGGER.warn("Could not release prepared Retina region", error); }
    }

    public void prepare(ChunkPos position, RegionStorageBridge storage) throws IOException {
        long key = generator.cacheKey(position);
        if (prepared.contains(key)) return;
        var destination = storage.retina$folder().resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca");
        generator.metrics().begin();
        long started = System.nanoTime();
        try {
            // Includes cached misses. All reads/writes in this storage run on this same queue.
            storage.retina$closeRegion(position);
            var cached = generator.publishPreview(position, destination);
            if (cached != null) {
                long elapsed = System.nanoTime() - started;
                prepared.add(key);
                if (cached.generated() > 0) {
                    generator.metrics().completedPromotion(elapsed);
                    Retina.debug("Published Retina region {} {},{}: {} cached chunks, {} preserved, {} ms",
                            generator.dimension().identifier(), position.getRegionX(), position.getRegionZ(), cached.generated(), cached.preserved(), elapsed / 1e6);
                    if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info(
                            "QA_EVT {\"event\":\"minecraft_mca_preview_promoted\",\"status\":\"pass\",\"context\":{\"dimension\":\"{}\",\"x\":{},\"z\":{},\"copied\":{},\"preserved\":{},\"ms\":{}}}",
                            generator.dimension().identifier(), position.getRegionX(), position.getRegionZ(), cached.generated(), cached.preserved(), elapsed / 1e6);
                } else generator.metrics().completedRegion(0, elapsed);
                return;
            }
            NativeTerrain.RegionReport report;
            var releaseGeneration = publicationAdmission.acquire(true);
            try {
                report = NativeTerrain.instance().generateRegion(
                        generator.request(position.x(), position.z()), destination,
                        SharedConstants.getCurrentVersion().dataVersion().version(), generator.regionBiome());
            } finally { releaseGeneration.run(); }
            long elapsed = System.nanoTime() - started;
            prepared.add(key);
            generator.metrics().completedRegion(report.generated(), elapsed, report.stages());
            if (report.generated() > 0) {
                Retina.debug("Generated Retina region {} {},{}: {} chunks, {} preserved, {} ms",
                        generator.dimension().identifier(), position.getRegionX(), position.getRegionZ(), report.generated(), report.preserved(), elapsed / 1_000_000.0);
                if (Boolean.getBoolean("retina.qa")) {
                    Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_mca_region\",\"status\":\"pass\",\"context\":{\"dimension\":\"{}\",\"x\":{},\"z\":{},\"generated\":{},\"preserved\":{},\"ms\":{},\"bytes\":{}}}",
                            generator.dimension().identifier(), position.getRegionX(), position.getRegionZ(), report.generated(), report.preserved(), elapsed / 1_000_000.0, report.bytes());
                }
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            generator.metrics().cancelled();
            throw cancelled;
        } catch (Throwable error) {
            generator.metrics().failed();
            if (error instanceof IOException io) throw io;
            // IOWorker completes failed tasks for Exceptions. This also carries native-loader
            // initialization errors back to its future instead of abandoning the waiting read.
            throw new IOException("Retina MCA generation failed in " + generator.dimension().identifier() + " for " + destination, error);
        }
    }
}
