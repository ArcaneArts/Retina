package art.arcane.retina.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

/** Installs saved search metadata without consulting current Structure.biomes rules. */
@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateAccessor {
    @Invoker("<init>")
    static ChunkGeneratorStructureState retina$create(RandomState random, BiomeSource biomes, long seed,
            ChunkPos origin, long ringsSeed, List<Holder<StructureSet>> sets) { throw new AssertionError(); }
    @Accessor("placementsForStructure") Map<Structure, List<StructurePlacement>> retina$placements();
    @Accessor("hasGeneratedPositions") void retina$generated(boolean generated);
}
