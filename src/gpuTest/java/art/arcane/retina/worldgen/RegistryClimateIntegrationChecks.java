package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.concurrent.*;

/** Compare the resident GPU index with the reference linear search on real profiles. */
final class RegistryClimateIntegrationChecks {
    static void check(BiomeTerrainProfile profile) throws Exception {
        var data = JsonParser.parseString(profile.json()).getAsJsonObject();
        data.addProperty("climate_lookup", "linear");
        var nativeTerrain = NativeTerrain.instance();
        int linear = nativeTerrain.registerProfile(data.toString());
        try (var workers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 32; i++) {
                final int at = i;
                futures.add(workers.submit(() -> {
                    long seed = new long[]{123456789L, 42L, 987654321L}[at % 3];
                    int x = at % 8 * 97 - 339, z = at / 8 * 79 - 173;
                    var indexedRequest = new TerrainRequest(seed, x, z, -64, 384, 64, 48, .008f, profile.nativeId());
                    var linearRequest = new TerrainRequest(seed, x, z, -64, 384, 64, 48, .008f, linear);
                    var indexedColumns = nativeTerrain.sampleColumns(indexedRequest);
                    var linearColumns = nativeTerrain.sampleColumns(linearRequest);
                    require(Arrays.equals(indexedColumns.heights(), linearColumns.heights()), "indexed heights match linear at " + x + "," + z);
                    require(Arrays.equals(indexedColumns.packed(), linearColumns.packed()), "indexed surface biome/ties match linear at " + x + "," + z);
                    require(Arrays.equals(indexedColumns.materials(), linearColumns.materials()), "indexed materials match linear at " + x + "," + z);
                    require(Arrays.equals(nativeTerrain.sampleBiomes(indexedRequest), nativeTerrain.sampleBiomes(linearRequest)), "depth-aware index matches linear at " + x + "," + z);
                }));
            }
            for (var future : futures) future.get();
        }
        System.out.println("QA_EVT {\"event\":\"climate_gpu_index_parity\",\"status\":\"pass\",\"context\":{\"chunks\":32,\"seeds\":3,\"biomes\":" + profile.biomes().size() + "}}");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
