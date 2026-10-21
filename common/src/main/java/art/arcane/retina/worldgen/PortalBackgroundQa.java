package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Measures actual DH native work during the opt-in portal fixture. */
final class PortalBackgroundQa {
    static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.portalDh");
    private static final AtomicLong STARTED = new AtomicLong(), COMPLETED = new AtomicLong();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final ConcurrentHashMap<Long, AtomicLong> NETHER_CHUNKS = new ConcurrentHashMap<>();

    private PortalBackgroundQa() {}

    static long started() { return STARTED.get(); }
    static long completed() { return COMPLETED.get(); }
    static int active() { return ACTIVE.get(); }

    static void generated(ResourceKey<Level> dimension, ChunkPos origin, int chunks) {
        if (ENABLED && dimension.equals(Level.NETHER) && chunks > 0)
            NETHER_CHUNKS.computeIfAbsent(ChunkPos.pack(origin.getRegionX(), origin.getRegionZ()),
                    ignored -> new AtomicLong()).addAndGet(chunks);
    }

    static long chunks(List<ChunkPos> regions) {
        long chunks = 0;
        for (var region : regions) {
            var count = NETHER_CHUNKS.get(ChunkPos.pack(region.getRegionX(), region.getRegionZ()));
            if (count != null) chunks += count.get();
        }
        return chunks;
    }


    static Span begin(ResourceKey<Level> dimension, ChunkPos origin) {
        if (!ENABLED) return null;
        try {
            if (!(boolean) Class.forName("com.seibel.distanthorizons.api.DhApi")
                    .getMethod("isDhThread").invoke(null)) return null;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Portal background QA requires the installed DH API", error);
        }
        STARTED.incrementAndGet();
        ACTIVE.incrementAndGet();
        return new Span(dimension, origin);
    }

    static final class Span implements AutoCloseable {
        private final ResourceKey<Level> dimension;
        private final ChunkPos origin;
        private final long began = System.nanoTime();
        private int generated;
        private boolean succeeded;

        private Span(ResourceKey<Level> dimension, ChunkPos origin) {
            this.dimension = dimension;
            this.origin = origin;
        }
        void complete(int generated) {
            this.generated = generated;
            succeeded = true;
        }
        @Override public void close() {
            ACTIVE.decrementAndGet();
            if (succeeded && generated > 0) COMPLETED.incrementAndGet();
            Retina.LOGGER.info("QA_EVT {\"event\":\"portal_dh_native_region\",\"status\":\"{}\",\"context\":{\"dimension\":\"{}\",\"x\":{},\"z\":{},\"chunks\":{},\"ms\":{}}}",
                    succeeded ? "pass" : "fail", dimension.identifier(), origin.getRegionX(), origin.getRegionZ(),
                    generated, (System.nanoTime() - began) / 1e6);
        }
    }
}
