package art.arcane.retina.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/** Standalone integration runner invoked by ./gradlew gpuTest; requires a compute device. */
public final class NativeTerrainIntegrationTest {
    public static void main(String[] args) throws Exception {
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
                    require(data.blocks().byteSize() == 98_304, "chunk buffer size");
                    require(Arrays.equals(data.heights(), nativeTerrain.sampleHeights(request)), "GPU query agrees with chunk generation");
                    for (int column = 0; column < 256; column++) {
                        int firstAir = data.heights()[column];
                        for (int layer = 0; layer < 384; layer++) {
                            byte expected = (byte) (layer - 64 < firstAir ? 1 : 0);
                            require(data.blocks().get(JAVA_BYTE, layer * 256L + column) == expected, "stone column matches GPU height");
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

        try {
            nativeTerrain.sampleHeights(new TerrainRequest(0, 0, 0, -64, 384, 64, 48, 0));
            throw new AssertionError("invalid native request must report its actual error");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("invalid simplex settings"), "Rust error crosses ABI");
        }
        System.out.println("QA_EVT {\"event\":\"native_error_propagation\",\"status\":\"pass\"}");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
