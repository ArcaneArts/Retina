package art.arcane.retina.worldgen;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Asynchronous read admission, with live region operations confined to the owning I/O queue. */
public interface RegionStorageBridge {
    void retina$configure(RegionCoordinator coordinator);
    Path retina$folder();
    default RegionCoordinator retina$coordinator() { return null; }
    CompletableFuture<Optional<CompoundTag>> retina$loadAsync(ChunkPos position,
            Supplier<CompletableFuture<Optional<CompoundTag>>> read);
    void retina$closeRegion(ChunkPos position) throws IOException;
}
