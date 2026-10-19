package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Persist a complete negative-coordinate Nether region and decode it with Minecraft. */
final class NetherRegionTest {
    static void check(TerrainProfileData data, BiomeTerrainProfile profile) throws Exception {
        var terrain = NativeTerrain.instance();
        var materials = data.materials();
        Files.createDirectories(Path.of("build/nether"));
        var directory = Files.createTempDirectory(Path.of("build/nether"), "region-");
        var path = directory.resolve("r.-1.0.mca");
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        long start = System.nanoTime();
        var report = terrain.generateRegion(NetherTerrainTest.request(profile, -1, 0, 0, 256), path, version, "minecraft:nether_wastes");
        double ms = (System.nanoTime() - start) / 1e6;
        require(report.generated() == 1024 && report.preserved() == 0, "fresh complete Nether region");
        var codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        var sourceBiomes = data.biomes().stream().map(b -> b.unwrapKey().orElseThrow().identifier().toString()).collect(Collectors.toSet());
        var probes = Set.of(new ChunkPos(-32, 0), new ChunkPos(-1, 31), new ChunkPos(-1, 0), new ChunkPos(-32, 31), new ChunkPos(-15, 16));
        long compared = 0;
        try (var storage = new RegionFileStorage(new RegionStorageInfo("nether-terrain-test", Level.NETHER, "chunk"), directory, false)) {
            for (int z = 0; z < 32; z++) for (int x = -32; x < 0; x++) {
                var position = new ChunkPos(x, z);
                var tag = storage.read(position);
                require(tag != null, "all MCA slots readable");
                require(tag.getIntOr("DataVersion", -1) == version, "current data version");
                require(tag.getIntOr("xPos", 0) == x && tag.getIntOr("zPos", -1) == z, "saved chunk coordinates");
                for (String name : new String[]{"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"}) {
                    var bits = new SimpleBitStorage(9, 256, tag.getCompoundOrEmpty("Heightmaps").getLongArray(name).orElseThrow());
                    for (int c = 0; c < 256; c++) require(bits.get(c) == 128, "saved heightmap sees bedrock roof");
                }
                var sections = McaTestSections.terrain(tag, 0, 256);
                for (var section : tag.getListOrEmpty("sections")) {
                    var nbt = (net.minecraft.nbt.CompoundTag) section;
                    for (byte light : nbt.getByteArray("SkyLight").orElse(new byte[0])) require(light == 0, "Nether has no skylight");
                }
                for (var section : sections) {
                    var palette = section.getCompoundOrEmpty("biomes").getListOrEmpty("palette");
                    for (int i = 0; i < palette.size(); i++) require(sourceBiomes.contains(palette.getString(i).orElseThrow()), "saved source biome palette");
                    if (section.getByteOr("Y", (byte) -1) >= 8) {
                        var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                        for (int yy = 0; yy < 16; yy++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++)
                            require(blocks.get(xx, yy, zz).isAir(), "saved build space above roof remains air");
                    }
                }
                if (!probes.contains(position)) continue;
                var request = NetherTerrainTest.request(profile, x, z, 0, 256);
                try (var generated = terrain.generate(request)) {
                    short[] expected = generated.blocks().toArray(ValueLayout.JAVA_SHORT);
                    short[] expectedBiomes = terrain.sampleBiomes(request);
                    for (var section : sections) {
                        int sy = section.getByteOr("Y", (byte) -1);
                        var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                        for (int yy = 0; yy < 16; yy++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) {
                            var state = materials[Short.toUnsignedInt(expected[(sy * 16 + yy) * 256 + zz * 16 + xx])];
                            require(blocks.get(xx, yy, zz).equals(state), "region/chunk block parity " + position + " at " + xx + "," + (sy * 16 + yy) + "," + zz);
                            compared++;
                        }
                        var biomes = section.getCompoundOrEmpty("biomes");
                        var palette = biomes.getListOrEmpty("palette");
                        int bits = palette.size() <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
                        var packed = bits == 0 ? null : new SimpleBitStorage(bits, 64, biomes.getLongArray("data").orElseThrow());
                        for (int i = 0; i < 64; i++) {
                            String saved = palette.getString(packed == null ? 0 : packed.get(i)).orElseThrow();
                            String selected = data.biomes().get(Short.toUnsignedInt(expectedBiomes[sy * 64 + i])).unwrapKey().orElseThrow().identifier().toString();
                            require(saved.equals(selected), "region/chunk biome parity at " + position);
                        }
                    }
                }
            }
        }
        byte[] saved = Files.readAllBytes(path);
        var repeat = terrain.generateRegion(NetherTerrainTest.request(profile, -1, 0, 0, 256), path, version, "minecraft:nether_wastes");
        require(repeat.generated() == 0 && repeat.preserved() == 1024 && Arrays.equals(saved, Files.readAllBytes(path)), "repeated publication preserves complete region");
        var stats = new JsonObject(); stats.addProperty("chunks", 1024); stats.addProperty("compared_voxels", compared);
        stats.addProperty("generation_ms", ms); stats.addProperty("bytes", report.bytes()); stats.addProperty("region", path.toAbsolutePath().toString());
        Files.writeString(Path.of("build/nether/region-stats.json"), stats.toString());
        System.out.println("QA_EVT {\"event\":\"nether_saved_region\",\"status\":\"pass\",\"context\":" + stats + "}");
    }

    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
