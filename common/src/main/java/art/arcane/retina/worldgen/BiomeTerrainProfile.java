package art.arcane.retina.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

/** Platform identities bound to an immutable dimension loaded from retina.dat. */
public record BiomeTerrainProfile(int nativeId, int seaLevel, BlockState[] materials, List<Holder<Biome>> biomes, String json,
                                  WorldDefinitionContext.Dimension savedDefinition) {
    public BiomeTerrainProfile {
        Objects.requireNonNull(savedDefinition, "A runtime terrain profile requires retina.dat");
    }
    /** Full data is requested only by explicit QA; ordinary adapters receive compact metadata. */
    public String diagnosticJson() { return savedDefinition.diagnosticProfile(); }
}
