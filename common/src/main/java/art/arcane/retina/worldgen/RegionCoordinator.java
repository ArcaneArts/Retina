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
    private final long seed;
    private final Path folder;
    private final TemporaryRegions previews;
    // One shared preparation pool; native concurrency is separately bounded for ordinary/bulk work.
    private static final ExecutorService PREPARERS = Executors.newFixedThreadPool(RegionGenerationBudget.BULK_LIMIT,
            Thread.ofPlatform().daemon().name("retina-region-prepare-", 0).factory());
    private final RegionLoadScheduler loads = new RegionLoadScheduler(PREPARERS, 16, RegionCoordinator::release);
    // Written on the I/O queue; request admission can read this from other threads.
    private final Set<Long> prepared = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RegionCoordinator(RetinaChunkGenerator generator, long seed, Path folder) {
        this.generator = generator;
        this.seed = seed;
        this.folder = folder;
        this.previews = generator.previewCache();
    }

    /** Await immutable preparation outside the I/O queue; only publication accesses live MCA files. */
    public <T> CompletableFuture<T> loadAsync(
            ChunkPos position, Supplier<CompletableFuture<T>> read) {
        long key = generator.cacheKey(position);
        return loads.load(key, () -> previews != null && !prepared.contains(key) && Files.notExists(
                        folder.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca")),
                () -> previews.prepare(position), read);
    }

    public void close() { loads.close(); }
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
                    if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info(
                            "QA_EVT {\"event\":\"minecraft_mca_preview_promoted\",\"status\":\"pass\",\"context\":{\"x\":{},\"z\":{},\"copied\":{},\"preserved\":{},\"ms\":{}}}",
                            position.getRegionX(), position.getRegionZ(), cached.generated(), cached.preserved(), elapsed / 1e6);
                } else generator.metrics().completedRegion(0, elapsed);
                return;
            }
            NativeTerrain.RegionReport report;
            var releaseGeneration = RegionGenerationBudget.SHARED.acquire();
            try {
                report = NativeTerrain.instance().generateRegion(
                        generator.request(seed, position.x(), position.z()), destination,
                        SharedConstants.getCurrentVersion().dataVersion().version(), generator.regionBiome());
            } finally { releaseGeneration.run(); }
            long elapsed = System.nanoTime() - started;
            prepared.add(key);
            generator.metrics().completedRegion(report.generated(), elapsed, report.stages());
            if (report.generated() > 0) {
                Retina.LOGGER.info("Generated Retina region {},{}: {} chunks, {} preserved, {} ms",
                        position.getRegionX(), position.getRegionZ(), report.generated(), report.preserved(), elapsed / 1_000_000.0);
                if (Boolean.getBoolean("retina.qa")) {
                    Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_mca_region\",\"status\":\"pass\",\"context\":{\"x\":{},\"z\":{},\"generated\":{},\"preserved\":{},\"ms\":{},\"bytes\":{}}}",
                            position.getRegionX(), position.getRegionZ(), report.generated(), report.preserved(), elapsed / 1_000_000.0, report.bytes());
                }
            }
        } catch (Throwable error) {
            generator.metrics().failed();
            if (error instanceof IOException io) throw io;
            // IOWorker completes failed tasks for Exceptions. This also carries native-loader
            // initialization errors back to its future instead of abandoning the waiting read.
            throw new IOException("Retina MCA generation failed for " + destination, error);
        }
    }
}
