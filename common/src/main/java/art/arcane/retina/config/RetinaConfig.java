package art.arcane.retina.config;

import java.math.BigInteger;
import java.util.Map;

/** Loader- and file-format-independent settings. Changes take effect at process startup. */
public record RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression mcaCompression,
                           boolean allowDirectx, boolean allowMetal, boolean allowVulkan) {
    public enum McaCompression {
        ZLIB("zlib"), LZ4("lz4");
        private final String id;
        McaCompression(String id) { this.id = id; }
        public String id() { return id; }
    }

    public RetinaConfig {
        java.util.Objects.requireNonNull(mcaCompression, "mcaCompression");
    }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression compression) {
        this(mcaParallelism, debug, f3Overlay, compression, true, true, true);
    }

    public boolean worldgenEnabled() { return allowDirectx || allowMetal || allowVulkan; }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay) {
        this(mcaParallelism, debug, f3Overlay, McaCompression.ZLIB);
    }

    public static final RetinaConfig DEFAULT = new RetinaConfig(8, false, true);

    public int regionParallelism() {
        return regionParallelism(Runtime.getRuntime().availableProcessors());
    }

    public int regionParallelism(int processors) {
        if (mcaParallelism > 0) return mcaParallelism;
        long cores = Math.max(1, processors);
        long divisor = mcaParallelism == 0 ? 1 : -(long) mcaParallelism;
        return (int) Math.max(1, (cores + divisor - 1) / divisor);
    }

    public static RetinaConfig from(Map<String, ?> values) {
        return new RetinaConfig(integer(values, "mca_parallelism", DEFAULT.mcaParallelism),
                bool(values, "debug", DEFAULT.debug), bool(values, "f3_overlay", DEFAULT.f3Overlay), compression(values),
                bool(values, "allow_directx", true), bool(values, "allow_metal", true), bool(values, "allow_vulkan", true));
    }

    private static McaCompression compression(Map<String, ?> values) {
        if (!values.containsKey("mca_compression")) return DEFAULT.mcaCompression;
        Object value = values.get("mca_compression");
        for (var codec : McaCompression.values()) if (codec.id().equals(value)) return codec;
        throw new IllegalArgumentException("mca_compression must be \"zlib\" or \"lz4\"");
    }

    private static int integer(Map<String, ?> values, String key, int fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
        } else if (value instanceof BigInteger number && number.bitLength() < 32) {
            return number.intValue();
        }
        throw new IllegalArgumentException(key + " must be a 32-bit integer");
    }

    private static boolean bool(Map<String, ?> values, String key, boolean fallback) {
        if (!values.containsKey(key)) return fallback;
        if (values.get(key) instanceof Boolean value) return value;
        throw new IllegalArgumentException(key + " must be true or false");
    }
}
