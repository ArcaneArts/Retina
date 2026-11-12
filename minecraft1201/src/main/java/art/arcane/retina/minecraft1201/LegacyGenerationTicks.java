package art.arcane.retina.minecraft1201;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickContainerAccess;

/** Install generation updates through the 1.20.1 chunk scheduler and storage codec. */
final class LegacyGenerationTicks {
    private LegacyGenerationTicks() { }

    static void install(ChunkAccess chunk, CompoundTag data) {
        install(chunk, data, "block_ticks", BuiltInRegistries.BLOCK, chunk.getBlockTicks());
        install(chunk, data, "fluid_ticks", BuiltInRegistries.FLUID, chunk.getFluidTicks());
        var processing = data.getList("PostProcessing", Tag.TAG_LIST);
        for (int section = 0; section < processing.size(); section++) {
            for (var packed : (ListTag) processing.get(section)) {
                chunk.addPackedPostProcess(((ShortTag) packed).getAsShort(), section);
            }
        }
    }

    private static <T> void install(ChunkAccess chunk, CompoundTag data, String key,
                                    Registry<T> registry, TickContainerAccess<T> destination) {
        long[] order = {0};
        SavedTick.loadTickList(data.getList(key, Tag.TAG_COMPOUND),
                id -> registry.getOptional(ResourceLocation.tryParse(id)), chunk.getPos(),
                tick -> destination.schedule(tick.unpack(0, order[0]++)));
    }
}
