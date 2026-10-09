package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Requests in one region share its first I/O job; subsequent jobs read the published MCA. */
public final class RegionCoordinator {
    private final RetinaChunkGenerator generator;
    private final long seed;
    private final Path folder;
    private final TemporaryRegions previews;
    // Two requested regions can overlap GPU/planning with the shared Rust assembly pool.
    private static final ExecutorService PREPARERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("retina-region-prepare-", 0).factory());
    private final Map<Long, CompletableFuture<AutoCloseable>> pending = new HashMap<>();
    private boolean closed;
    private final Set<Long> demanded = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Confined to the IOWorker's consecutive executor, including all MCA publication.
    private final Set<Long> prepared = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RegionCoordinator(RetinaChunkGenerator generator, long seed, Path folder) {
        this.generator = generator;
        this.seed = seed;
        this.folder = folder;
        this.previews = generator.previewCache();
    }

    /** A terrain-stage request, unlike Minecraft's wide status-probing ring. */
    public void demand(ChunkPos position) { demanded.add(generator.cacheKey(position)); }

    /** Prefetch an actual request; publication remains on the owning I/O queue. */
    public synchronized void request(ChunkPos position) {
        long key = generator.cacheKey(position);
        if (generator.bendMode() && !demanded.contains(key)) return;
        if (closed || previews == null || prepared.contains(key) || pending.containsKey(key) || pending.size() >= 4) return;
        Path destination = folder.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca");
        if (!Files.notExists(destination)) return;
        pending.put(key, CompletableFuture.supplyAsync(() -> previews.prepare(position), PREPARERS));
    }

    public synchronized void close() {
        closed = true;
        for (var future : pending.values()) future.thenAccept(RegionCoordinator::release);
        pending.clear();
    }
    private static void release(AutoCloseable lease) {
        try { if (lease != null) lease.close(); }
        catch (Exception error) { Retina.LOGGER.warn("Could not release prepared Retina region", error); }
    }

    public void prepare(ChunkPos position, RegionStorageBridge storage) throws IOException {
        long key = generator.cacheKey(position);
        // Loading EMPTY/status dependencies must never assemble unrequested terrain.
        // Existing saved chunks still flow through the ordinary storage read below.
        if (generator.bendMode() && !demanded.contains(key)) return;
        if (prepared.contains(key)) {
            CompletableFuture<AutoCloseable> unused;
            synchronized (this) { unused = pending.remove(key); }
            if (unused != null) unused.thenAccept(RegionCoordinator::release);
            return;
        }
        var destination = storage.retina$folder().resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca");
        CompletableFuture<AutoCloseable> preparation;
        synchronized (this) { preparation = pending.get(key); }
        AutoCloseable lease = null;
        generator.metrics().begin();
        long started = System.nanoTime();
        try {
            if (generator.bendMode() && BendRegionFiles.complete(destination,position,BendRegionBackend.BATCH_CHUNKS)) {
                prepared.add(key); generator.metrics().completedRegion(0,System.nanoTime()-started); return;
            }
            if (preparation != null) lease = preparation.join();
            else if (generator.bendMode()) lease = previews.prepare(position);
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
            if (generator.bendMode()) throw new IOException("Bend region preparation did not publish: "+destination);
            var report = NativeTerrain.instance().generateRegion(
                    generator.request(seed, position.x(), position.z()), destination,
                    SharedConstants.getCurrentVersion().dataVersion().version(), generator.regionBiome());
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
        } finally {
            synchronized (this) { pending.remove(key); }
            release(lease);
        }
    }
}
