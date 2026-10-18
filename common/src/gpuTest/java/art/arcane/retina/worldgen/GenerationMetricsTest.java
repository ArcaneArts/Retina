package art.arcane.retina.worldgen;

import art.arcane.retina.debug.TerrainDebugReport;
import static art.arcane.retina.worldgen.NativeTimings.*;

/** Deterministic rolling-window and display checks, invoked by gpuTest. */
public final class GenerationMetricsTest {
    public static void main(String[] args) { run(); }
    static void run() {
        var initialClock = new java.util.concurrent.atomic.AtomicLong();
        var metrics = new GenerationMetrics(initialClock::get);
        initialClock.set(1_000_000_000L);
        metrics.begin();
        metrics.completedRegion(1024, 1_000_000, stages(1_000_000));
        var first = metrics.snapshot();
        require(first.regionSamples() == 1, "average starts with available samples");
        close(first.averageRegionMs(), 1, "first region average");
        for (int i = 2; i <= 26; i++) {
            metrics.begin();
            long nanos = i * 1_000_000L;
            if (i % 2 == 0) metrics.completedPreviewRegion(1024, nanos, stages(nanos),nanos/10);
            else metrics.completedRegion(1024, nanos, stages(nanos));
        }
        var window = metrics.snapshot();
        require(window.regionSamples() == 20 && window.regionStages().chunks() == 20 * 1024, "only the last 20 generated regions contribute");
        close(window.averageRegionMs(), 16.5, "rolling average drops six oldest regions");
        close(window.p95RegionMs(), 25, "p95 uses the same rolling region window");
        close(window.msPerChunk(), 16.5 / 1024, "amortized time uses the region window");
        close(window.chunksPerSecond(), 26 * 1024, "throughput uses all completions in the wall window");
        close(window.regionStagePercent(NBT), 25, "worker share uses average region time");
        close(window.columnCacheMs(), .85, "cache writes use the same rolling window");
        close(window.columnCachePercent(), 100*.85/16.5, "column cache shares region latency");
        metrics.begin(); metrics.completedPromotion(1);
        metrics.begin(); metrics.completedRegion(0, 999_000_000, NativeTimings.EMPTY);
        var unchanged = metrics.snapshot();
        close(unchanged.averageRegionMs(), 16.5, "promotion and existing saves do not skew generation speed");
        require(unchanged.regionSamples() == 20 && unchanged.inFlight() == 0 && unchanged.total() == 26 * 1024, "cached terrain is not counted twice");
        var partial = new GenerationMetrics(initialClock::get);
        initialClock.addAndGet(1_000_000_000L);
        partial.begin(); partial.completedRegion(1024, 1_000_000);
        partial.begin(); partial.completedRegion(1, 3_000_000);
        close(partial.snapshot().averageRegionMs(), 2, "partial regions are valid latency samples");
        close(partial.snapshot().chunksPerSecond(), 1025, "partial regions use actual chunk counts per wall second");
        close(partial.snapshot().msPerChunk(), 4.0 / 1025, "weighted partial-region amortization");

        var lines = TerrainDebugReport.lines(new TerrainStatsPayload(true, "Metal test", "mca", window));
        require(lines.stream().anyMatch(s -> s.contains("Region latency:") && s.contains("16.50 ms mean") && s.contains("25.00 ms p95") && s.contains("20")), "display reports rolling mean, p95 and sample count");
        require(lines.stream().anyMatch(s -> s.contains("Completed terrain:") && s.contains("chunks/s")), "display labels the measured throughput scope");
        require(lines.stream().anyMatch(s -> s.contains("Promotions:") && s.contains("not production rate")), "display does not conflate promotion with generation");
        require(lines.stream().anyMatch(s -> s.contains("NBT encoding") && s.contains("§6~25.0%")), "NBT share is colored and marked as an estimate");
        require(lines.stream().anyMatch(s -> s.contains("Material layers") && s.startsWith("§d")), "GPU material timing has a dedicated category");
        require(lines.stream().anyMatch(s -> s.contains("Aquifer fields") && s.startsWith("§d")), "GPU aquifer fields have a dedicated category");
        require(lines.stream().anyMatch(s -> s.contains("Aquifer fluids / barriers") && s.startsWith("§d")), "GPU aquifer classification has a dedicated category");
        require(lines.stream().anyMatch(s -> s.startsWith("§9Column cache")), "cache timing does not imply Java-only work");
        require(lines.stream().anyMatch(s -> s.contains("GPU device") && s.contains("overlaps")), "device timing overlap is explicit");
        require(lines.stream().anyMatch(s -> s.startsWith("§dOre planning (CPU + GPU)")), "mixed ore planner is labeled and colored as GPU work");
        require(lines.stream().anyMatch(s -> s.startsWith("§dDecoration planning (CPU + GPU)")), "mixed decoration planner is labeled and colored as GPU work");
        require(lines.stream().anyMatch(s -> s.startsWith("§dFeature counts")), "sparse feature device timing has its own colored row");
        require(lines.stream().anyMatch(s -> s.startsWith("§dProvider noise") && s.contains("2.5%")), "provider noise has its own colored averaged timing row");
        require(lines.stream().anyMatch(s -> s.startsWith("§dOre preparation / masks") && s.contains("5.0%")), "ore device timing uses its own color and averaged region denominator");
        require(lines.stream().anyMatch(s -> s.startsWith("§dLake candidates") && s.contains("1.0%")), "lake classification uses the region average");
        require(lines.stream().anyMatch(s -> s.startsWith("§dLake density probes") && s.contains("7.5%")), "lake probes have a separate colored percentage");
        require(lines.stream().anyMatch(s -> s.startsWith("§dGPU lighting / upload / readback") && s.contains("10.0%")), "lighting host share uses averaged region wall time");
        require(lines.stream().anyMatch(s -> s.startsWith("§dLighting: propagation") && s.contains("6.0%")), "lighting device propagation has a separate averaged share");
        require(lines.stream().anyMatch(s -> s.startsWith("§dLake level reduction") && s.contains("0.5%")), "lake reduction is not included in column time");
        for (String line : lines) {
            require(!line.contains(" | ") && !line.contains("Last region") && !line.contains("session worker"), "each stage has its own line without old counters");
            require(line.chars().filter(c -> c == '%').count() <= 1, "one percentage per display line");
            require(line.contains("§"), "every line is color coded");
        }
        var empty = TerrainDebugReport.lines(TerrainStatsPayload.INACTIVE);
        var specialized=TerrainDebugReport.lines(new TerrainStatsPayload(true,"Metal test","mca",window,new NativeGpuDiagnostics(2,123_000_000,43210,1500,900,12,7,3,1048576,2097152)));
        require(specialized.stream().anyMatch(s->s.contains("GPU programs:") && s.contains("specialized")),"F3 shows completed specialization");
        require(specialized.stream().anyMatch(s->s.contains("GPU compilation:") && s.contains("123.0 ms")),"shader warmup cost is separate from region timings");
        require(specialized.stream().anyMatch(s->s.contains("Horizontal cache fields:") && s.contains("7")),"F3 reports resident horizontal reuse");
        require(specialized.stream().anyMatch(s->s.contains("GPU profile readback total:") && s.contains("2.00 MiB")),"readback volume has units and scope");
        var mixed=TerrainDebugReport.lines(new TerrainStatsPayload(true,"Metal test","mca",window,new NativeGpuDiagnostics(4,123_000_000,43210,1500,900,12,7,3,1048576,2097152)));
        require(mixed.stream().anyMatch(s->s.contains("GPU programs:") && s.contains("mixed (terrain interpreter)")),"F3 reports mixed GPU stage selection");
        require(empty.stream().noneMatch(s -> s.contains("NaN") || s.contains("Infinity")), "empty window formats finite values");
        packetRoundTrip(window);
        completedThroughput();
        System.out.println("QA_EVT {\"event\":\"rolling_region_metrics_and_f3_format\",\"status\":\"pass\"}");
    }

    private static void completedThroughput() {
        var clock = new java.util.concurrent.atomic.AtomicLong(-9_000_000_000L);
        var metrics = new GenerationMetrics(clock::get);
        close(metrics.snapshot().completedChunksPerSecond(),0,"empty completion window is finite");
        metrics.begin(); metrics.begin();
        clock.addAndGet(1_000_000_000L);
        metrics.completedRegion(1024,1_000_000_000L);
        metrics.completedPreviewRegion(1024,1_000_000_000L,NativeTimings.EMPTY);
        var overlap = metrics.snapshot();
        close(overlap.chunksPerSecond(),2048,"public throughput includes overlapping completions");
        close(overlap.completedChunksPerSecond(),2048,"both completed regions count against one wall second");
        close(overlap.completionWindowSeconds(),1,"startup exposes actual observation duration");
        close(overlap.averageRegionMs(),1000,"overlap does not halve request latency");
        metrics.begin(); metrics.completedPromotion(1);
        metrics.previewCacheHit();
        metrics.begin(); metrics.completedRegion(0,1);
        metrics.begin(); metrics.failed();
        close(metrics.snapshot().completedChunksPerSecond(),2048,"promotion, hits, failures and existing slots do not count as generation");
        clock.addAndGet(1_000_000_000L);
        metrics.begin(); metrics.completedRegion(1,500_000_000L);
        close(metrics.snapshot().completedChunksPerSecond(),2049.0/2,"partial repairs count only their generated chunks");
        clock.addAndGet(4_000_000_000L);
        close(metrics.snapshot().completedChunksPerSecond(),2049.0/6,"completions older than five seconds remain in the ten-second window");
        clock.addAndGet(5_000_000_000L);
        close(metrics.snapshot().completedChunksPerSecond(),2049.0/10,"completion at exact window boundary remains included");
        close(metrics.snapshot().completionWindowSeconds(),10,"wall observation is capped at ten seconds");
        var fullWindow = metrics.snapshot();
        clock.incrementAndGet();
        close(metrics.snapshot().completedChunksPerSecond(),.1,"old completions expire by wall time");
        clock.addAndGet(1_000_000_000L);
        var idle = metrics.snapshot();
        close(idle.completedChunksPerSecond(),0,"idle production rate reaches zero");
        close(idle.chunksPerSecond(),0,"all throughput fields decay to zero when idle");
        close(idle.averageRegionMs(),2500.0/3,"idle retains the last twenty latency samples");
        require(idle.inFlight()==0 && idle.total()==2049 && idle.failures()==1,"completion accounting stays balanced");
        var lines=TerrainDebugReport.lines(new TerrainStatsPayload(true,"test","mca",overlap));
        require(lines.stream().anyMatch(s->s.contains("Completed terrain: §a2048.0 chunks/s") && s.contains("1.0s wall window")),"completion throughput has its own colored wall-time label");
        require(lines.stream().filter(s->s.contains("chunks/s")).count()==1,"only the wall-time throughput is displayed");
        require(lines.stream().noneMatch(s->s.contains("from region latency") || s.contains("Amortized chunk")),"latency-derived chunk figures are removed");
        require(TerrainDebugReport.lines(new TerrainStatsPayload(true,"test","mca",fullWindow)).stream()
                .anyMatch(s->s.contains("204.9 chunks/s") && s.contains("10.0s wall window")),"steady state shows the ten-second window");
        packetRoundTrip(overlap);
        packetRoundTrip(fullWindow);
        packetRoundTrip(idle);
        var chunk = new GenerationMetrics(clock::get);
        chunk.begin(); clock.addAndGet(1_000_000_000L); chunk.completed(5,3,2);
        close(chunk.snapshot().chunksPerSecond(),1,"existing chunk-mode window is preserved");
        require(TerrainDebugReport.lines(new TerrainStatsPayload(true,"test","chunk",chunk.snapshot())).stream()
                .noneMatch(s->s.contains("Completed terrain:")),"chunk mode does not duplicate its existing completion row");
    }

    private static void packetRoundTrip(GenerationMetrics.Snapshot snapshot) {
        var diagnostics = new NativeGpuDiagnostics(2,123_000_000,43210,1500,900,12,7,3,1048576,2097152);
        for (var candidate : java.util.List.of(TerrainStatsPayload.INACTIVE,
                new TerrainStatsPayload(true,"Metal","mca",snapshot,diagnostics))) {
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try {
                TerrainStatsPayload.CODEC.encode(buffer,candidate);
                var decoded = TerrainStatsPayload.CODEC.decode(buffer);
                require(decoded.mode().equals(candidate.mode()),"mode survives packet round trip");
                require(decoded.stats().regionSamples()==candidate.stats().regionSamples(),"rolling region count survives transport");
                close(decoded.stats().averageRegionMs(),candidate.stats().averageRegionMs(),"region latency survives transport");
                close(decoded.stats().completedChunksPerSecond(),candidate.stats().completedChunksPerSecond(),"aggregate completions survive transport");
                close(decoded.stats().completionWindowSeconds(),candidate.stats().completionWindowSeconds(),"wall window survives transport");
                require(decoded.diagnostics().equals(candidate.diagnostics()) && !buffer.isReadable(),"packet preserves GPU diagnostics and consumes schema");
                require(TerrainDebugReport.lines(decoded).equals(TerrainDebugReport.lines(candidate)),"client decodes the exact same timing display");
                require(TerrainDebugReport.lines(decoded).getFirst().equals("§b§lRetina§r"),"F3 uses the unified generator name");
            } finally { buffer.release(); }
        }
    }

    static NativeTimings stages(long nanos) {
        long[] stages = new long[STAGES];
        stages[NBT] = nanos / 4;
        stages[COMPRESS] = nanos / 10;
        stages[WAIT_COPY] = nanos / 5;
        stages[HEIGHT] = nanos / 10;
        stages[ORE_MASK] = nanos / 20;
        stages[PROVIDER_NOISE] = nanos / 40;
        stages[LAKE_CANDIDATES] = nanos / 100;
        stages[LAKE_DENSITY] = nanos * 3 / 40;
        stages[LAKE_REDUCE] = nanos / 200;
        stages[LIGHT_HOST] = nanos / 10;
        stages[LIGHT_SKY] = nanos / 100;
        stages[LIGHT_SPREAD] = nanos * 3 / 50;
        stages[LIGHT_PACK] = nanos / 200;
        return new NativeTimings(15, 1024, 256 * 1024, 1, stages);
    }
    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
