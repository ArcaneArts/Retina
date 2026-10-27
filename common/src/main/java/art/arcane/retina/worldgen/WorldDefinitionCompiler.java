package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.*;

/** One-time compilation only. No native library or GPU is initialized here. */
public final class WorldDefinitionCompiler {
    public static final int FORMAT_VERSION = 1;
    public static final int GENERATION_VERSION = 1;

    private WorldDefinitionCompiler() { }

    public static CompoundTag compile(MinecraftServer server) {
        var stems = new LinkedHashMap<ResourceKey<LevelStem>, LevelStem>();
        server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).listElements()
                .forEach(holder -> stems.put(holder.key(), holder.value()));
        String build = WorldDefinitionCompiler.class.getPackage().getImplementationVersion();
        return compile(server.registryAccess(), server.getWorldGenSettings().options(), stems,
                StructureProfile.Context.of(server.getStructureTemplateManager(),
                        server.getWorldGenSettings().options().generateStructures()),
                server.getResourceManager().listPacks().map(pack -> pack.packId()).toList(),
                "Retina " + (build == null ? "development" : build));
    }

    static CompoundTag compile(HolderLookup.Provider registry, WorldOptions options,
                               Map<ResourceKey<LevelStem>, LevelStem> stems,
                               StructureProfile.Context structures, List<String> packs, String exporter) {
        var root = new CompoundTag();
        root.putInt("format_version", FORMAT_VERSION);
        root.putInt("generation_version", GENERATION_VERSION);
        root.putString("minecraft_version", SharedConstants.getCurrentVersion().id());
        root.putInt("data_version", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putString("exporter", exporter);
        root.putLong("seed", options.seed());
        root.putBoolean("generate_structures", options.generateStructures());
        root.putBoolean("bonus_chest", options.generateBonusChest());
        var datapacks = new ListTag();
        for (String pack : packs) {
            var entry = new CompoundTag(); entry.putString("id", pack); datapacks.add(entry);
        }
        root.put("datapacks", datapacks);
        var materials = new LinkedHashMap<BlockState, Integer>();
        var biomes = new LinkedHashMap<String, Integer>();
        var dimensions = new CompoundTag();
        var effectiveStructures = new StructureProfile.Context(structures.templates(),
                structures.enabled() && options.generateStructures(), structures.allowedSets());
        var keys = new ArrayList<>(stems.keySet());
        keys.sort(Comparator.comparing(key -> key.identifier().toString()));
        for (var key : keys) {
            var stem = stems.get(key);
            var level = Registries.levelStemToLevel(key);
            var generator = stem.generator();
            var type = stem.type().value();
            var entry = new CompoundTag();
            var parameters = new CompoundTag();
            parameters.putString("generator", generator.getClass().getName());
            parameters.putInt("min_y", type.minY());
            parameters.putInt("height", type.height());
            parameters.putInt("generator_depth", generator.getGenDepth());
            parameters.putInt("sea_level", generator.getSeaLevel());
            parameters.putInt("spawn_height", generator.getSpawnHeight(LevelHeightAccessor.create(type.minY(), type.height())));
            parameters.putBoolean("skylight", type.hasSkyLight());
            parameters.putBoolean("ceiling", type.hasCeiling());
            parameters.putInt("logical_height", type.logicalHeight());
            parameters.putDouble("coordinate_scale", type.coordinateScale());
            parameters.putFloat("base_height", 64F);
            parameters.putFloat("amplitude", 48F);
            parameters.putFloat("frequency", .008F);
            entry.put("parameters", parameters);
            String unsupported = RetinaGenerators.unsupportedReason(level, generator);
            if (unsupported != null) {
                entry.putString("unsupported_reason", unsupported);
                entry.putIntArray("material_palette", new int[0]);
                entry.putIntArray("biome_palette", new int[0]);
            } else {
                var source = new GenerationSource(level, generator, type);
                var data = export(registry, source, options.seed(), effectiveStructures);
                var profile = JsonParser.parseString(data.json()).getAsJsonObject();
                profile.addProperty("dimension", level.identifier().toString());
                profile.add("generation", source.describe());
                profile.remove("materials");
                profile.remove("mca_compression");
                var localMaterials = data.materials();
                var palette = new int[localMaterials.length];
                for (int i = 0; i < palette.length; i++) {
                    palette[i] = materials.computeIfAbsent(localMaterials[i], ignored -> materials.size());
                }
                entry.putIntArray("material_palette", palette);
                entry.putIntArray("biome_palette", data.biomes().stream().mapToInt(biome -> biomes.computeIfAbsent(
                        biome.unwrapKey().orElseThrow().identifier().toString(), ignored -> biomes.size())).toArray());
                entry.put("profile", ProfileNbt.encode(profile));
            }
            dimensions.put(key.identifier().toString(), entry);
        }
        var shared = new CompoundTag();
        var states = new ListTag();
        for (var state : materials.keySet()) {
            // Encode identifiers/properties, never runtime state or registry IDs.
            var encoded = BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, state).getOrThrow();
            var entry = new CompoundTag();
            entry.putString("id", encoded.isJsonPrimitive() ? encoded.getAsString() : encoded.getAsJsonObject().get("id").getAsString());
            var properties = new CompoundTag();
            if (encoded.isJsonObject() && encoded.getAsJsonObject().has("properties")) {
                encoded.getAsJsonObject().getAsJsonObject("properties").entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).forEach(p -> properties.putString(p.getKey(), p.getValue().getAsString()));
            }
            entry.put("properties", properties); states.add(entry);
        }
        shared.put("materials", states);
        var biomeNames = new ListTag();
        biomes.keySet().forEach(id -> biomeNames.add(StringTag.valueOf(id)));
        shared.put("biomes", biomeNames);
        root.put("shared_types", shared);
        root.put("dimensions", dimensions);
        return root;
    }

    static TerrainProfileData export(HolderLookup.Provider registry, GenerationSource source, long seed,
                                     StructureProfile.Context structures) {
        if (source.flat()) return FlatTerrainProfile.export(registry, source, seed, structures, source.dimension().hasSkyLight());
        if (source.endTerrain()) return EndTerrainProfile.export(registry, source, seed, structures);
        return TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(source.generator().getBiomeSource()),
                source.dimension().minY(), source.dimension().height(), seed, source.noise().generatorSettings().value(),
                structures, true, source.dimension().hasSkyLight(), source.enclosedTerrain());
    }
}
