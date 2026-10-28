package art.arcane.retina.bukkit;

import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class BiomeTileCacheTest {
    private static final Logger LOG = Logger.getLogger(BiomeTileCacheTest.class.getName());
    private static final String DIMENSION = "minecraft:overworld";
    @TempDir Path output;

    private PersistentBiomeCache disk(Path path, byte definition, byte engine, String dimension, int capacity) {
        byte[] d = new byte[32], e = new byte[32]; d[0] = definition; e[0] = engine;
        return new PersistentBiomeCache(path, dimension, d, e, capacity, 16, LOG);
    }
    private static int expected(int x, int y, int z) { return Math.floorMod((x >> 2) + (y >> 2) * 3 + (z >> 2) * 7, 16); }
    private static int[] sample(int[] points) {
        int[] answers = new int[points.length / 3];
        for (int i = 0; i < answers.length; i++) answers[i] = expected(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]);
        return answers;
    }

    @Test void restartUsesExactSavedAnswersAcrossNegativeTilesAndQuartHeights() {
        Path path = output.resolve("biomes.dat"); var calls = new AtomicInteger();
        int[] points = {-33, 0, -33, -32, 0, -32, -1, 0, -1, 0, 0, 0, 31, 3, 31, 32, 4, 32, -7, -1, 9};
        try (var cache = new BiomeTileCache(1, 2, disk(path, (byte) 0, (byte) 0, DIMENSION, 16), p -> {
            calls.incrementAndGet(); return sample(p);
        })) {
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < points.length; i += 3)
                assertEquals(expected(points[i], points[i + 1], points[i + 2]), cache.biome(points[i], points[i + 1], points[i + 2]));
            assertEquals(5, calls.get(), "Hot-cache evictions must reuse the bounded backing cache");
            cache.save();
            try (var saved = disk(path, (byte) 0, (byte) 0, DIMENSION, 16)) {
                assertEquals(5, saved.statistics().restored(), "World-save checkpoint must be readable before close");
            }
        }
        try (var cache = new BiomeTileCache(1, 2, disk(path, (byte) 0, (byte) 0, DIMENSION, 16), p -> { throw new AssertionError("Native query on restart"); })) {
            assertEquals(5, cache.diskStatistics().restored());
            for (int i = 0; i < points.length; i += 3)
                assertEquals(expected(points[i], points[i + 1], points[i + 2]), cache.biome(points[i], points[i + 1], points[i + 2]));
        }
    }

    @Test void changedDefinitionEngineOrDimensionCannotReuseSavedAnswers() throws Exception {
        Path path = output.resolve("biomes.dat");
        try (var cache = new BiomeTileCache(1, 1, disk(path, (byte) 0, (byte) 0, DIMENSION, 2), BiomeTileCacheTest::sample)) { cache.biome(0, 0, 0); }
        byte[] original = Files.readAllBytes(path);
        for (int changed = 0; changed < 3; changed++) {
            Files.write(path, original); var calls = new AtomicInteger();
            var backing = disk(path, (byte) (changed == 0 ? 1 : 0), (byte) (changed == 1 ? 1 : 0), changed == 2 ? "minecraft:the_end" : DIMENSION, 2);
            try (var cache = new BiomeTileCache(1, 1, backing, p -> { calls.incrementAndGet(); return sample(p); })) {
                assertEquals(0, cache.diskStatistics().restored()); cache.biome(0, 0, 0); assertEquals(1, calls.get());
            }
        }
    }

    @Test void corruptionNeverPublishesPartiallyReadTilesOrBlocksGeneration() throws Exception {
        Path path = output.resolve("biomes.dat");
        try (var cache = new BiomeTileCache(1, 1, disk(path, (byte) 0, (byte) 0, DIMENSION, 2), BiomeTileCacheTest::sample)) { cache.biome(0, 0, 0); }
        byte[] valid = Files.readAllBytes(path), crc = valid.clone(); crc[crc.length - 8] ^= 1;
        for (byte[] invalid : new byte[][]{Arrays.copyOf(valid, valid.length - 4), crc, payload(Integer.MAX_VALUE, false, false),
                payload(1, true, false), payload(2, false, false), payload(1, false, true)}) {
            Files.write(path, invalid); var calls = new AtomicInteger();
            try (var cache = new BiomeTileCache(1, 1, disk(path, (byte) 0, (byte) 0, DIMENSION, 2), p -> { calls.incrementAndGet(); return sample(p); })) {
                assertEquals(0, cache.diskStatistics().restored()); assertEquals(0, cache.diskStatistics().entries());
                assertEquals(expected(0, 0, 0), cache.biome(0, 0, 0)); assertEquals(1, calls.get());
            }
        }
    }

    private static byte[] payload(int count, boolean badId, boolean trailing) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x52424331); out.write(new byte[64]); out.writeUTF(DIMENSION); out.writeInt(count);
            // Duplicate coordinates when count=2; no allocation from an untrusted count.
            for (int i = 0; i < Math.min(2, count); i++) {
                out.writeInt(0); out.writeInt(0); out.writeInt(0);
                for (int j = 0; j < 64; j++) out.writeShort(badId ? 65535 : 0);
            }
            if (trailing) out.writeByte(1);
        }
        return bytes.toByteArray();
    }

    @Test void persistenceIsBoundedOptionalAndNeverCachesFailedQueries() {
        Path path = output.resolve("biomes.dat"); var calls = new AtomicInteger();
        try (var cache = new BiomeTileCache(1, 1, disk(path, (byte) 0, (byte) 0, DIMENSION, 2), p -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("actual native failure"); return sample(p);
        })) {
            assertThrows(IllegalStateException.class, () -> cache.biome(0, 0, 0)); assertEquals(0, cache.diskStatistics().entries());
            cache.biome(0, 0, 0); cache.biome(32, 0, 0); cache.biome(64, 0, 0); assertEquals(2, cache.diskStatistics().entries());
        }
        try (var cache = new BiomeTileCache(1, 1, disk(path, (byte) 0, (byte) 0, DIMENSION, 2), p -> { calls.incrementAndGet(); return sample(p); })) {
            assertEquals(2, cache.diskStatistics().restored()); cache.biome(64, 0, 0); assertEquals(4, calls.get());
            cache.biome(0, 0, 0); assertEquals(5, calls.get());
        }
        Path disabled = output.resolve("disabled.dat");
        try (var cache = new BiomeTileCache(1, 1, disk(disabled, (byte) 0, (byte) 0, DIMENSION, 0), BiomeTileCacheTest::sample)) {
            cache.biome(0, 0, 0); assertEquals(0, cache.diskStatistics().entries());
        }
        assertFalse(Files.exists(disabled));
    }

    @Test void actualCacheReadAndWriteFailuresStillReturnNativeAnswers() throws Exception {
        Path parentFile = output.resolve("not-a-directory"); Files.writeString(parentFile, "occupied");
        try (var cache = new BiomeTileCache(1, 1, disk(parentFile.resolve("cache.dat"), (byte) 0, (byte) 0, DIMENSION, 2), BiomeTileCacheTest::sample)) {
            assertEquals(expected(-1, -1, -1), cache.biome(-1, -1, -1));
        }
        assertEquals("occupied", Files.readString(parentFile));
    }

    @Test void closedWorldReleasesBackingTilesAndRejectsStaleQueries() {
        var cache = new BiomeTileCache(1, 1, disk(output.resolve("biomes.dat"), (byte) 0, (byte) 0, DIMENSION, 2), BiomeTileCacheTest::sample);
        cache.biome(0, 0, 0); cache.close();
        assertEquals(0, cache.diskStatistics().entries());
        assertThrows(IllegalStateException.class, () -> cache.biome(0, 0, 0));
        cache.close();
        try (var saved = disk(output.resolve("biomes.dat"), (byte) 0, (byte) 0, DIMENSION, 2)) { assertEquals(1, saved.statistics().restored()); }
    }

    @Test void failedCheckpointRemainsDirtyAndCanBeRetried() throws Exception {
        Path parent = output.resolve("retry"); Files.writeString(parent, "occupied");
        Path path = parent.resolve("biomes.dat");
        var tile = new BiomeTileCache.Tile(-1, 2, 3); int[] values = new int[64]; Arrays.fill(values, 7);
        try (var cache = disk(path, (byte) 0, (byte) 0, DIMENSION, 2)) {
            cache.put(tile, values); cache.save();
            Files.delete(parent); Files.createDirectory(parent);
            cache.save();
            try (var reopened = disk(path, (byte) 0, (byte) 0, DIMENSION, 2)) {
                assertArrayEquals(values, reopened.get(tile));
            }
        }
    }

    @Test void asynchronousUpdatesAndConcurrentClosePreserveLatestRevision() throws Exception {
        Path path = output.resolve("concurrent.dat");
        var cache = disk(path, (byte) 0, (byte) 0, DIMENSION, 16);
        var tile = new BiomeTileCache.Tile(0, 0, 0);
        for (int i = 0; i < 2000; i++) {
            int[] values = new int[64]; Arrays.fill(values, i % 16);
            cache.put(tile, values); cache.requestSave();
            assertArrayEquals(values, cache.get(tile));
        }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(cache::close); var second = executor.submit(cache::close);
            first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThrows(IllegalStateException.class, () -> cache.put(tile, new int[64]));
        assertEquals(0, cache.statistics().entries());
        try (var reopened = disk(path, (byte) 0, (byte) 0, DIMENSION, 16)) {
            int[] expected = new int[64]; Arrays.fill(expected, 1999 % 16);
            assertArrayEquals(expected, reopened.get(tile));
        }
    }

    @Test @EnabledIfSystemProperty(named = "retina.native.path", matches = ".+")
    void cachedBiomesMatchIndependentNativeGridsAndReopenWithoutQueries() throws Exception {
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(System.getProperty("retina.native.path")))), NativeLibrary.imageDigest());
        try (var world = NativeCatalog.create(Path.of(System.getProperty("retina.test.catalog")), "bukkit-api", Long.MIN_VALUE + 41, output)) {
            for (String dimension : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
                world.bindDimension(dimension, 2);
                int[] points = {-65, 0, -65, -64, 0, -64, -1, 0, -1, 0, 0, 0, 63, 0, 63, 64, 0, 64,
                        -63, 3, -63, -63, 4, -63, -63, 7, -63, 3, 32, -1};
                int[] expected = new int[points.length / 3];
                try (var cache = new BiomeTileCache(2, 4, output, dimension, 16, 65536, LOG, p -> world.biomePoints(dimension, p))) {
                    for (int i = 0; i < expected.length; i++) {
                        int x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
                        int actual = cache.biome(x, y, z);
                        var request = world.request(dimension, x >> 4, z >> 4);
                        short[] independent = world.biomes(dimension, x >> 4, z >> 4);
                        int index = (Math.floorDiv(y - request.minY(), 4) * 4 + ((z & 15) >> 2)) * 4 + ((x & 15) >> 2);
                        expected[i] = Short.toUnsignedInt(independent[index]);
                        assertEquals(expected[i], actual, dimension + " point " + i);
                    }
                }
                try (var cache = new BiomeTileCache(2, 4, output, dimension, 16, 65536, LOG, p -> { throw new AssertionError("Native query after reopen"); })) {
                    for (int i = 0; i < expected.length; i++) assertEquals(expected[i], cache.biome(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]));
                }
            }
        }
    }
}
