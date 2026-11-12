package art.arcane.retina.minecraft1201;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Exercise the actual 1.20.1 reader and installation path on native Nether records. */
final class NetherFeatureVerification {
    private NetherFeatureVerification() { }

    static void verify(Path world, RegistryAccess registries) throws Exception {
        Path directory = world.resolve("DIM-1/region");
        var materials = new HashSet<String>();
        int checked = 0;
        int ticks = 0;
        for (var region : new ChunkPos[]{new ChunkPos(-1, 0), new ChunkPos(0, -1)}) {
            String name = "r." + region.x + "." + region.z + ".mca";
            try (var file = new RegionFile(directory.resolve(name), directory, true)) {
                for (int slot = 0; slot < 1024; slot++) {
                    var pos = new ChunkPos(region.x * 32 + (slot & 31), region.z * 32 + (slot >> 5));
                    CompoundTag data;
                    try (var input = file.getChunkDataInputStream(pos)) {
                        data = NbtIo.read(input);
                    }
                    for (var rawSection : data.getList("sections", Tag.TAG_COMPOUND)) {
                        var states = ((CompoundTag) rawSection).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
                        for (var state : states) materials.add(((CompoundTag) state).getString("Name"));
                    }
                    var fluids = data.getList("fluid_ticks", Tag.TAG_COMPOUND);
                    if (fluids.isEmpty()) continue;
                    verifyTicks(data, fluids, pos, registries);
                    checked++;
                    ticks += fluids.size();
                }
            }
        }
        require(checked > 0, "generated spring queues");
        require(materials.containsAll(Set.of("minecraft:glowstone", "minecraft:shroomlight")), "generated glowstone and fungi");
        System.out.println("QA_EVT {\"event\":\"nether_1201_feature_ticks\",\"status\":\"pass\",\"chunks\":"
                + checked + ",\"fluid_ticks\":" + ticks + "}");
    }

    private static void verifyTicks(CompoundTag data, ListTag fluids, ChunkPos pos, RegistryAccess registries) {
        var read = ProtoChunkTicks.load(fluids, id -> BuiltInRegistries.FLUID.getOptional(new ResourceLocation(id)), pos);
        require(read.count() == fluids.size(), "native spring positions and fluid names");
        var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 256),
                registries.registryOrThrow(Registries.BIOME), null);
        // The native corpus supplies actual fluid ticks. Exercise the other
        // two queues with a block tick and one packed position.
        var blocks = new ListTag();
        var blockTick = fluids.getCompound(0).copy();
        blockTick.putString("i", "minecraft:netherrack");
        blocks.add(blockTick);
        data.put("block_ticks", blocks);
        var offsets = new ListTag();
        var section = new ListTag();
        section.add(ShortTag.valueOf((short) 0x432));
        offsets.add(section);
        data.put("PostProcessing", offsets);
        LegacyGenerationTicks.install(chunk, data);
        var saved = chunk.getTicksForSerialization();
        require(saved.fluids().save(0, fluid -> BuiltInRegistries.FLUID.getKey(fluid).toString()).equals(fluids),
                "installed spring tick roundtrip");
        require(saved.blocks().save(0, block -> BuiltInRegistries.BLOCK.getKey(block).toString()).equals(blocks),
                "installed block tick roundtrip");
        require(chunk.getPostProcessing()[0].getShort(0) == 0x432, "packed post-processing offset");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
