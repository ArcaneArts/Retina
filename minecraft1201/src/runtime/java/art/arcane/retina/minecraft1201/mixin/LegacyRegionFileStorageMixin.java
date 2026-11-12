package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyRegionStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
abstract class LegacyRegionFileStorageMixin implements LegacyRegionStorage {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
    @Shadow @Final private Path folder;

    @Invoker("getRegionFile")
    protected abstract RegionFile retina$getRegionFile(ChunkPos position) throws IOException;

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void retina$skipMissingRegion(ChunkPos position, StreamTagVisitor visitor, CallbackInfo callback) {
        // IOWorker has already checked pending writes. A scan should not create a region file.
        if (retina$regionMissing(position)) callback.cancel();
    }

    @Unique
    private boolean retina$regionMissing(ChunkPos position) {
        int x = position.getRegionX();
        int z = position.getRegionZ();
        return !regionCache.containsKey(ChunkPos.asLong(x, z)) && Files.notExists(folder.resolve("r." + x + "." + z + ".mca"));
    }

    @Override public Path retina$folder() { return folder; }

    @Override public boolean retina$hasFullRegion(ChunkPos position) throws IOException {
        if (retina$regionMissing(position)) return false;
        var region = retina$getRegionFile(position);
        int x = position.getRegionX() << 5;
        int z = position.getRegionZ() << 5;
        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                if (!region.hasChunk(new ChunkPos(x + dx, z + dz))) return false;
            }
        }
        return true;
    }

    @Override public void retina$closeRegion(ChunkPos position) throws IOException {
        var cached = regionCache.remove(ChunkPos.asLong(position.getRegionX(), position.getRegionZ()));
        if (cached != null) cached.close();
    }
}
