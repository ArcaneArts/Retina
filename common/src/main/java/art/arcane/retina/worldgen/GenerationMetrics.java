package art.arcane.retina.worldgen;

import java.util.ArrayDeque;
import java.util.Deque;

/** Chunk-mode production rate; MCA latency and profiled stages from the last 20 generated regions. */
public final class GenerationMetrics {
    private static final long WINDOW_NANOS = 5_000_000_000L;
    public static final int REGION_WINDOW = 20;
    private final long started = System.nanoTime();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<Sample> timings = new ArrayDeque<>();
    private final Deque<RegionSample> regionTimings = new ArrayDeque<>();
    private int inFlight;
    private long total, failures, regions, previewRegions, previewCacheHits, promotions;
    private NativeTimings nativeTimings = NativeTimings.EMPTY;
    private NativeTimings nativeBaseline = NativeTimings.EMPTY;

    public synchronized void startNativeTimings(NativeTimings baseline) {
        nativeBaseline=baseline; nativeTimings=NativeTimings.EMPTY;
    }
    public synchronized void nativeTimings(NativeTimings value) { nativeTimings = value.since(nativeBaseline); }
    public synchronized void begin() { inFlight++; }
    public synchronized void completed(long elapsedNanos, long nativeNanos, long conversionNanos) {
        inFlight--;
        record(1, elapsedNanos, nativeNanos, conversionNanos);
    }
    public synchronized void completedRegion(int chunks, long elapsedNanos) {
        completedRegion(chunks,elapsedNanos,NativeTimings.EMPTY);
    }
    public synchronized void completedRegion(int chunks, long elapsedNanos, NativeTimings stages) {
        inFlight--;
        if(chunks==0)return;
        regions++;
        recordRegion(chunks,elapsedNanos,stages);
    }
    public synchronized void completedPreviewRegion(int chunks, long elapsedNanos, NativeTimings stages) {
        completedPreviewRegion(chunks,elapsedNanos,stages,0);
    }
    public synchronized void completedPreviewRegion(int chunks, long elapsedNanos, NativeTimings stages, long columnCacheNanos) {
        inFlight--;
        if(chunks==0)return;
        previewRegions++;
        recordRegion(chunks,elapsedNanos,stages,columnCacheNanos);
    }
    public synchronized void previewCacheHit() { previewCacheHits++; }
    public synchronized void completedPromotion(long elapsedNanos) {
        inFlight--; regions++; promotions++;
        // Moving cached data measures publication, not terrain generation.
    }
    private void recordRegion(int chunks, long elapsedNanos, NativeTimings stages) {
        recordRegion(chunks,elapsedNanos,stages,0);
    }
    private void recordRegion(int chunks, long elapsedNanos, NativeTimings stages, long columnCacheNanos) {
        record(chunks,elapsedNanos,elapsedNanos,0);
        regionTimings.addLast(new RegionSample(chunks,elapsedNanos,stages,columnCacheNanos));
        if(regionTimings.size()>REGION_WINDOW)regionTimings.removeFirst();
    }
    private void record(int chunks, long elapsedNanos, long nativeNanos, long conversionNanos) {
        total+=chunks;
        long now=System.nanoTime();
        var sample=new Sample(now,chunks,elapsedNanos,nativeNanos,conversionNanos);
        samples.addLast(sample);timings.addLast(sample);
        if(timings.size()>128)timings.removeFirst();
        prune(now);
    }
    public synchronized void failed() { inFlight--; failures++; }
    public synchronized Snapshot snapshot() {
        long now=System.nanoTime();prune(now);
        long elapsed=0,nativeTime=0,conversion=0,timedChunks=0;
        for(var sample:timings) {
            timedChunks+=sample.chunks;elapsed+=sample.elapsed;
            nativeTime+=sample.nativeTime;conversion+=sample.conversion;
        }
        double windowSeconds=Math.max(.001,Math.min(WINDOW_NANOS,now-started)/1e9);
        double divisor=Math.max(1,timedChunks)*1e6;
        double msPerChunk=elapsed/divisor,rate=samples.stream().mapToLong(Sample::chunks).sum()/windowSeconds;
        long regionElapsed=0,regionChunks=0,gpuColumns=0,gpuJobs=0,columnCache=0;
        int flags=0;long[] stages=new long[NativeTimings.STAGES];
        for(var sample:regionTimings) {
            regionElapsed+=sample.elapsed;regionChunks+=sample.chunks;columnCache+=sample.columnCacheNanos;
            gpuColumns+=sample.stages.gpuColumns();gpuJobs+=sample.stages.gpuJobs();flags|=sample.stages.flags();
            for(int i=0;i<stages.length;i++)stages[i]+=sample.stages.nanos(i);
        }
        if(!regionTimings.isEmpty()) {
            msPerChunk=regionElapsed/(Math.max(1,regionChunks)*1e6);
            rate=regionChunks*1e9/Math.max(1,regionElapsed);
        }
        return new Snapshot(rate,msPerChunk,nativeTime/divisor,conversion/divisor,inFlight,total,failures,regions,
                regionElapsed/(Math.max(1,regionTimings.size())*1e6),previewRegions,previewCacheHits,promotions,nativeTimings,
                regionTimings.size(),new NativeTimings(flags,regionChunks,gpuColumns,gpuJobs,stages),columnCache/(Math.max(1,regionTimings.size())*1e6));
    }
    private void prune(long now) {
        while(!samples.isEmpty() && now-samples.peekFirst().completedAt>WINDOW_NANOS)samples.removeFirst();
    }
    private record Sample(long completedAt,int chunks,long elapsed,long nativeTime,long conversion) { }
    private record RegionSample(int chunks,long elapsed,NativeTimings stages,long columnCacheNanos) { }
    public record Snapshot(double chunksPerSecond,double msPerChunk,double nativeMs,double conversionMs,int inFlight,
                           long total,long failures,long regions,double averageRegionMs,long previewRegions,long previewCacheHits,
                           long promotions,NativeTimings stages,int regionSamples,NativeTimings regionStages,double columnCacheMs) {
        public Snapshot(double rate,double ms,double nativeMs,double conversion,int inFlight,long total,long failures,long regions,
                        double averageRegionMs,long previews,long hits,long promotions) {
            this(rate,ms,nativeMs,conversion,inFlight,total,failures,regions,averageRegionMs,previews,hits,promotions,NativeTimings.EMPTY);
        }
        public Snapshot(double rate,double ms,double nativeMs,double conversion,int inFlight,long total,long failures,long regions,
                        double averageRegionMs,long previews,long hits,long promotions,NativeTimings stages) {
            this(rate,ms,nativeMs,conversion,inFlight,total,failures,regions,averageRegionMs,previews,hits,promotions,stages,0,NativeTimings.EMPTY,0);
        }
        public double columnCachePercent() { return 100*columnCacheMs/Math.max(.000001,averageRegionMs); }
        public double regionStageMs(int stage) { return regionStages.nanos(stage)/(Math.max(1,regionSamples)*1e6); }
        public double regionStagePercent(int stage) { return 100*regionStageMs(stage)/Math.max(.000001,averageRegionMs); }
    }
}
