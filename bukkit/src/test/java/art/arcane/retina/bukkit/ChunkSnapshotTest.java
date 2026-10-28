package art.arcane.retina.bukkit;

import art.arcane.retina.core.NativeCatalog;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSnapshotTest {
    @TempDir Path output;

    @Test void proceduralStringPaletteInstallsUniformNegativeHeightSection() throws Exception {
        var root = net.kyori.adventure.nbt.TagStringIO.tagStringIO().asCompound("""
                {sections:[{Y:-4b,block_states:{palette:["minecraft:air"]},biomes:{palette:["minecraft:forest"]}}]}
                """);
        var bytes = new java.io.ByteArrayOutputStream();
        net.kyori.adventure.nbt.BinaryTagIO.writer().write(root, bytes, net.kyori.adventure.nbt.BinaryTagIO.Compression.NONE);
        var calls = new java.util.ArrayList<String>();
        var target = (org.bukkit.generator.ChunkGenerator.ChunkData) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.generator.ChunkGenerator.ChunkData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    case "setRegion" -> { assertEquals(-64, args[1]); assertEquals(-48, args[4]); calls.add("region"); yield null; }
                    default -> throw new AssertionError(method);
                });
        var chunk = new ChunkSnapshot(bytes.toByteArray(), -64, 384);
        chunk.install(target, state -> { assertEquals("minecraft:air", state); return null; });
        assertEquals(java.util.List.of("region"), calls);
        assertEquals("minecraft:forest", chunk.biome(-16, -64, -16));
    }

    @Test void heightmapsPreservePaddedWordsAndNegativeStorageMinimum() {
        int minimum = -64, height = 384, bits = 9, perWord = 64 / bits;
        long[] words = new long[(256 + perWord - 1) / perWord];
        for (int i = 0; i < 256; i++)
            words[i / perWord] |= (long) ((i * 37) % (height + 1)) << ((i % perWord) * bits);
        var maps = net.kyori.adventure.nbt.CompoundBinaryTag.builder();
        for (var type : org.bukkit.HeightMap.values()) maps.putLongArray(type.name(), words);
        var root = net.kyori.adventure.nbt.CompoundBinaryTag.builder().put("Heightmaps", maps.build()).build();
        var snapshot = new ChunkSnapshot(root, minimum, height);
        for (var type : org.bukkit.HeightMap.values()) {
            for (int i = 0; i < 256; i++)
                assertEquals(minimum + (i * 37) % (height + 1),
                        snapshot.height(type, (i & 15) - 16, (i >>> 4) + 16));
        }
        assertSame(root, snapshot.root); // Metadata remains available without reconstruction.
        var absent = new ChunkSnapshot(net.kyori.adventure.nbt.CompoundBinaryTag.empty(), minimum, height);
        assertThrows(IllegalStateException.class,
                () -> absent.height(org.bukkit.HeightMap.WORLD_SURFACE, 0, 0));
    }

    @Test @EnabledIfSystemProperty(named = "retina.native.path", matches = ".+")
    void apiCatalogExecutesWithoutUnavailableTickOrStructureMetadata() {
        var delivery = new MetadataDelivery(java.util.logging.Logger.getAnonymousLogger(), false);
        for (long seed : new long[]{0, Long.MIN_VALUE + 41, Long.MAX_VALUE - 37}) {
            try (var world = NativeCatalog.create(Path.of(System.getProperty("retina.test.catalog")),
                    "bukkit-api", seed, output.resolve(Long.toString(seed)))) {
                var metadata = JsonParser.parseString(world.metadata()).getAsJsonObject();
                assertFalse(metadata.get("generate_structures").getAsBoolean());
                assertEquals(seed, metadata.get("seed").getAsLong());
                for (String key : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
                    world.bindDimension(key, 2);
                    for (int[] at : new int[][]{{0, 0}, {-1, -1}, {12, -13}}) {
                        var request = world.request(key, at[0], at[1]);
                        var chunk = new ChunkSnapshot(world.chunk(key, at[0], at[1]), request.minY(), request.height());
                        assertDoesNotThrow(() -> delivery.validate(chunk.root), key + " " + seed);
                    }
                }
            }
        }
    }

    @Test @EnabledIfSystemProperty(named = "retina.native.path", matches = ".+")
    void decodedBiomesMatchIndependentNativeGridForAllQuartCells() {
        try (var world = NativeCatalog.create(Path.of(System.getProperty("retina.test.catalog")),
                "normal-no-structures", Long.MIN_VALUE + 41, output)) {
            var metadata = JsonParser.parseString(world.metadata()).getAsJsonObject();
            var shared = metadata.getAsJsonObject("shared_types").getAsJsonArray("biomes");
            for (String key : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
                world.bindDimension(key, 2);
                var dimension = metadata.getAsJsonObject("dimensions").getAsJsonObject(key);
                var palette = dimension.getAsJsonArray("biome_palette");
                for (int[] at : new int[][]{{0, 0}, {-1, -1}, {32, -33}}) {
                    var request = world.request(key, at[0], at[1]);
                    var chunk = new ChunkSnapshot(world.chunk(key, at[0], at[1]), request.minY(), request.height());
                    short[] grid = world.biomes(key, at[0], at[1]);
                    int[] points = new int[32 * 3];
                    int[] expectedPoints = new int[32];
                    for (int i = 0; i < 32; i++) {
                        int index = (i * 97) % grid.length;
                        points[i * 3] = at[0] * 16 + (index % 4) * 4 + 3;
                        points[i * 3 + 1] = request.minY() + (index / 16) * 4 + 2;
                        points[i * 3 + 2] = at[1] * 16 + ((index / 4) % 4) * 4 + 1;
                        expectedPoints[i] = Short.toUnsignedInt(grid[index]);
                    }
                    assertArrayEquals(expectedPoints, world.biomePoints(key, points), key + " sparse/full biome parity");
                    for (int y = 0; y < request.height() / 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                        int index = (y * 4 + z) * 4 + x;
                        String expected = shared.get(palette.get(Short.toUnsignedInt(grid[index])).getAsInt()).getAsString();
                        assertEquals(expected, chunk.biome(x * 4, request.minY() + y * 4, z * 4), key + " quart " + index);
                    }
                }
            }
        }
    }
}
