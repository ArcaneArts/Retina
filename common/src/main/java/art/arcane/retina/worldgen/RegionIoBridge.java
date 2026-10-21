package art.arcane.retina.worldgen;

import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.CompletableFuture;

/** Publish prepared MCA terrain without requesting a FULL Minecraft chunk. */
public interface RegionIoBridge {
    boolean retina$regionMode();
    CompletableFuture<Void> retina$generateAhead(ChunkPos position);
    CompletableFuture<Void> retina$prepareForeground(ChunkPos position);
}
