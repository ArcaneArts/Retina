package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Effective, unmodified source definition. Only the runtime generator is adapted. */
record GenerationSource(ResourceKey<Level> key, ChunkGenerator generator, DimensionType dimension) {
    GenerationSource(ChunkGenerator generator, DimensionType dimension) {
        this(Level.OVERWORLD, generator, dimension);
    }

    boolean enclosedTerrain() {
        return dimension.hasCeiling() || key.equals(Level.NETHER)
                || !flat() && noise().generatorSettings().is(NoiseGeneratorSettings.NETHER);
    }

    JsonObject describe() {
        var data = new JsonObject();
        data.addProperty("version", 3);
        data.addProperty("dimension", key.identifier().toString());
        data.addProperty("terrain", flat() ? "flat" : "noise");
        data.addProperty("biomes", generator.getBiomeSource() instanceof FixedBiomeSource ? "fixed" : "climate");
        data.addProperty("min_y", dimension.minY());
        data.addProperty("height", dimension.height());
        data.addProperty("skylight", dimension.hasSkyLight());
        data.addProperty("ceiling", dimension.hasCeiling());
        data.addProperty("logical_height", dimension.logicalHeight());
        data.addProperty("coordinate_scale", dimension.coordinateScale());
        if (!flat()) data.add("bounds", GenerationBounds.noise(noise().generatorSettings().value().noiseSettings(),
                dimension.minY(), dimension.height()).describe());
        return data;
    }

    boolean flat() { return generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource; }

    NoiseBasedChunkGenerator noise() { return (NoiseBasedChunkGenerator) generator; }
}
