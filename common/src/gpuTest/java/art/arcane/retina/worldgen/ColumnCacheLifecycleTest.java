package art.arcane.retina.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;

/** Small real cache lifecycle check: 16-block-tall stone fixtures, no feature/registry export. */
public final class ColumnCacheLifecycleTest {
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static boolean same(NativeTerrain.Columns a,NativeTerrain.Columns b) {
        return Arrays.equals(a.heights(),b.heights()) && Arrays.equals(a.packed(),b.packed())
                && Arrays.equals(a.materials(),b.materials());
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var request=new TerrainRequest(123456789,0,0,0,16,6,4,.008F,0);
        var metrics=new GenerationMetrics();
        var cache=new TemporaryRegions(request,"minecraft:plains",null,metrics,2,1);
        var save=Files.createTempDirectory("retina-column-promotion-");
        var a=new ChunkPos(-1,0);var b=new ChunkPos(0,0);var c=new ChunkPos(32,0);
        Path folder=cache.folder();
        try {
            var before=cache.columns(a);
            require(Files.size(folder.resolve("r.-1.0.columns.z"))>0,"native sidecar exists before preview completion");
            require(metrics.snapshot().columnCacheMs()>0,"cache write time reaches existing F3 metric");
            cache.columns(b);
            long regions=metrics.snapshot().previewRegions();
            require(same(before,cache.columns(a)),"cold bulk reload preserves every column field");
            require(metrics.snapshot().previewRegions()==regions,"cold reload does not regenerate terrain");
            cache.columns(c);
            require(cache.size()==2 && !Files.exists(folder.resolve("r.0.0.columns.z"))
                    && !Files.exists(folder.resolve("r.0.0.mca")),"LRU deletes MCA and column sidecars together");
            Path destination=save.resolve("r.-1.0.mca");
            require(cache.publishIfPresent(a,destination).generated()==1024,"preview promotes existing MCA");
            var missing=new ChunkPos(-2,0);
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-cache-check",Level.OVERWORLD,"chunk"),save,false)) {
                require(storage.read(a).equals(cache.read(a)),"promoted chunk exactly matches cached NBT");
                var edited=storage.read(a);edited.putString("cache_test","player edit");storage.write(a,edited);
                storage.write(missing,null);
            }
            byte[] prior=Files.readAllBytes(destination);
            var report=cache.publishIfPresent(missing,destination);
            require(report.generated()==1 && report.preserved()==1023 && report.gpuNanos()==0,"partial promotion fills only missing slots");
            byte[] after=Files.readAllBytes(destination);
            require(Arrays.equals(prior,8192,prior.length,after,8192,prior.length),"existing compressed records remain intact");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-cache-check",Level.OVERWORLD,"chunk"),save,false)) {
                require(storage.read(a).getStringOr("cache_test","").equals("player edit"),"player edits survive promotion");
                require(storage.read(missing).equals(cache.read(missing)),"restored slot exactly matches cached NBT");
            }
            var lease=cache.prepare(a);
            cache.close();require(Files.exists(folder),"shutdown retains an active lease");
            lease.close();require(!Files.exists(folder),"last lease deletes all cache companions");
        } finally {
            cache.close();
            try(var files=Files.walk(save)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        }
        var failureMetrics=new GenerationMetrics();
        var failed=new TemporaryRegions(request,"minecraft:plains",null,failureMetrics,1,1);
        Path failedFolder=failed.folder();
        try {
            // Make the actual sidecar write fail after the MCA succeeds, without a preflight gate.
            Files.createDirectory(failedFolder.resolve("r.0.0.columns.z"));
            boolean rejected=false;
            try{failed.columns(b);}catch(java.io.UncheckedIOException expected){rejected=true;}
            require(rejected && failed.size()==0 && failureMetrics.snapshot().failures()==1
                    && failureMetrics.snapshot().inFlight()==0,"native cache I/O failure releases the failed entry");
            require(!Files.exists(failedFolder.resolve("r.0.0.mca")),"failed cache entry leaves no orphan MCA");
        } finally{failed.close();}
        require(!Files.exists(failedFolder),"failed cache folder cleaned up");
        System.out.println("QA_EVT {\"event\":\"column_cache_lifecycle\",\"status\":\"pass\"}");
    }
}
