package art.arcane.retina.core;

import java.nio.file.Path;
import java.util.Objects;

/** Coordinates open region handles and the native background writer. */
public final class LazyRegionCompression {
    private static final System.Logger LOG = System.getLogger(LazyRegionCompression.class.getName());
    private LazyRegionCompression() { }

    @FunctionalInterface public interface Open<T> {
        T open() throws java.io.IOException;
    }

    /** Implemented by the Minecraft RegionFile mixins. */
    public interface Handle {
        void retina$attachCompressionPin(Path path);
    }

    public static <T> T open(Path path, Open<T> operation) throws java.io.IOException {
        pin(path);
        boolean attached = false;
        try {
            T file = operation.open();
            ((Handle) file).retina$attachCompressionPin(path);
            attached = true;
            return file;
        } finally {
            if (!attached) release(path);
        }
    }

    public static void pin(Path path) { call(path, 0, 0, 0, 0, 0); }

    public static void release(Path path) { call(path, 1, 0, 0, 0, 0); }

    public static void enqueue(Path path, int x, int z, int codec, int level, boolean preview) {
        if (codec == 3) return;
        try {
            call(path, preview ? 3 : 2, x, z, codec, level);
        } catch (RuntimeException error) {
            // The raw region is already durable. Failure to reclaim space cannot
            // make a successfully published chunk fail to load.
            LOG.log(System.Logger.Level.WARNING, "Could not queue lazy MCA compression for " + path, error);
        }
    }

    public static void cancel(Path path, boolean subtree) {
        call(path, subtree ? 5 : 4, 0, 0, 0, 0);
    }

    public record Counts(long rawChunks, long encodedChunks, long lazyLiveChunks,
                         long lazyPreviewChunks, long reclaimedBytes) { }

    public static Counts counts() {
        long[] values = NativeLibrary.definitionTransport().lazyRegionCounts();
        return new Counts(values[0], values[1], values[2], values[3], values[4]);
    }

    private static void call(Path path, int action, int x, int z, int codec, int level) {
        Objects.requireNonNull(path);
        NativeLibrary.definitionTransport().lazyRegion(path, action, x, z, codec, level);
    }
}
