package art.arcane.retina.core;

import java.nio.file.Path;

/** Definition operations with no dependency on a Java native-access API or Minecraft. */
interface DefinitionTransport {
    long open(Path path);
    long create(Path catalog, String variant, long seed, Path worldRoot);
    String metadata(long handle);
    int bind(long handle, String key, int compression);
    int bind(long handle, String key, int compression, NativeWorldDefinition.ProgramExecution execution);
    TerrainRequest request(long handle, String key, int x, int z);
    NativeWorldDefinition.RegionResult generateRegion(long handle, String key, int x, int z, Path destination);
    NativeWorldDefinition.RegionResult publishRegion(long handle, String key, int x, int z, Path preview, Path destination);
    byte[] chunk(long handle, String key, int x, int z);
    short[] biomes(TerrainRequest request);
    int[] biomePoints(TerrainRequest request, int[] xyz);
    String diagnosticProfile(long handle, String key);
    void close(long handle);
}
