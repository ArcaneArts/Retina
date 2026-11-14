package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyRegionStorage;
import art.arcane.retina.core.LazyRegionCompression;
import art.arcane.retina.core.IdleRegionFiles;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
abstract class LegacyRegionFileStorageMixin implements LegacyRegionStorage {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<RegionFile> regionCache;
    @Shadow @Final private Path folder;
    @Unique private IdleRegionFiles retina$idle;

    @Override public void retina$enableLazyCompression() { retina$idle = new IdleRegionFiles(); }

    @Inject(method = "close", at = @At("HEAD"))
    private void retina$stopCompression(CallbackInfo callback) {
        if (retina$idle != null) LazyRegionCompression.cancel(folder, true);
    }

    @WrapOperation(method = "getRegionFile", at = @At(value = "NEW",
            target = "(Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;"))
    private RegionFile retina$openPinned(Path path, Path directory, boolean sync,
                                        Operation<RegionFile> original) throws IOException {
        return retina$idle != null ? LazyRegionCompression.open(path, () -> original.call(path, directory, sync))
                : original.call(path, directory, sync);
    }

    @Inject(method = "getRegionFile", at = @At("RETURN"))
    private void retina$touchRegion(ChunkPos position, CallbackInfoReturnable<RegionFile> callback) {
        if (retina$idle != null && callback.getReturnValue() != null)
            retina$idle.touch(ChunkPos.asLong(position.getRegionX(), position.getRegionZ()));
    }

    @Override
    public void retina$closeIdleRegions() throws IOException {
        if (retina$idle != null) retina$idle.closeIdle(this::retina$closeKey);
    }

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
        long key = ChunkPos.asLong(position.getRegionX(), position.getRegionZ());
        if (retina$idle != null) retina$idle.remove(key);
        retina$closeKey(key);
    }

    @Unique private void retina$closeKey(long key) throws IOException {
        var cached = regionCache.remove(key);
        if (cached != null) cached.close();
    }
}
