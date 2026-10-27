package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.*;

/** Runtime object binding for Rust's saved-definition metadata output. */
public final class WorldDefinitionContext implements AutoCloseable {
    private final NativeWorldDefinition nativeWorld;
    private final JsonObject metadata;
    private final Map<String, Dimension> dimensions = new LinkedHashMap<>();

    WorldDefinitionContext(NativeWorldDefinition nativeWorld) {
        this.nativeWorld = nativeWorld;
        metadata = JsonParser.parseString(nativeWorld.metadata()).getAsJsonObject();
        metadata.getAsJsonObject("dimensions").entrySet().forEach(entry ->
                dimensions.put(entry.getKey(), new Dimension(entry.getKey(), entry.getValue().getAsJsonObject())));
    }

    public long seed() { return metadata.get("seed").getAsLong(); }
    public int dataVersion() { return metadata.get("data_version").getAsInt(); }
    public boolean structures() { return metadata.get("generate_structures").getAsBoolean(); }
    public int dimensionCount() { return dimensions.size(); }
    public Dimension dimension(ResourceKey<Level> key) { return dimension(key.identifier().toString()); }
    Dimension dimension(String key) {
        var dimension = dimensions.get(key);
        if (dimension == null) throw new IllegalArgumentException("Dimension " + key + " is absent from retina.dat; its generation definition must be explicitly replaced");
        return dimension;
    }
    @Override public void close() { nativeWorld.close(); }

    public final class Dimension {
        private final String key;
        private final JsonObject data;
        private Dimension(String key, JsonObject data) { this.key = key; this.data = data; }
        public String key() { return key; }
        public JsonObject parameters() { return data.getAsJsonObject("parameters").deepCopy(); }
        public String unsupportedReason() { return data.has("unsupported_reason") ? data.get("unsupported_reason").getAsString() : null; }
        long seed() { return WorldDefinitionContext.this.seed(); }
        int dataVersion() { return WorldDefinitionContext.this.dataVersion(); }
        boolean structures() { return WorldDefinitionContext.this.structures(); }
        TerrainRequest request(int x, int z) { return nativeWorld.request(key, x, z); }
        String diagnosticProfile() { return nativeWorld.diagnosticProfile(key); }

        List<Holder<Biome>> biomes(HolderLookup.Provider registry) {
            var shared = metadata.getAsJsonObject("shared_types").getAsJsonArray("biomes");
            var lookup = registry.lookupOrThrow(Registries.BIOME);
            return data.getAsJsonArray("biome_palette").asList().stream().map(index -> (Holder<Biome>) lookup.getOrThrow(
                    ResourceKey.create(Registries.BIOME, Identifier.parse(shared.get(index.getAsInt()).getAsString())))).toList();
        }

        BiomeTerrainProfile bind(HolderLookup.Provider registry) {
            var shared = metadata.getAsJsonObject("shared_types").getAsJsonArray("materials");
            var materials = data.getAsJsonArray("material_palette").asList().stream()
                    .map(index -> resolveMaterial(shared.get(index.getAsInt()).getAsJsonObject())).toArray(BlockState[]::new);
            var biomes = biomes(registry);
            int codec = switch (art.arcane.retina.config.RetinaConfiguration.get().mcaCompression()) {
                case ZLIB -> 2;
                case LZ4 -> 4;
            };
            int profile = nativeWorld.bindDimension(key, codec);
            return new BiomeTerrainProfile(profile, parameters().get("sea_level").getAsInt(), materials, biomes,
                    data.getAsJsonObject("profile_metadata").toString(), this);
        }

        void bindDimensionType(DimensionType type) {
            var p = parameters();
            if (p.get("min_y").getAsInt() != type.minY() || p.get("height").getAsInt() != type.height()
                    || p.get("skylight").getAsBoolean() != type.hasSkyLight())
                throw new IllegalArgumentException("Saved Retina bounds/skylight cannot be installed in dimension " + key
                        + " (saved " + p.get("min_y") + "/" + p.get("height") + "/" + p.get("skylight")
                        + ", platform " + type.minY() + "/" + type.height() + "/" + type.hasSkyLight() + ")");
        }
    }

    private static BlockState resolveMaterial(JsonObject description) {
        String name = description.get("id").getAsString();
        var block = BuiltInRegistries.BLOCK.get(ResourceKey.create(Registries.BLOCK, Identifier.parse(name)))
                .orElseThrow(() -> new IllegalArgumentException("Saved Retina block is unavailable: " + name)).value();
        var state = block.defaultBlockState();
        for (var entry : description.getAsJsonObject("properties").entrySet()) {
            var property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("Saved Retina block property is unavailable: " + name + "." + entry.getKey());
            state = setProperty(state, property, entry.getValue().getAsString());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() ->
                new IllegalArgumentException("Saved Retina block property value is unavailable: " + state + "." + property.getName() + "=" + value)));
    }
}
