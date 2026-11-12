package art.arcane.retina.minecraft1201;

import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;

public interface LegacyRegionStorage {
    Path retina$folder();
    void retina$closeRegion(ChunkPos position) throws IOException;
}
