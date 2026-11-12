package art.arcane.retina.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** Chunky's provider is replaced on each integrated-server lifetime. */
public final class ChunkyLifecycle {
    private static final AtomicLong GENERATION = new AtomicLong();

    private ChunkyLifecycle() {}

    public static long generation() { return GENERATION.get(); }
    public static void changed() { GENERATION.incrementAndGet(); }

    public static boolean providerNotLoaded(IllegalStateException error) {
        var trace = error.getStackTrace();
        return "Chunky is not loaded.".equals(error.getMessage()) && trace.length != 0
                && trace[0].getClassName().equals("org.popcraft.chunky.ChunkyProvider")
                && trace[0].getMethodName().equals("get");
    }
}
