package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.concurrent.*;

/** Compare the resident GPU index with the reference linear search on real profiles. */
final class RegistryClimateIntegrationChecks {
    static void check(BiomeTerrainProfile profile) throws Exception {
        checkIndex(profile);
        RegistryProgramIntegrationChecks.check(profile);
    }
    static void checkIndex(BiomeTerrainProfile profile) throws Exception {
        var data = JsonParser.parseString(profile.diagnosticJson()).getAsJsonObject();
        data.addProperty("climate_lookup", "linear");
        var nativeTerrain = NativeTerrain.instance();
        for(long seed : new long[]{123456789L,42L,987654321L}) {
            var indexed=data.deepCopy();indexed.remove("climate_lookup");
            try(var indexedWorld=RegistryDefinitionFixture.create(indexed,seed);
                var linear=RegistryDefinitionFixture.create(data,seed);
                var workers=Executors.newFixedThreadPool(8)) {
                var futures=new java.util.ArrayList<Future<?>>();
                for(int i=0;i<32;i++) {
                    if(new long[]{123456789L,42L,987654321L}[i%3]!=seed)continue;
                    final int at=i;
                    futures.add(workers.submit(()->{
                        int x=at%8*97-339,z=at/8*79-173;
                        var indexedRequest=indexedWorld.request(x,z);var linearRequest=linear.request(x,z);
                        var a=nativeTerrain.sampleColumns(indexedRequest);var b=nativeTerrain.sampleColumns(linearRequest);
                        require(Arrays.equals(a.heights(),b.heights()),"indexed heights match linear at "+x+","+z);
                        require(Arrays.equals(a.packed(),b.packed()),"indexed surface biome/ties match linear at "+x+","+z);
                        require(Arrays.equals(a.materials(),b.materials()),"indexed materials match linear at "+x+","+z);
                        require(Arrays.equals(nativeTerrain.sampleBiomes(indexedRequest),nativeTerrain.sampleBiomes(linearRequest)),"depth-aware index matches linear at "+x+","+z);
                    }));
                }
                for(var future:futures)future.get();
            }
        }
        System.out.println("QA_EVT {\"event\":\"climate_gpu_index_parity\",\"status\":\"pass\",\"context\":{\"chunks\":32,\"seeds\":3,\"biomes\":" + profile.biomes().size() + "}}");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
