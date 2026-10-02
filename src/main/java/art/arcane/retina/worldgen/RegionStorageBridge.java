package art.arcane.retina.worldgen;

import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;

/** Operations invoked only from the owning IOWorker's serialized queue. */
public interface RegionStorageBridge {
    void retina$configure(RegionCoordinator coordinator);
    Path retina$folder();
    void retina$closeRegion(ChunkPos position) throws IOException;
}
