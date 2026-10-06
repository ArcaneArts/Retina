package org.popcraft.chunky;

/** Models the actual provider's failure before Chunky's server-started callback. */
public final class ChunkyProvider {
    public static boolean loaded;

    public static void get() {
        if (!loaded) throw new IllegalStateException("Chunky is not loaded.");
    }
}
