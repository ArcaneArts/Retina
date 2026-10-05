package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.concurrent.*;

/** Forces a real compile, then compares full column/quart outputs on the GPU. */
final class RegistryProgramIntegrationChecks {
    static void check(BiomeTerrainProfile profile) throws Exception {
        var data=JsonParser.parseString(profile.json()).getAsJsonObject();
        var nativeTerrain=NativeTerrain.instance();
        data.addProperty("program_execution","interpreter");int reference=nativeTerrain.registerProfile(data.toString());
        data.addProperty("program_execution","specialized");int compiled=nativeTerrain.registerProfile(data.toString());
        try(var workers=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<24;i++) {
                final int at=i;
                futures.add(workers.submit(()->{
                    long seed=new long[]{123456789L,42L,987654321L}[at%3];
                    int x=new int[]{-33,-32,-1,0,31,32}[at%6],z=at/6*53-81;
                    var old=new TerrainRequest(seed,x,z,-64,384,64,48,.008f,reference);
                    var next=new TerrainRequest(seed,x,z,-64,384,64,48,.008f,compiled);
                    var a=nativeTerrain.sampleColumns(old);var b=nativeTerrain.sampleColumns(next);
                    require(Arrays.equals(a.heights(),b.heights()),"specialized heights match interpreter at "+x+","+z);
                    require(Arrays.equals(a.packed(),b.packed()),"specialized biomes/column fields match interpreter at "+x+","+z);
                    require(Arrays.equals(a.materials(),b.materials()),"specialized materials match interpreter at "+x+","+z);
                    require(Arrays.equals(nativeTerrain.sampleBiomes(old),nativeTerrain.sampleBiomes(next)),"specialized underground biomes match interpreter at "+x+","+z);
                }));
            }
            for(var f:futures)f.get();
        }
        var diagnostics=nativeTerrain.gpuDiagnostics(compiled);
        require((diagnostics.status()==2 || diagnostics.status()==4) && diagnostics.compileNanos()>0 && diagnostics.emittedNodes()>0,"actual compiled DAGs serve the selected stages");
        System.out.println("QA_EVT {\"event\":\"gpu_program_specialization_parity\",\"status\":\"pass\",\"context\":{\"chunks\":24,\"seeds\":3,\"biomes\":"+profile.biomes().size()+",\"nodes\":"+diagnostics.nodes()+",\"emitted\":"+diagnostics.emittedNodes()+"}}");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
