package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.TerrainRequest;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.*;

/** Forces a real compile, then compares full column/quart outputs on the GPU. */
public final class RegistryProgramIntegrationChecks {
    /** Reuse an actual saved definition when registry loading consumes the test deadline. */
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        try(var saved=DefinitionFixture.open(Path.of(args[0]),"minecraft:overworld",2)) {
            checkJson(saved.diagnosticProfile());
        }
    }
    static void check(BiomeTerrainProfile profile) throws Exception { checkJson(profile.diagnosticJson()); }

    private static void checkJson(String json) throws Exception {
        var data=JsonParser.parseString(json).getAsJsonObject();
        var nativeTerrain=NativeTerrain.instance();
        for(long seed:new long[]{123456789L,42L,987654321L}) {
            // Execution choice is separate from generation identity: both worlds
            // independently open the very same seed-bound file.
            try(var snapshot=DefinitionFixture.snapshot("minecraft:overworld",data,
                    new TerrainRequest(seed,0,0,-64,384,64,48,.008F,0),-64,384,true,false);
                var reference=snapshot.open(NativeWorldDefinition.ProgramExecution.INTERPRETER);
                var compiled=snapshot.open(NativeWorldDefinition.ProgramExecution.SPECIALIZED);
                var workers=Executors.newFixedThreadPool(8)) {
                var futures=new java.util.ArrayList<Future<?>>();
                for(int i=0;i<24;i++) {
                    if(new long[]{123456789L,42L,987654321L}[i%3]!=seed)continue;
                    final int at=i;
                    futures.add(workers.submit(()->{
                        int x=new int[]{-33,-32,-1,0,31,32}[at%6],z=at/6*53-81;
                        var old=reference.request(x,z);var next=compiled.request(x,z);
                        var a=nativeTerrain.sampleColumns(old);var b=nativeTerrain.sampleColumns(next);
                        require(Arrays.equals(a.heights(),b.heights()),"specialized heights match interpreter at "+x+","+z);
                        require(Arrays.equals(a.packed(),b.packed()),"specialized biomes/column fields match interpreter at "+x+","+z);
                        require(Arrays.equals(a.materials(),b.materials()),"specialized materials match interpreter at "+x+","+z);
                        require(Arrays.equals(nativeTerrain.sampleBiomes(old),nativeTerrain.sampleBiomes(next)),"specialized underground biomes match interpreter at "+x+","+z);
                    }));
                }
                for(var future:futures)future.get();
                var diagnostics=nativeTerrain.gpuDiagnostics(compiled.request(0,0).profile());
                require((diagnostics.status()==2||diagnostics.status()==4)&&diagnostics.compileNanos()>0&&diagnostics.emittedNodes()>0,"actual compiled DAGs serve the selected stages");
            }
        }
        System.out.println("QA_EVT {\"event\":\"gpu_program_specialization_parity\",\"status\":\"pass\",\"context\":{\"chunks\":24,\"seeds\":3,\"biomes\":"+data.getAsJsonArray("biomes").size()+"}}");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
