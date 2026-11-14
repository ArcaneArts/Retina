package art.arcane.retina.config;

import art.arcane.retina.core.ZlibLevel;

import java.math.BigInteger;
import java.util.Map;

/** Loader- and file-format-independent settings. Changes take effect at process startup. */
public record RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression mcaCompression,
                           int mcaCompressionLevel, boolean lazyCompression, boolean allowDirectx, boolean allowMetal, boolean allowVulkan) {
    public enum McaCompression {
        NONE("none", 3), ZLIB("zlib", 2), LZ4("lz4", 4);
        private final String id;
        private final int codec;
        McaCompression(String id, int codec) { this.id = id; this.codec = codec; }
        public String id() { return id; }
        public int codec() { return codec; }
    }

    public RetinaConfig {
        java.util.Objects.requireNonNull(mcaCompression, "mcaCompression");
        mcaCompressionLevel = Math.max(0, Math.min(9, mcaCompressionLevel));
    }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression compression,
                        int level, boolean allowDirectx, boolean allowMetal, boolean allowVulkan) {
        this(mcaParallelism, debug, f3Overlay, compression, level, true, allowDirectx, allowMetal, allowVulkan);
    }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression compression,
                        boolean allowDirectx, boolean allowMetal, boolean allowVulkan) {
        this(mcaParallelism, debug, f3Overlay, compression, ZlibLevel.DEFAULT, allowDirectx, allowMetal, allowVulkan);
    }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay, McaCompression compression) {
        this(mcaParallelism, debug, f3Overlay, compression, ZlibLevel.DEFAULT, true, true, true);
    }

    /** Supported worlds use Retina with either GPU or native CPU execution. */
    public boolean worldgenEnabled() { return true; }

    public boolean usesLazyCompression() { return lazyCompression && mcaCompression != McaCompression.NONE; }
    public int generationCodec() { return lazyCompression ? McaCompression.NONE.codec() : mcaCompression.codec(); }

    public RetinaConfig(int mcaParallelism, boolean debug, boolean f3Overlay) {
        this(mcaParallelism, debug, f3Overlay, McaCompression.ZLIB);
    }

    public static final RetinaConfig DEFAULT = new RetinaConfig(4, false, true);

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
                compressionLevel(values.get("mca_compression_level")), bool(values, "lazy_compression", true),
                bool(values, "allow_directx", true), bool(values, "allow_metal", true), bool(values, "allow_vulkan", true));
    }

    private static McaCompression compression(Map<String, ?> values) {
        if (!values.containsKey("mca_compression")) return DEFAULT.mcaCompression;
        Object value = values.get("mca_compression");
        for (var codec : McaCompression.values()) if (codec.id().equals(value)) return codec;
        throw new IllegalArgumentException("mca_compression must be \"none\", \"zlib\" or \"lz4\"");
    }

    private static int compressionLevel(Object value) {
        if (value instanceof String || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            return ZlibLevel.parse(value.toString());
        }
        return ZlibLevel.DEFAULT;
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
