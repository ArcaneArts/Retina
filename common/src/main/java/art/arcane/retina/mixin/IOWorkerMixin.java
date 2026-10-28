package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RegionStorageBridge;
import art.arcane.retina.worldgen.RegionIoBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Preparation never accesses live region handles or pending writes outside the I/O queue. */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin implements RegionIoBridge {
    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private PriorityConsecutiveExecutor consecutiveExecutor;
    @Unique private boolean retina$closing;

    @Override public boolean retina$regionMode() {
        return ((RegionStorageBridge)(Object)storage).retina$coordinator() != null;
    }

    @Override public CompletableFuture<Void> retina$generateAhead(ChunkPos position) {
        return retina$prepare(position, false);
    }

    @Override public CompletableFuture<Void> retina$prepareForeground(ChunkPos position) {
        return retina$prepare(position, true);
    }

    @Unique private CompletableFuture<Void> retina$prepare(ChunkPos position, boolean foreground) {
        var bridge = (RegionStorageBridge)(Object)storage;
        var coordinator = bridge.retina$coordinator();
        if (coordinator == null) return CompletableFuture.completedFuture(null);
        return coordinator.loadAsync(position, foreground, () -> {
            synchronized (this) {
                if (retina$closing) return CompletableFuture.failedFuture(new IllegalStateException("Region I/O is closing"));
                // IOWorker FOREGROUND is priority 0. Queue publication without decoding/loading a chunk.
                return consecutiveExecutor.scheduleWithResult(0, result -> {
                    try {
                        coordinator.prepare(position, bridge);
                        result.complete(null);
                    } catch (Throwable error) { result.completeExceptionally(error); }
                });
            }
        });
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void retina$stopAhead(CallbackInfo callback) {
        // A publication is either queued before shutdown's drain barrier or rejected here.
        synchronized (this) { retina$closing = true; }
        var coordinator = ((RegionStorageBridge)(Object) storage).retina$coordinator();
        if (coordinator != null) coordinator.stopAdmission();
    }
    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> retina$loadPreparedRegion(ChunkPos position,
            Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        return ((RegionStorageBridge)(Object)storage).retina$loadAsync(position, () -> original.call(position));
    }
}
