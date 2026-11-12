import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeLibrary;
import art.arcane.retina.core.NativeWorldDefinition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Run against both Java providers with the same native image and catalog. */
public final class DefinitionTransportSmoke {
    private static final long SEED = -0x1234_5678_9abc_defL;
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";
    private static final List<String> DIMENSIONS = List.of(OVERWORLD, NETHER, END);

    public static void main(String[] args) throws Exception {
        Path catalog = Path.of(args[0]);
        boolean metal = "metal".equals(System.getProperty("retina.gpuBackend"));
        NativeLibrary.allowGpuBackends(false, metal, false);
        String prefix = args.length == 2 && args[1].equals("--ascii-path")
                ? "retina-transport-" : "retina-transport-雪-🌍-";
        Path root = Files.createTempDirectory(prefix);
        try {
            verify(catalog, root, metal);
        } finally {
            List<Path> paths;
            try (var files = Files.walk(root)) {
                paths = files.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : paths) Files.delete(path);
        }
    }

    private static void verify(Path catalog, Path root, boolean metal) throws Exception {
        Path definition = root.resolve("retina.dat");
        require(NativeWorldDefinition.tryOpen(definition) == null, "missing definition");
        Path bad = root.resolve("invalid.dat");
        Files.writeString(bad, "invalid gzip");
        fail(() -> NativeWorldDefinition.open(bad), "invalid.dat");
        fail(() -> NativeLibrary.allowGpuBackends(true, metal, false), "already configured");
        receipt("library", NativeLibrary.imageDigest());

        int[] points = new int[1024 * 3];
        for (int i = 0; i < 1024; i++) {
            points[3 * i] = (i % 32 - 16) * 4;
            points[3 * i + 1] = 64;
            points[3 * i + 2] = (i / 32 - 16) * 4;
        }
        List<String> before;
        String saved;
        int[] expected;
        try (var world = NativeCatalog.create(catalog, "normal-no-structures", SEED, root)) {
            saved = digest(Files.readAllBytes(definition));
            System.out.println("RECEIPT definition " + saved);
            receipt("metadata", world.metadata());
            receipt("profile", world.diagnosticProfile(END));
            fail(() -> world.bindDimension("retina:missing", 2), "retina:missing");
            world.bindDimension(OVERWORLD, 2, NativeWorldDefinition.ProgramExecution.AUTO);
            world.bindDimension(NETHER, 2);
            require(world.request(OVERWORLD, -2, 1).seed() == SEED, "request seed");
            require(world.request(OVERWORLD, -2, 1).chunkX() == -2, "negative chunk coordinate");
            expected = world.biomePoints(END, points);
            require(expected.length == 1024, "point batch length");
            receipt("points", Arrays.toString(expected));
            before = readChunks(world);
            for (String chunk : before) System.out.println("RECEIPT " + chunk);

            Path region = root.resolve("r.-1.0.mca");
            var generated = world.generateRegion(END, -1, 0, region);
            require(generated.generated() == 1024 && generated.preserved() == 0, "generated region");
            byte[] first = Files.readAllBytes(region);
            var preserved = world.generateRegion(END, -1, 0, region);
            require(preserved.generated() == 0 && preserved.preserved() == 1024, "preserved region");
            require(Arrays.equals(first, Files.readAllBytes(region)), "existing region bytes");
            // Save timestamps are allowed to differ between independent runs.
            Arrays.fill(first, 4096, 8192, (byte) 0);
            receipt("region", first);
            benchmark(world, points, expected);
        }

        var reopened = NativeWorldDefinition.open(definition);
        try (reopened) {
            reopened.bindDimension(END, 2);
            require(Arrays.equals(expected, reopened.biomePoints(END, points)), "reopened point batch");
            require(before.equals(readChunks(reopened)), "reopened chunks and biome palettes");
        }
        reopened.close();
        fail(() -> reopened.chunk(END, 0, 0), "closed");
        require(saved.equals(digest(Files.readAllBytes(definition))), "saved definition unchanged");
        System.out.println("TRANSPORT_SMOKE_PASS java=" + Runtime.version().feature());
    }

    private static List<String> readChunks(NativeWorldDefinition world) throws Exception {
        var executor = Executors.newFixedThreadPool(DIMENSIONS.size());
        try {
            var jobs = new ArrayList<Callable<String>>();
            for (String dimension : DIMENSIONS) {
                jobs.add(() -> dimension + " chunk=" + digest(world.chunk(dimension, -1, 0))
                        + " biomes=" + digest(Arrays.toString(world.biomes(dimension, -1, 0)).getBytes(StandardCharsets.UTF_8)));
            }
            var futures = executor.invokeAll(jobs);
            var output = new ArrayList<String>();
            for (var result : futures) output.add(result.get());
            return output;
        } finally {
            executor.shutdown();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "chunk queries drained");
        }
    }

    private static void benchmark(NativeWorldDefinition world, int[] points, int[] expected) {
        // Warm native query caches and let the JVM compile the transport call site.
        for (int i = 0; i < 160; i++) require(Arrays.equals(expected, world.biomePoints(END, points)), "warm point batch");
        long[] samples = new long[7];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            for (int batch = 0; batch < 64; batch++) {
                require(Arrays.equals(expected, world.biomePoints(END, points)), "timed point batch");
            }
            samples[i] = (System.nanoTime() - start) / 64;
        }
        Arrays.sort(samples);
        System.out.println("TIMING point_batch_1024 median_ns=" + samples[samples.length / 2]
                + " samples_ns=" + Arrays.toString(samples));
    }

    private static void fail(Runnable call, String message) {
        try {
            call.run();
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains(message), "exception content: " + expected);
            return;
        }
        throw new AssertionError("Expected an error containing " + message);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void receipt(String name, String value) throws Exception {
        receipt(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void receipt(String name, byte[] value) throws Exception {
        // The imageDigest accessor already supplies a SHA-256.
        String hash = "library".equals(name) ? HexFormat.of().formatHex(value) : digest(value);
        System.out.println("RECEIPT " + name + " " + hash);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
