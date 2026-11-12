package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyRegionCoordinator;
import art.arcane.retina.minecraft1201.LegacyRegionIo;
import art.arcane.retina.minecraft1201.LegacyRegionStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Mixin(IOWorker.class)
abstract class LegacyIOWorkerMixin implements LegacyRegionIo {
    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;
    @Unique private volatile LegacyRegionCoordinator retina$regions;
    @Unique private boolean retina$closing;

    @Invoker("submitTask")
    protected abstract <T> CompletableFuture<T> retina$submit(Supplier<Either<T, Exception>> task);

    @Override public void retina$configure(LegacyRegionCoordinator regions) {
        retina$regions = regions;
    }

    @Override public int retina$regionLimit() {
        var regions = retina$regions;
        return regions == null ? 0 : regions.limit();
    }

    @Override public CompletableFuture<Void> retina$generateAhead(ChunkPos position) {
        var regions = retina$regions;
        if (regions == null) return CompletableFuture.completedFuture(null);
        var observer = new CompletableFuture<Void>();
        retina$checkRegion(position).whenComplete((complete, error) -> {
            if (observer.isDone()) return;
            if (error != null) {
                observer.completeExceptionally(error);
            } else if (complete) {
                observer.complete(null);
            } else {
                var load = regions.load(position, false, () -> retina$publishAhead(position, regions));
                observer.whenComplete((value, failure) -> { if (observer.isCancelled()) load.cancel(false); });
                load.whenComplete((value, failure) -> {
                    if (failure != null) observer.completeExceptionally(failure);
                    else observer.complete(null);
                });
            }
        });
        return observer;
    }

    @Unique
    private synchronized CompletableFuture<Boolean> retina$checkRegion(ChunkPos position) {
        if (retina$closing) return CompletableFuture.failedFuture(new CancellationException("Retina chunk I/O is closing"));
        return retina$submit(() -> {
            try {
                return Either.left(((LegacyRegionStorage) (Object) storage).retina$hasFullRegion(position));
            } catch (RuntimeException | IOException error) {
                return Either.right(error);
            }
        });
    }

    @Unique
    private synchronized CompletableFuture<Void> retina$publishAhead(ChunkPos position, LegacyRegionCoordinator regions) {
        if (retina$closing) return CompletableFuture.failedFuture(new CancellationException("Retina chunk I/O is closing"));
        return retina$submit(() -> {
            try {
                regions.publish(position, (LegacyRegionStorage) (Object) storage);
                return Either.left(null);
            } catch (RuntimeException | IOException error) {
                return Either.right(error);
            }
        });
    }

    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> retina$load(ChunkPos position,
            Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        var regions = retina$regions;
        if (regions == null) return original.call(position);
        var observer = new CompletableFuture<Optional<CompoundTag>>();
        retina$read(position, original).whenComplete((existing, error) -> {
            if (observer.isDone()) return;
            if (error != null) {
                observer.completeExceptionally(error);
            } else if (existing.isPresent()) {
                observer.complete(existing);
            } else {
                var load = regions.load(position, true, () -> retina$publish(position, regions, original));
                observer.whenComplete((value, failure) -> {
                    if (observer.isCancelled()) load.cancel(false);
                });
                load.whenComplete((value, failure) -> {
                    if (failure != null) observer.completeExceptionally(failure);
                    else observer.complete(value);
                });
            }
        });
        return observer;
    }

    @Unique
    private synchronized CompletableFuture<Optional<CompoundTag>> retina$read(ChunkPos position,
            Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        if (retina$closing) return CompletableFuture.failedFuture(new CancellationException("Retina chunk I/O is closing"));
        return original.call(position);
    }

    @Unique
    private synchronized CompletableFuture<Optional<CompoundTag>> retina$publish(ChunkPos position,
            LegacyRegionCoordinator regions, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        if (retina$closing) return CompletableFuture.failedFuture(new CancellationException("Retina chunk I/O is closing"));
        return this.<CompoundTag>retina$submit(() -> {
            try {
                // A pending store is authoritative even if it hasn't reached the disk yet.
                if (pendingWrites.containsKey(position)) return Either.left(null);
                regions.publish(position, (LegacyRegionStorage) (Object) storage);
                var data = storage.read(position);
                if (data == null) throw new IOException("MCA record is still unreadable after publication");
                return Either.left(data);
            } catch (Throwable error) {
                return Either.right(new IOException("Cannot publish Retina region for " + position, error));
            }
        }).thenCompose(data -> data == null ? retina$read(position, original)
                : CompletableFuture.completedFuture(Optional.of(data)));
    }

    @WrapMethod(method = "close")
    private void retina$close(Operation<Void> original) {
        var regions = retina$regions;
        if (regions == null) {
            original.call();
            return;
        }
        synchronized (this) { retina$closing = true; }
        regions.stopAdmission();
        try { original.call(); }
        finally { regions.close(); }
    }
}
