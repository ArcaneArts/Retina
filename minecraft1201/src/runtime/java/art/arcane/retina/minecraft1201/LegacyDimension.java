package art.arcane.retina.minecraft1201;

import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.core.GenerationCache;
import art.arcane.retina.core.NativeRegionBudget;
import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.List;

/** Saved native inputs and Minecraft identities for one 1.20.1 dimension. */
final class LegacyDimension implements AutoCloseable {
    final int minY;
    final int height;
    final int generatorDepth;
    final int seaLevel;
    final int spawnHeight;
    final boolean hasStructures;
    final LegacyStructureLocator locator;
    final List<Holder<Biome>> biomes;
    private final String key;
    private final NativeWorldDefinition definition;
    private final LegacyChunkData.Decoder decoder;
    private final GenerationCache<Long, LegacyChunkData> chunks;
    private final GenerationCache<Long, LegacyChunkData> detachedChunks;
    private final GenerationCache<Long, short[]> biomeGrids;
    final LegacyRegionCoordinator regions;

    LegacyDimension(String key, DimensionType type, RegistryAccess registries, NativeWorldDefinition definition,
                    JsonObject dimension, JsonObject sharedTypes, NativeRegionBudget regionBudget) {
        this.key = key;
        this.definition = definition;
        var params = dimension.getAsJsonObject("parameters");
        minY = params.get("min_y").getAsInt();
        height = params.get("height").getAsInt();
        generatorDepth = params.get("generator_depth").getAsInt();
        seaLevel = params.get("sea_level").getAsInt();
        spawnHeight = params.get("spawn_height").getAsInt();
        var profile = dimension.getAsJsonObject("profile_metadata");
        hasStructures = profile != null && profile.has("structure_sets") && profile.get("structure_sets").getAsInt() != 0;
        if (type.minY() != minY || type.height() != height
                || type.hasSkyLight() != params.get("skylight").getAsBoolean()
                || type.hasCeiling() != params.get("ceiling").getAsBoolean()
                || type.logicalHeight() != params.get("logical_height").getAsInt()
                || type.coordinateScale() != params.get("coordinate_scale").getAsDouble())
            throw new IllegalStateException("Retina saved dimension settings differ from the host for " + key);
        var registry = registries.registryOrThrow(Registries.BIOME);
        var names = sharedTypes.getAsJsonArray("biomes");
        biomes = dimension.getAsJsonArray("biome_palette").asList().stream().<Holder<Biome>>map(index -> {
            String id = names.get(index.getAsInt()).getAsString();
            return registry.getHolder(ResourceKey.create(Registries.BIOME, new ResourceLocation(id)))
                    .orElseThrow(() -> new IllegalStateException("Retina cannot bind saved biome " + id + " in " + key));
        }).toList();
        decoder = new LegacyChunkData.Decoder(registries, biomes.get(0));
        int parallel = Math.min(4, RetinaConfiguration.get().regionParallelism());
        chunks = new GenerationCache<>(64, parallel);
        detachedChunks = new GenerationCache<>(64, parallel);
        biomeGrids = new GenerationCache<>(128, parallel);
        definition.bindDimension(key, 2);
        locator = hasStructures ? new LegacyStructureLocator(definition, key, profile) : null;
        regions = new LegacyRegionCoordinator(definition, key, regionBudget);
    }

    LegacyChunkData chunk(int x, int z) {
        return chunks.get(ChunkPos.asLong(x, z), () -> decoder.read(definition.chunk(key, x, z), x, z, minY, height));
    }

    LegacyChunkData previewChunk(ChunkPos pos) {
        return detachedChunks.get(pos.toLong(), () -> decoder.read(regions.preview(pos), pos.x, pos.z, minY, height));
    }

    Holder<Biome> biome(int x, int y, int z) {
        if (biomes.size() == 1) return biomes.get(0);
        int chunkX = x >> 2, chunkZ = z >> 2;
        short[] grid = biomeGrids.get(ChunkPos.asLong(chunkX, chunkZ), () -> definition.biomes(key, chunkX, chunkZ));
        int localY = Math.max(0, Math.min(QuartPos.fromBlock(height) - 1, y - QuartPos.fromBlock(minY)));
        return biomes.get(Short.toUnsignedInt(grid[(localY * 4 + (z & 3)) * 4 + (x & 3)]));
    }

    @Override
    public void close() {
        regions.close();
        chunks.close();
        detachedChunks.close();
        biomeGrids.close();
    }
}
