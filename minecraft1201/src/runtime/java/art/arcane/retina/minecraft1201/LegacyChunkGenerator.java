package art.arcane.retina.minecraft1201;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** Installs native output into generation-status chunks under the game's storage ownership. */
final class LegacyChunkGenerator extends ChunkGenerator {
    private final ChunkGenerator original;
    private final LegacyDimension saved;
    private final StructurePieceSerializationContext structureContext;

    LegacyChunkGenerator(ChunkGenerator original, LegacyDimension saved, StructurePieceSerializationContext structureContext) {
        super(new SavedBiomes(saved));
        this.original = original;
        this.saved = saved;
        this.structureContext = structureContext;
    }

    @Override protected Codec<? extends ChunkGenerator> codec() { return Codec.unit(this); }

    @Override
    public Optional<ResourceKey<Codec<? extends ChunkGenerator>>> getTypeNameForDataFixer() {
        // The saved LevelStem stays intact. Keep its generator identity for upgrades.
        return original.getTypeNameForDataFixer();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
                                                       StructureManager structures, ChunkAccess chunk) {
        var pos = chunk.getPos();
        return CompletableFuture.supplyAsync(() -> {
            saved.chunk(pos.x, pos.z).install(chunk);
            return chunk;
        }, executor);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor bounds, RandomState random) {
        int height = saved.chunk(x >> 4, z >> 4).height(type, x, z);
        return Math.max(bounds.getMinBuildHeight(), Math.min(bounds.getMaxBuildHeight(), height));
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor bounds, RandomState random) {
        var data = saved.chunk(x >> 4, z >> 4);
        return new NoiseColumn(bounds.getMinBuildHeight(), data.column(x, z, bounds));
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState state, long seed) {
        return ChunkGeneratorStructureState.createForFlat(state, seed, getBiomeSource(), Stream.empty());
    }

    @Override public int getSeaLevel() { return saved.seaLevel; }
    LegacyRegionCoordinator regions() { return saved.regions; }
    @Override public int getMinY() { return saved.minY; }
    @Override public int getGenDepth() { return saved.generatorDepth; }
    @Override public int getSpawnHeight(LevelHeightAccessor bounds) { return saved.spawnHeight; }
    @Override public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos) { }
    @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) { }
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                                       StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) { }
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }
    @Override public void spawnOriginalMobs(WorldGenRegion region) { }
    @Override public void createStructures(RegistryAccess registry, ChunkGeneratorStructureState state, StructureManager structures,
                                          ChunkAccess chunk, StructureTemplateManager templates) {
        if (saved.hasStructures) {
            var pos = chunk.getPos();
            saved.chunk(pos.x, pos.z).installStarts(chunk, structureContext, state.getLevelSeed());
        }
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        if (saved.hasStructures) {
            var pos = chunk.getPos();
            saved.chunk(pos.x, pos.z).installReferences(chunk, level.registryAccess());
        }
    }

    private static final class SavedBiomes extends BiomeSource {
        private final LegacyDimension saved;

        SavedBiomes(LegacyDimension saved) { this.saved = saved; }
        @Override protected Codec<? extends BiomeSource> codec() { return Codec.unit(this); }
        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return saved.biomes.stream(); }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
            return saved.biome(x, y, z);
        }
    }
}
