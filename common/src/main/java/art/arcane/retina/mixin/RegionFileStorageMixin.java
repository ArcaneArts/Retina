package art.arcane.retina.mixin;

import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.core.LazyRegionCompression;
import art.arcane.retina.core.IdleRegionFiles;
import art.arcane.retina.worldgen.RegionCoordinator;
import art.arcane.retina.worldgen.RegionStorageBridge;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements RegionStorageBridge {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<Optional<RegionFile>> regionCache;
    @Shadow @Final private Path folder;
    @Unique private RegionCoordinator retina$coordinator;
    @Unique private IdleRegionFiles retina$idle;

    @Override
    public void retina$configure(RegionCoordinator coordinator) {
        retina$coordinator = coordinator;
        if (RetinaConfiguration.get().usesLazyCompression()) retina$idle = new IdleRegionFiles();
    }

    @Override
    public CompletableFuture<Optional<CompoundTag>> retina$loadAsync(ChunkPos position,
            Supplier<CompletableFuture<Optional<CompoundTag>>> read) {
        return retina$coordinator == null ? read.get() : retina$coordinator.loadAsync(position, read);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void retina$closePreparation(CallbackInfo callback) {
        if (retina$coordinator != null) {
            retina$coordinator.close();
            if (RetinaConfiguration.get().usesLazyCompression()) LazyRegionCompression.cancel(folder, true);
        }
    }

    @WrapOperation(method = "getRegionFile", at = @At(value = "NEW",
            target = "(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;Z)Lnet/minecraft/world/level/chunk/storage/RegionFile;"))
    private RegionFile retina$openPinned(RegionStorageInfo info, Path path, Path directory, boolean sync,
                                        Operation<RegionFile> original) throws IOException {
        if (retina$idle == null) return original.call(info, path, directory, sync);
        return LazyRegionCompression.open(path, () -> original.call(info, path, directory, sync));
    }

    @Inject(method = "getRegionFile", at = @At("RETURN"))
    private void retina$touchRegion(ChunkPos position, boolean create, CallbackInfoReturnable<RegionFile> callback) {
        if (retina$idle != null && callback.getReturnValue() != null)
            retina$idle.touch(ChunkPos.pack(position.getRegionX(), position.getRegionZ()));
    }

    @Override
    public void retina$closeIdleRegions() throws IOException {
        if (retina$idle != null) {
            retina$idle.closeIdle(this::retina$closeKey);
            retina$coordinator.closeIdlePreviewReaders();
        }
    }

    @Override
    public RegionCoordinator retina$coordinator() { return retina$coordinator; }

    @Override
    public Path retina$folder() { return folder; }

    @Override
    public void retina$closeRegion(ChunkPos position) throws IOException {
        long key = ChunkPos.pack(position.getRegionX(), position.getRegionZ());
        if (retina$idle != null) retina$idle.remove(key);
        retina$closeKey(key);
    }

    @Unique private void retina$closeKey(long key) throws IOException {
        var cached = regionCache.remove(key);
        if (cached != null && cached.isPresent()) cached.get().close();
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void retina$prepareRegion(ChunkPos position, CallbackInfoReturnable<CompoundTag> callback) throws IOException {
        if (retina$coordinator != null) retina$coordinator.prepare(position, this);
    }
}
