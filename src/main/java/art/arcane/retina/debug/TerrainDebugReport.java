package art.arcane.retina.debug;

import art.arcane.retina.worldgen.TerrainStatsPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static art.arcane.retina.worldgen.NativeTimings.*;

/** Shared text formatter so the F3 report can be checked without a rendered client. */
public final class TerrainDebugReport {
    private static final String LABEL="§7", DATA="§f", RUST="§b", GPU="§d", HEADER="§6§l", RESET="§r";
    private static final String[] GPU_HOST={"GPU queue","Command encoding","GPU work / readback"};
    private static final String[] PLANS={"Structure planning","Decoration planning (CPU + GPU)","Ore planning (CPU + GPU)"};
    private static final String[] GPU_DEVICE={"Height / climate","Biome sites","Column surfaces","Cave density","Cave mask"};
    private static final String[] GPU_LAKES={"Lake candidates","Lake density probes / cache","Lake level reduction"};
    private static final String[] WORKERS={"Base terrain","Ores","Cave decorations","Vegetation / sediments","Structure placement","Snow","NBT encoding","Zlib compression"};
    private TerrainDebugReport() { }
    public static List<String> lines(TerrainStatsPayload payload) {
        var stats=payload.stats();var lines=new ArrayList<String>();boolean mca=payload.mode().equals("mca");
        lines.add("§b§lRetina "+payload.mode().toUpperCase(Locale.ROOT)+RESET);
        if(mca)lines.add(LABEL+"Average region: "+DATA+format("%.2f ms",stats.averageRegionMs())+LABEL+" (last "+DATA+stats.regionSamples()+LABEL+" / 20)");
        lines.add(LABEL+"Chunks/sec: §a"+format("%.1f",stats.chunksPerSecond())+(mca?LABEL+" from region average":LABEL+" (5s)"));
        lines.add(LABEL+(mca?"Amortized chunk: ":"Chunk time: ")+DATA+format("%.3f ms",stats.msPerChunk()));
        lines.add(LABEL+"Regions: §a"+stats.regions()+LABEL+"   In flight: §e"+stats.inFlight());
        lines.add(LABEL+"Temporary regions: §a"+stats.previewRegions()+LABEL+"   Hits: §a"+stats.previewCacheHits()+LABEL+"   Promoted: §a"+stats.promotions());
        lines.add(LABEL+"Terrain chunks: §a"+stats.total()+LABEL+"   Failed: "+(stats.failures()>0?"§c":"§a")+stats.failures());
        lines.add(GPU+"GPU: "+DATA+payload.backend());
        var gpu=payload.diagnostics();
        if(gpu.nodes()>0) {
            lines.add(GPU+"GPU programs: "+(gpu.status()==3?"§c":DATA)+gpu.execution());
            if(gpu.compileNanos()>0)lines.add(GPU+"GPU compilation: "+DATA+format("%.1f ms",gpu.compileNanos()/1e6));
            lines.add(GPU+"Graph nodes: "+DATA+gpu.emittedNodes()+LABEL+" emitted / "+DATA+gpu.nodes()+LABEL+" registered");
            if(gpu.horizontalFields()>0)lines.add(GPU+"Horizontal cache fields: "+DATA+gpu.horizontalFields());
        }
        if(gpu.readbackBytes()>0) {
            lines.add(GPU+"GPU profile upload total: "+DATA+format("%.2f MiB",gpu.uploadBytes()/1048576.0));
            lines.add(GPU+"GPU profile readback total: "+DATA+format("%.2f MiB",gpu.readbackBytes()/1048576.0));
        }
        if(mca && stats.regionSamples()>0) {
            lines.add(HEADER+"GPU host (% of average region)"+RESET);
            for(int stage=QUEUE;stage<=WAIT_COPY;stage++)lines.add(share(GPU,GPU_HOST[stage],stats.regionStagePercent(stage),false));
            lines.add(share(GPU,"GPU lighting / upload / readback",stats.regionStagePercent(LIGHT_HOST),false));
            lines.add(HEADER+"Native (% of average region; ~ worker estimates)"+RESET);
            for(int stage=STRUCTURE_PLAN;stage<=ORE_PLAN;stage++)lines.add(share(stage>=VEGETATION_PLAN?GPU:RUST,PLANS[stage-STRUCTURE_PLAN],stats.regionStagePercent(stage),false));
            for(int stage=ASSEMBLY;stage<=COMPRESS;stage++)lines.add(share(RUST,WORKERS[stage-ASSEMBLY],stats.regionStagePercent(stage),true));
            lines.add(share(RUST,"File I/O",stats.regionStagePercent(IO),false));
            lines.add(share("§9","Java column cache",stats.columnCachePercent(),false));
            double accounted=stats.columnCacheMs();
            accounted+=stats.regionStageMs(LIGHT_HOST);
            for(int stage=0;stage<STAGES;stage++)if(stage<HEIGHT || stage>=STRUCTURE_PLAN && stage<MATERIALS)accounted+=stats.regionStageMs(stage);
            double other=100*Math.max(0,stats.averageRegionMs()-accounted)/Math.max(.000001,stats.averageRegionMs());
            lines.add(share(LABEL,"Other / waiting",other,false));
            lines.add(HEADER+"GPU device (% of region; overlaps host)"+RESET);
            if(stats.regionStages().gpuMeasured()) {for(int stage=HEIGHT;stage<=CAVE_MASK;stage++)
                lines.add(share(GPU,GPU_DEVICE[stage-HEIGHT],stats.regionStagePercent(stage),false));
                for(int stage=LAKE_CANDIDATES;stage<=LAKE_REDUCE;stage++)
                    lines.add(share(GPU,GPU_LAKES[stage-LAKE_CANDIDATES],stats.regionStagePercent(stage),false));
                lines.add(share(GPU,"Material layers",stats.regionStagePercent(MATERIALS),false));
                lines.add(share(GPU,"Aquifer fields",stats.regionStagePercent(AQUIFER_FIELDS),false));
                lines.add(share(GPU,"Aquifer fluids / barriers",stats.regionStagePercent(AQUIFER_MASK),false));
                lines.add(share(GPU,"Feature counts",stats.regionStagePercent(FEATURE_COUNTS),false));
                if(stats.regionStages().providerMeasured())lines.add(share(GPU,"Provider noise",stats.regionStagePercent(PROVIDER_NOISE),false));
                if(stats.regionStages().oreMeasured())lines.add(share(GPU,"Ore masks",stats.regionStagePercent(ORE_MASK),false));
                else lines.add(LABEL+"Ore masks: "+DATA+"not measured");
                if(stats.regionStages().lightingMeasured()) {
                    lines.add(share(GPU,"Lighting: sky / sources",stats.regionStagePercent(LIGHT_SKY),false));
                    lines.add(share(GPU,"Lighting: propagation",stats.regionStagePercent(LIGHT_SPREAD),false));
                    lines.add(share(GPU,"Lighting: packing",stats.regionStagePercent(LIGHT_PACK),false));
                }}
            else lines.add(LABEL+"Device timestamps unavailable");
        } else if(!mca && stats.stages().chunks()>0) {
            lines.add(HEADER+"Rust (% of average chunk time)"+RESET);
            for(int stage=ASSEMBLY;stage<=COMPRESS;stage++)lines.add(share(RUST,WORKERS[stage-ASSEMBLY],100*stats.stages().chunkMs(stage)/Math.max(.000001,stats.msPerChunk()),false));
            lines.add(LABEL+"Convert: "+DATA+format("%.3f ms",stats.conversionMs()));
            if(stats.stages().gpuMeasured()) {
                for(int stage=LAKE_CANDIDATES;stage<=LAKE_REDUCE;stage++)
                    lines.add(share(GPU,GPU_LAKES[stage-LAKE_CANDIDATES],100*stats.stages().gpuChunkMs(stage)/Math.max(.000001,stats.msPerChunk()),false));
                lines.add(share(GPU,"Material layers",100*stats.stages().gpuChunkMs(MATERIALS)/Math.max(.000001,stats.msPerChunk()),false));
                lines.add(share(GPU,"Aquifer fields",100*stats.stages().gpuChunkMs(AQUIFER_FIELDS)/Math.max(.000001,stats.msPerChunk()),false));
                lines.add(share(GPU,"Aquifer fluids / barriers",100*stats.stages().gpuChunkMs(AQUIFER_MASK)/Math.max(.000001,stats.msPerChunk()),false));
                lines.add(share(GPU,"Feature counts",100*stats.stages().chunkMs(FEATURE_COUNTS)/Math.max(.000001,stats.msPerChunk()),false));
                if(stats.stages().providerMeasured())lines.add(share(GPU,"Provider noise",100*stats.stages().chunkMs(PROVIDER_NOISE)/Math.max(.000001,stats.msPerChunk()),false));
            }
        }
        return List.copyOf(lines);
    }
    private static String share(String category,String name,double percent,boolean estimate) {
        String color=percent>=50?"§c":percent>=25?"§6":percent>=10?"§e":"§a";
        return category+name+LABEL+": "+color+(estimate?"~":"")+format("%.1f%%",percent)+RESET;
    }
    private static String format(String pattern,Object... values) { return String.format(Locale.ROOT,pattern,values); }
}
