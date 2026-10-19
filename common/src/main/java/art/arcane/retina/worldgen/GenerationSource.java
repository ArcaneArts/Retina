package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Effective, unmodified source definition. Only the runtime generator is adapted. */
record GenerationSource(ChunkGenerator generator, DimensionType dimension) {
    JsonObject describe() {
        var data = new JsonObject();
        data.addProperty("version", 1);
        data.addProperty("terrain", flat() ? "flat" : "noise");
        data.addProperty("biomes", generator.getBiomeSource() instanceof FixedBiomeSource ? "fixed" : "climate");
        data.addProperty("min_y", dimension.minY());
        data.addProperty("height", dimension.height());
        data.addProperty("skylight", dimension.hasSkyLight());
        data.addProperty("ceiling", dimension.hasCeiling());
        return data;
    }

    boolean flat() { return generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource; }

    NoiseBasedChunkGenerator noise() { return (NoiseBasedChunkGenerator) generator; }
}
