package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickContainerAccess;

/** Installs native feature updates at the same proto-chunk stage as decoration. */
final class GenerationTicks {
    private GenerationTicks() {}

    static void install(ChunkAccess chunk, CompoundTag data) {
        install(chunk, data, "block_ticks", BuiltInRegistries.BLOCK.byNameCodec(), chunk.getBlockTicks());
        install(chunk, data, "fluid_ticks", BuiltInRegistries.FLUID.byNameCodec(), chunk.getFluidTicks());
    }

    private static <T> void install(ChunkAccess chunk, CompoundTag data, String key,
                                   Codec<T> type, TickContainerAccess<T> destination) {
        var ticks = SavedTick.codec(type).listOf().parse(NbtOps.INSTANCE, data.getListOrEmpty(key)).getOrThrow();
        long order = 0;
        for (var tick : SavedTick.filterTickListForChunk(ticks, chunk.getPos()))
            destination.schedule(tick.unpack(0, order++));
    }
}
