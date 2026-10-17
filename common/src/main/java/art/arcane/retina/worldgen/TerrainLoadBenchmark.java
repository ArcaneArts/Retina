package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in headless activation measurement for already generated benchmark files. */
final class TerrainLoadBenchmark {
    private static boolean checked;

    static void check(MinecraftServer server) {
        if(!Boolean.getBoolean("retina.qa.loadBenchmark") || checked || server.getTickCount()<20) return;
        checked=true;
        var level=server.overworld();
        if(!(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator))
            throw new IllegalStateException("Load benchmark requires a Retina world");
        boolean reopen=Boolean.getBoolean("retina.qa.loadBenchmark.reopen");
        var samples=new ArrayList<Map<String,Object>>();
        // Include new interior chunks and both sides of two actual MCA seams.
        // The spawn at (16,16) and its immediate neighbors are omitted.
        for(int x:new int[]{-16,-2,-1,0,1,8,24,30,31,32,33,48}) {
            long start=System.nanoTime();
            var chunk=level.getChunk(x,16);
            double ms=(System.nanoTime()-start)/1e6;
            if(!chunk.isLightCorrect()) throw new IllegalStateException("Chunk lighting did not activate: "+x+",16");
            if(chunk.getHeight(Heightmap.Types.WORLD_SURFACE,8,8)<level.getMinY())
                throw new IllegalStateException("Loaded chunk has no terrain: "+x+",16");
            samples.add(Map.of("chunk_x",x,"chunk_z",16,"activation_ms",ms,
                "boundary_chunk",Math.floorMod(x,32)==0 || Math.floorMod(x,32)==31));
        }
        var edit=new BlockPos(256,128,256);
        if(reopen) {
            if(!level.getBlockState(edit).is(Blocks.DIAMOND_BLOCK)) throw new IllegalStateException("Saved load-benchmark edit was lost");
        } else level.setBlockAndUpdate(edit,Blocks.DIAMOND_BLOCK.defaultBlockState());
        var stats=generator.metrics().snapshot();
        if(stats.previewRegions()!=0 || stats.total()!=0 || stats.failures()!=0 || stats.inFlight()!=0)
            throw new IllegalStateException("Load benchmark regenerated terrain or failed: "+stats);
        if(generator.bendMode() && generator.profile()!=null && generator.profile().nativeId()!=-1)
            throw new IllegalStateException("Bend load benchmark initialized Rust");
        var result=Map.of("mode",generator.mode(),"reopen",reopen,"generated_chunks",stats.total(),
            "samples",samples,"edit_preserved",reopen,"jvm_uptime_ms",java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime(),
            "scope","synchronous full LevelChunk activation, including normal boundary lighting; excludes rendering");
        try {
            var path=server.getWorldPath(LevelResource.ROOT).resolve("retina-load-benchmark-"+(reopen?"reopen":"first")+".json");
            Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
        } catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_backend_load_benchmark\",\"status\":\"pass\",\"context\":{\"mode\":\"{}\",\"chunks\":{},\"reopen\":{},\"generated_chunks\":0}}",
            generator.mode(),samples.size(),reopen);
        if(server.isDedicatedServer()) server.halt(false);
    }
}
