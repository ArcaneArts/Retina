package art.arcane.retina.worldgen;

import art.arcane.retina.debug.TerrainDebugReport;
import static art.arcane.retina.worldgen.NativeTimings.*;

/** Deterministic rolling-window and display checks, invoked by gpuTest. */
final class GenerationMetricsTest {
    static void run() {
        var metrics = new GenerationMetrics();
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
        close(window.msPerChunk(), 16.5 / 1024, "amortized time uses the region window");
        close(window.chunksPerSecond(), 1024 * 1000 / 16.5, "rate uses the same region average");
        close(window.regionStagePercent(NBT), 25, "worker share uses average region time");
        close(window.columnCacheMs(), .85, "cache writes use the same rolling window");
        close(window.columnCachePercent(), 100*.85/16.5, "Java cache shares region latency");
        metrics.begin(); metrics.completedPromotion(1);
        metrics.begin(); metrics.completedRegion(0, 999_000_000, NativeTimings.EMPTY);
        var unchanged = metrics.snapshot();
        close(unchanged.averageRegionMs(), 16.5, "promotion and existing saves do not skew generation speed");
        require(unchanged.regionSamples() == 20 && unchanged.inFlight() == 0 && unchanged.total() == 26 * 1024, "cached terrain is not counted twice");
        var partial = new GenerationMetrics();
        partial.begin(); partial.completedRegion(1024, 1_000_000);
        partial.begin(); partial.completedRegion(1, 3_000_000);
        close(partial.snapshot().averageRegionMs(), 2, "partial regions are valid latency samples");
        close(partial.snapshot().chunksPerSecond(), 1025 / .004, "partial regions use actual chunk counts");
        close(partial.snapshot().msPerChunk(), 4.0 / 1025, "weighted partial-region amortization");

        var lines = TerrainDebugReport.lines(new TerrainStatsPayload(true, "Metal test", "mca", window));
        require(lines.stream().anyMatch(s -> s.contains("Average region:") && s.contains("16.50 ms") && s.contains("20")), "display reports rolling latency and sample count");
        require(lines.stream().anyMatch(s -> s.contains("NBT encoding") && s.contains("§6~25.0%")), "NBT share is colored and marked as an estimate");
        require(lines.stream().anyMatch(s -> s.contains("GPU device") && s.contains("overlaps")), "device timing overlap is explicit");
        require(lines.stream().anyMatch(s -> s.startsWith("§dOre planning (CPU + GPU)")), "mixed ore planner is labeled and colored as GPU work");
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
        require(empty.stream().noneMatch(s -> s.contains("NaN") || s.contains("Infinity")), "empty window formats finite values");
        System.out.println("QA_EVT {\"event\":\"rolling_region_metrics_and_f3_format\",\"status\":\"pass\"}");
    }

    static NativeTimings stages(long nanos) {
        long[] stages = new long[STAGES];
        stages[NBT] = nanos / 4;
        stages[COMPRESS] = nanos / 10;
        stages[WAIT_COPY] = nanos / 5;
        stages[HEIGHT] = nanos / 10;
        return new NativeTimings(1, 1024, 256 * 1024, 1, stages);
    }
    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " != " + expected);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
