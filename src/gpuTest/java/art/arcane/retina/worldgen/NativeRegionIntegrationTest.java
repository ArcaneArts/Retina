package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.Bootstrap;
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

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/** Read Rust's entire MCA using Minecraft's actual region, NBT, palette and bit-storage decoders. */
public final class NativeRegionIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var nativeTerrain = NativeTerrain.instance();
        var directory = Files.createTempDirectory("retina-mca-test-");
        var path = directory.resolve("r.-1.0.mca");
        var request = request(-1, 0);
        int version = SharedConstants.getCurrentVersion().dataVersion().version();
        var info = new RegionStorageInfo("retina-test", Level.OVERWORLD, "chunk");
        Codec<PalettedContainer<BlockState>> codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        try {
            Files.createFile(path); // Vanilla and DH can open the MCA before the first chunk write.
            long started = System.nanoTime();
            var report = nativeTerrain.generateRegion(request, path, version, "minecraft:plains");
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            require(report.generated() == 1024 && report.preserved() == 0, "all region slots generated");
            try (var storage = new RegionFileStorage(info, directory, false)) {
                for (int z = 0; z < 32; z++) {
                    for (int x = -32; x < 0; x++) {
                        var tag = storage.read(new ChunkPos(x, z));
                        require(tag != null, "Minecraft reads every MCA slot");
                        require(tag.getIntOr("DataVersion", 0) == version, "current data version");
                        require(tag.getIntOr("xPos", 0) == x && tag.getIntOr("zPos", -1) == z, "negative region coordinates");
                        require(tag.getStringOr("Status", "").equals("minecraft:features"), "lighting remains for Minecraft");
                        var heights = nativeTerrain.sampleHeights(request(x, z));
                        var maps = tag.getCompoundOrEmpty("Heightmaps");
                        for (String name : new String[]{"WORLD_SURFACE", "OCEAN_FLOOR", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES"}) {
                            var packed = new SimpleBitStorage(9, 256, maps.getLongArray(name).orElseThrow());
                            for (int column = 0; column < 256; column++) {
                                require(packed.get(column) - 64 == heights[column], "Minecraft heightmap agrees with GPU");
                            }
                        }
                        var sections = tag.getListOrEmpty("sections");
                        require(sections.size() == 24, "all sections encoded");
                        for (int index = 0; index < sections.size(); index++) {
                            var section = sections.getCompound(index).orElseThrow();
                            int sectionY = section.getByteOr("Y", (byte) 0) * 16;
                            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                            require(section.getCompoundOrEmpty("biomes").getListOrEmpty("palette").getString(0).orElseThrow().equals("minecraft:plains"), "biome palette");
                            for (int y = 0; y < 16; y++) {
                                for (int localZ = 0; localZ < 16; localZ++) {
                                    for (int localX = 0; localX < 16; localX++) {
                                        var expected = sectionY + y < heights[localZ * 16 + localX] ? Blocks.STONE : Blocks.AIR;
                                        require(blocks.get(localX, y, localZ).is(expected), "Minecraft palette matches GPU stone/air data");
                                    }
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf(java.util.Locale.ROOT,
                    "QA_EVT {\"event\":\"minecraft_mca_decode\",\"status\":\"pass\",\"context\":{\"chunks\":1024,\"generation_ms\":%.3f,\"amortized_ms_per_chunk\":%.5f,\"bytes\":%d}}%n",
                    ms, ms / 1024, report.bytes());

            var stages=nativeTerrain.timings(0);
            require(stages.chunks()==1024, "MCA worker chunks counted once");
            for(int stage:new int[]{NativeTimings.ASSEMBLY,NativeTimings.NBT,NativeTimings.COMPRESS,NativeTimings.IO})
                require(stages.nanos(stage)>0, "MCA stage has real measurements: "+stage);
            System.out.println("QA_EVT {\"event\":\"mca_stage_timings\",\"status\":\"pass\",\"context\":{\"worker_ms_per_chunk\":"+stages.workerNanos()/1024e6+",\"nbt_ms_per_chunk\":"+stages.chunkMs(NativeTimings.NBT)+",\"compression_ms_per_chunk\":"+stages.chunkMs(NativeTimings.COMPRESS)+"}}");
            byte[] complete = Files.readAllBytes(path);
            var repeat = nativeTerrain.generateRegion(request, path, version, "minecraft:plains");
            require(repeat.generated() == 0 && repeat.preserved() == 1024, "complete region does not regenerate");
            require(Arrays.equals(complete, Files.readAllBytes(path)), "complete region stays byte-for-byte unchanged");

            require(nativeTerrain.timings(0).chunks()==1024, "existing region does not inflate timing chunk counts");
            var editedPosition = new ChunkPos(-32, 0);
            try (var storage = new RegionFileStorage(info, directory, false)) {
                var edited = storage.read(editedPosition);
                edited.putString("retina_test_marker", "player edit");
                var section = edited.getListOrEmpty("sections").getCompound(0).orElseThrow();
                section.getCompoundOrEmpty("block_states").getListOrEmpty("palette")
                        .set(0, StringTag.valueOf("minecraft:diamond_block"));
                storage.write(editedPosition, edited);
                storage.write(new ChunkPos(-1, 31), null); // Actual Minecraft deletion leaves one absent slot.
            }
            var repair = nativeTerrain.generateRegion(request, path, version, "minecraft:plains");
            require(repair.generated() == 1 && repair.preserved() == 1023, "fill only the missing slot");
            try (var storage = new RegionFileStorage(info, directory, false)) {
                var edited = storage.read(editedPosition);
                require(edited.getStringOr("retina_test_marker", "").equals("player edit"), "custom data preserved");
                var section = edited.getListOrEmpty("sections").getCompound(0).orElseThrow();
                var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                require(blocks.get(0, 0, 0).is(Blocks.DIAMOND_BLOCK), "player block edits survive region generation");
                require(storage.read(new ChunkPos(-1, 31)) != null, "missing slot is readable again");
            }
            require(nativeTerrain.timings(0).chunks()==1025, "single missing slot counts as one chunk");
            System.out.println("QA_EVT {\"event\":\"mca_existing_edits_and_missing_slots\",\"status\":\"pass\"}");

            var corrupt = directory.resolve("corrupt.mca");
            Files.write(corrupt, new byte[]{1, 2, 3});
            try {
                nativeTerrain.generateRegion(request, corrupt, version, "minecraft:plains");
                throw new AssertionError("truncated MCA must report an error");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("truncated MCA header"), "actual MCA error crosses ABI");
                require(Arrays.equals(Files.readAllBytes(corrupt), new byte[]{1, 2, 3}), "corrupt file not overwritten");
            }
            System.out.println("QA_EVT {\"event\":\"mca_error_preserves_save\",\"status\":\"pass\"}");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static TerrainRequest request(int x, int z) {
        return new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, 0.008F);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
