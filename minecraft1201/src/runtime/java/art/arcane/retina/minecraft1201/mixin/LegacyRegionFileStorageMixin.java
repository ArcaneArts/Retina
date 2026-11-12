package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyRegionStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.io.IOException;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
abstract class LegacyRegionFileStorageMixin implements LegacyRegionStorage {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
    @Shadow @Final private Path folder;

    @Override public Path retina$folder() { return folder; }

    @Override public void retina$closeRegion(ChunkPos position) throws IOException {
        var cached = regionCache.remove(ChunkPos.asLong(position.getRegionX(), position.getRegionZ()));
        if (cached != null) cached.close();
    }
}
