package org.popcraft.chunky;

/** Models the actual provider's failure before Chunky's server-started callback. */
public final class ChunkyProvider {
    private static Object instance;

    public static Object get() {
        if (instance == null) throw new IllegalStateException("Chunky is not loaded.");
        return instance;
    }

    public static void register(Object value) { instance = value; }
    public static void unregister() { instance = null; }
}
