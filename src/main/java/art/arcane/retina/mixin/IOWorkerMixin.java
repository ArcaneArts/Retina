package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RegionStorageBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Preparation never accesses live region handles or pending writes outside the I/O queue. */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow @Final private RegionFileStorage storage;
    @Inject(method = "loadAsync", at = @At("HEAD"))
    private void retina$requestRegion(ChunkPos position, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        ((RegionStorageBridge)(Object)storage).retina$request(position);
    }
}
