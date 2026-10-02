package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RegionCoordinator;
import art.arcane.retina.worldgen.RegionStorageBridge;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements RegionStorageBridge {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<Optional<RegionFile>> regionCache;
    @Shadow @Final private Path folder;
    @Unique private RegionCoordinator retina$coordinator;

    @Override
    public void retina$configure(RegionCoordinator coordinator) { retina$coordinator = coordinator; }

    @Override
    public Path retina$folder() { return folder; }

    @Override
    public void retina$closeRegion(ChunkPos position) throws IOException {
        var cached = regionCache.remove(ChunkPos.pack(position.getRegionX(), position.getRegionZ()));
        if (cached != null && cached.isPresent()) cached.get().close();
    }

    @Inject(method = "read", at = @At("HEAD"))
    private void retina$prepareRegion(ChunkPos position, CallbackInfoReturnable<CompoundTag> callback) throws IOException {
        if (retina$coordinator != null) retina$coordinator.prepare(position, this);
    }
}
