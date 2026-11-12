package art.arcane.retina.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Java 17 binding for the same native operations used by FFM. */
final class JniDefinitionTransport implements DefinitionTransport {
    static final JniDefinitionTransport INSTANCE = new JniDefinitionTransport();

    private JniDefinitionTransport() { }

    static native int configureBackend(int code);
    static native void configureDebug(int enabled);
    static native void flushPipelineCaches();

    @Override
    public long open(Path path) {
        return openNative(pathBytes(path));
    }

    @Override
    public long create(Path catalog, String variant, long seed, Path worldRoot) {
        return createNative(pathBytes(catalog), bytes(variant), seed, pathBytes(worldRoot));
    }

    @Override
    public String metadata(long handle) {
        return new String(metadataNative(handle), StandardCharsets.UTF_8);
    }

    @Override
    public int bind(long handle, String key, int compression) {
        return bindNative(handle, bytes(key), compression, -1);
    }

    @Override
    public int bind(long handle, String key, int compression, NativeWorldDefinition.ProgramExecution execution) {
        return bindNative(handle, bytes(key), compression, execution.ordinal());
    }

    @Override
    public TerrainRequest request(long handle, String key, int x, int z) {
        long[] result = requestNative(handle, bytes(key), x, z);
        return new TerrainRequest(result[0], (int) result[1], (int) result[2], (int) result[3], (int) result[4],
                Float.intBitsToFloat((int) result[5]), Float.intBitsToFloat((int) result[6]),
                Float.intBitsToFloat((int) result[7]), (int) result[8]);
    }

    @Override
    public NativeWorldDefinition.RegionResult generateRegion(long handle, String key, int x, int z, Path destination) {
        long[] report = regionNative(handle, bytes(key), x, z, pathBytes(destination));
        return new NativeWorldDefinition.RegionResult(report[0], report[1], report[2]);
    }

    @Override
    public byte[] chunk(long handle, String key, int x, int z) {
        return chunkNative(handle, bytes(key), x, z);
    }

    @Override
    public short[] biomes(TerrainRequest request) {
        return biomesNative(request.seed(), request.chunkX(), request.chunkZ(), request.minY(), request.height(),
                request.baseHeight(), request.amplitude(), request.frequency(), request.profile());
    }

    @Override
    public int[] biomePoints(TerrainRequest request, int[] xyz) {
        return pointsNative(request.seed(), request.chunkX(), request.chunkZ(), request.minY(), request.height(),
                request.baseHeight(), request.amplitude(), request.frequency(), request.profile(), xyz);
    }

    @Override
    public String diagnosticProfile(long handle, String key) {
        return new String(profileNative(handle, bytes(key)), StandardCharsets.UTF_8);
    }

    @Override
    public void close(long handle) {
        closeNative(handle);
    }

    private static byte[] pathBytes(Path path) {
        return bytes(path.toAbsolutePath().toString());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static native long openNative(byte[] path);
    private static native long createNative(byte[] catalog, byte[] variant, long seed, byte[] root);
    private static native byte[] metadataNative(long handle);
    private static native int bindNative(long handle, byte[] key, int compression, int execution);
    private static native long[] requestNative(long handle, byte[] key, int x, int z);
    private static native long[] regionNative(long handle, byte[] key, int x, int z, byte[] destination);
    private static native byte[] chunkNative(long handle, byte[] key, int x, int z);
    private static native short[] biomesNative(long seed, int x, int z, int minY, int height, float baseHeight,
                                               float amplitude, float frequency, int profile);
    private static native int[] pointsNative(long seed, int x, int z, int minY, int height, float baseHeight,
                                             float amplitude, float frequency, int profile, int[] xyz);
    private static native byte[] profileNative(long handle, byte[] key);
    private static native void closeNative(long handle);
}
