package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Requests in one region share its first I/O job; subsequent jobs read the published MCA. */
public final class RegionCoordinator {
    private final RetinaChunkGenerator generator;
    private final long seed;
    // Confined to the IOWorker's consecutive executor, including all MCA publication.
    private final Set<Long> prepared = new HashSet<>();

    public RegionCoordinator(RetinaChunkGenerator generator, long seed) {
        this.generator = generator;
        this.seed = seed;
    }

    public void prepare(ChunkPos position, RegionStorageBridge storage) throws IOException {
        long key = ChunkPos.pack(position.getRegionX(), position.getRegionZ());
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
            var report = NativeTerrain.instance().generateRegion(
                    generator.request(seed, position.x(), position.z()), destination,
                    SharedConstants.getCurrentVersion().dataVersion().version(), generator.regionBiome());
            long elapsed = System.nanoTime() - started;
            prepared.add(key);
            generator.metrics().completedRegion(report.generated(), elapsed);
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
            throw new IOException("Rust MCA generation failed for " + destination, error);
        }
    }
}
