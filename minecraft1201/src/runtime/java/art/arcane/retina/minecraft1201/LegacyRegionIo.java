package art.arcane.retina.minecraft1201;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;

public interface LegacyRegionIo {
    void retina$configure(LegacyRegionCoordinator regions);
    int retina$regionLimit();
    CompletableFuture<Void> retina$generateAhead(ChunkPos position);
}
