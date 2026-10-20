package art.arcane.retina.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** Chunky's provider is replaced on each integrated-server lifetime. */
public final class ChunkyLifecycle {
    private static final AtomicLong GENERATION = new AtomicLong();

    private ChunkyLifecycle() {}

    public static long generation() { return GENERATION.get(); }
    public static void changed() { GENERATION.incrementAndGet(); }
}
