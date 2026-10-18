package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RegionStorageBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Preparation never accesses live region handles or pending writes outside the I/O queue. */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow @Final private RegionFileStorage storage;
    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> retina$loadPreparedRegion(ChunkPos position,
            Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        return ((RegionStorageBridge)(Object)storage).retina$loadAsync(position, () -> original.call(position));
    }
}
