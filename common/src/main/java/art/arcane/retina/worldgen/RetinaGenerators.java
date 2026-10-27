package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Adapts effective server generators without rewriting registries or saved world settings. */
public final class RetinaGenerators {
    private RetinaGenerators() { }

    public static ChunkGenerator adapt(net.minecraft.server.MinecraftServer server, ResourceKey<Level> dimension,
                                       LevelStem stem, ChunkGenerator generator) {
        var saved = WorldDefinitions.get(server).dimension(dimension);
        if (saved.unsupportedReason() != null) {
            if (generator instanceof RetinaChunkGenerator) throw new IllegalArgumentException(
                    "retina.dat cannot execute saved Retina dimension " + dimension.identifier() + ": "
                            + saved.unsupportedReason() + "; explicitly replace the generation definition to compile its current settings");
            Retina.LOGGER.info("Retina leaving dimension {} on {}: {}", dimension.identifier(),
                    generator.getClass().getName(), saved.unsupportedReason());
            return generator;
        }
        saved.bindDimensionType(stem.type().value());
        return RetinaChunkGenerator.fromDefinition(saved, server.registryAccess());
    }

    public static ChunkGenerator adapt(ResourceKey<Level> dimension, LevelStem stem, ChunkGenerator generator) {
        if (generator instanceof RetinaChunkGenerator) return generator;
        String unsupported = unsupportedReason(dimension, generator);
        if (unsupported != null) {
            Retina.LOGGER.info("Retina leaving dimension {} on generator {}: {}", dimension.identifier(),
                    generator.getClass().getName(), unsupported);
            return generator;
        }
        var result = RetinaChunkGenerator.fromSource(new GenerationSource(dimension, generator, stem.type().value()));
        Retina.LOGGER.info("Retina automatically activated for dimension {} using {}", dimension.identifier(),
                generator.getClass().getName());
        return result;
    }

    static String unsupportedReason(ResourceKey<Level> dimension, ChunkGenerator generator) {
        if (generator instanceof RetinaChunkGenerator) return null;
        if (!dimension.equals(Level.OVERWORLD) && !dimension.equals(Level.NETHER)) return "dimension adapter is not implemented; End support is deferred";
        if (generator.getClass() == net.minecraft.world.level.levelgen.FlatLevelSource.class) return null;
        // Subclasses can implement arbitrary generation behavior. Adapting their fields alone would lose it.
        if (generator.getClass() != NoiseBasedChunkGenerator.class) return "no adapter for this generator implementation";
        var noise = (NoiseBasedChunkGenerator) generator;
        if (noise.generatorSettings().is(NoiseGeneratorSettings.END)) return "End noise settings are not supported yet";
        var biomes = generator.getBiomeSource();
        if (biomes.getClass() != MultiNoiseBiomeSource.class && biomes.getClass() != FixedBiomeSource.class)
            return "no adapter for biome source " + biomes.getClass().getName();
        return null;
    }
}
