package art.arcane.retina.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** Standalone integration runner invoked by ./gradlew gpuTest; requires a compute device. */
public final class NativeTerrainIntegrationTest {
    public static void main(String[] args) throws Exception {
        GenerationMetricsTest.run();
        var nativeTerrain = NativeTerrain.instance();
        System.out.println("QA_EVT {\"event\":\"java_native_loaded\",\"status\":\"pass\",\"details\":\"" + nativeTerrain.backend() + "\"}");
        long started = System.nanoTime();
        var jobs = new ArrayList<Callable<Double>>();
        for (int index = 0; index < 128; index++) {
            int x = index % 16 - 8;
            int z = index / 16 - 4;
            jobs.add(() -> {
                var request = new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, 0.008F);
                long begin = System.nanoTime();
                try (var data = nativeTerrain.generate(request)) {
                    double elapsed = (System.nanoTime() - begin) / 1_000_000.0;
                    require(data.blocks().byteSize() == 196_608, "chunk buffer size");
                    require(Arrays.equals(data.heights(), nativeTerrain.sampleHeights(request)), "GPU query agrees with chunk generation");
                    for (int column = 0; column < 256; column++) {
                        int firstAir = data.heights()[column];
                        for (int layer = 0; layer < 384; layer++) {
                            byte expected = (byte) (layer - 64 < firstAir ? 1 : 0);
                            require(data.blocks().get(JAVA_SHORT, (layer * 256L + column)*2) == expected, "stone column matches GPU height");
                        }
                    }
                    return elapsed;
                }
            });
        }
        double latency = 0;
        try (var workers = Executors.newFixedThreadPool(16)) {
            for (var result : workers.invokeAll(jobs)) latency += result.get();
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(java.util.Locale.ROOT,
                "QA_EVT {\"event\":\"java_parallel_bridge\",\"status\":\"pass\",\"context\":{\"chunks\":128,\"workers\":16,\"chunks_per_second\":%.2f,\"mean_native_request_ms\":%.3f}}%n",
                128 / seconds, latency / 128);

        var timings = nativeTerrain.timings(0);
        var transfers=nativeTerrain.gpuDiagnostics(0);
        require(transfers.readbackBytes()>=128L*256*12 && transfers.uploadBytes()>0,"actual GPU transfer counters cross C ABI");
        require(timings.chunks()==128, "parallel requests are counted exactly once");
        require(timings.gpuColumns()>=128*256 && timings.gpuJobs()>0, "GPU dispatches and processed columns are reported");
        require(timings.nanos(NativeTimings.ASSEMBLY)>0 && timings.nanos(NativeTimings.WAIT_COPY)>0, "worker and host timings cross C ABI");
        if(timings.gpuMeasured()) require(timings.nanos(NativeTimings.COLUMNS)>0, "GPU timestamps measure real shader execution");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var stats=new GenerationMetrics.Snapshot(3,4,5,6,7,8,9,10,11,11.5,12,13,14,timings,20,GenerationMetricsTest.stages(8_000_000),2.5);
            var diagnostics=new NativeGpuDiagnostics(2,123_000_000,43210,1500,900,12,7,3,1048576,2097152);
            var payload=new TerrainStatsPayload(true,nativeTerrain.backend(),"mca",stats,diagnostics);
            TerrainStatsPayload.CODEC.encode(buffer,payload);
            var decoded=TerrainStatsPayload.CODEC.decode(buffer);
            require(decoded.active() && decoded.mode().equals("mca") && decoded.stats().promotions()==14, "F3 payload preserves existing statistics");
            require(decoded.stats().stages().chunks()==timings.chunks() && Arrays.equals(decoded.stats().stages().nanos(),timings.nanos()), "F3 payload carries all native stage counters");
            require(decoded.stats().averageRegionMs()==11 && decoded.stats().p95RegionMs()==11.5 && decoded.stats().regionSamples()==20 && decoded.stats().columnCacheMs()==2.5, "F3 packet carries rolling region mean and p95");
            require(Arrays.equals(decoded.stats().regionStages().nanos(),stats.regionStages().nanos()), "F3 packet carries job-local wall shares");
            require(decoded.diagnostics().equals(diagnostics),"GPU compilation and transfer diagnostics survive packet roundtrip");
            require(!buffer.isReadable(), "timing packet consumes its complete bounded schema");
        } finally { buffer.release(); }
        System.out.println("QA_EVT {\"event\":\"native_timing_packet\",\"status\":\"pass\",\"context\":{\"chunks\":"+timings.chunks()+",\"gpu_jobs\":"+timings.gpuJobs()+",\"gpu_timestamps\":"+timings.gpuMeasured()+"}}");
        try {
            nativeTerrain.sampleHeights(new TerrainRequest(0, 0, 0, -64, 384, 64, 48, 0));
            throw new AssertionError("invalid native request must report its actual error");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("invalid simplex settings"), "Rust error crosses ABI");
        }
        require(nativeTerrain.timings(0).chunks()==128, "failed requests do not count as generated chunks");
        var nextWorld=new GenerationMetrics(); nextWorld.startNativeTimings(nativeTerrain.timings(0));
        nextWorld.nativeTimings(nativeTerrain.timings(0));
        require(nextWorld.snapshot().stages().chunks()==0 && nextWorld.snapshot().stages().workerNanos()==0, "new world baseline excludes reused native profile history");
        try(var data=nativeTerrain.generate(new TerrainRequest(123456789L,17,17,-64,384,64,48,.008F))) {
            require(data.heights().length==256,"new world request completes");
        }
        nextWorld.nativeTimings(nativeTerrain.timings(0));
        require(nextWorld.snapshot().stages().chunks()==1 && nextWorld.snapshot().stages().workerNanos()>0, "new session reports only new worker measurements");
        System.out.println("QA_EVT {\"event\":\"native_error_propagation\",\"status\":\"pass\"}");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
