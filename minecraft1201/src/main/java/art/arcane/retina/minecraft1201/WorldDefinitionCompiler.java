package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.BinaryDefinitionTags;
import art.arcane.retina.definition.DefinitionAssembler;
import art.arcane.retina.definition.FlatProfileModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Resolves supported 1.20.1 generators and records the reason for each unhandled dimension. */
public final class WorldDefinitionCompiler {
    private static final BinaryDefinitionTags TAGS = BinaryDefinitionTags.INSTANCE;

    private WorldDefinitionCompiler() { }

    public static BinaryDefinitionTags.Compound compile(RegistryAccess registries, Registry<LevelStem> stems,
                                                         WorldOptions options, List<String> packs, boolean noiseTerrainPreview) {
        var version = SharedConstants.getCurrentVersion();
        var assembly = new DefinitionAssembler<>(TAGS, version.getName(), version.getDataVersion().getVersion(),
                "Retina Minecraft 1.20.1", options.seed(), options.generateStructures(),
                options.generateBonusChest(), packs);
        var keys = stems.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().location())).toList();
        for (var entry : keys) {
            String id = entry.getKey().location().toString();
            LevelStem stem = entry.getValue();
            var parameters = parameters(stem);
            if (noiseTerrainPreview && stem.generator() instanceof NoiseBasedChunkGenerator noise) {
                JsonObject profile;
                try {
                    profile = LegacyEndProfile.selected(stem, noise)
                            ? LegacyEndProfile.compile(registries, stem, noise)
                            : LegacyTerrainProfile.compile(registries, stem, noise, options.seed());
                } catch (IllegalArgumentException unsupported) {
                    assembly.unsupported(id, parameters, unsupported.getMessage());
                    continue;
                }
                assembly.supported(id, parameters, profile, null);
                continue;
            }
            if (stem.generator().getClass() != FlatLevelSource.class) {
                assembly.unsupported(id, parameters, "No 1.20.1 definition adapter for " + stem.generator().getClass().getName());
                continue;
            }
            var generator = (FlatLevelSource) stem.generator();
            var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
            JsonObject encoded = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, generator.settings())
                    .getOrThrow(false, message -> { throw new IllegalArgumentException(message); }).getAsJsonObject();
            if (options.generateStructures() && generator.settings().structureOverrides().map(set -> set.size() != 0).orElse(true)) {
                assembly.unsupported(id, parameters, "1.20.1 Flat structure export is unavailable");
            } else if (enabled(encoded, "features") || enabled(encoded, "lakes")) {
                assembly.unsupported(id, parameters, "1.20.1 Flat biome decoration or lakes require a feature adapter");
            } else {
                var settings = FlatLevelGeneratorSettings.CODEC.parse(ops, encoded)
                        .getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
                settings.adjustGenerationSettings(settings.getBiome());
                assembly.supported(id, parameters, profile(stem, generator, settings, encoded), null);
            }
        }
        return assembly.build();
    }

    private static boolean enabled(JsonObject settings, String key) {
        return settings.has(key) && settings.get(key).getAsBoolean();
    }

    private static BinaryDefinitionTags.Compound parameters(LevelStem stem) {
        var generator = stem.generator();
        var type = stem.type().value();
        var parameters = TAGS.compound();
        var storage = TAGS.compound();
        TAGS.put(storage, "min_y", TAGS.intTag(type.minY()));
        TAGS.put(storage, "height", TAGS.intTag(type.height()));
        TAGS.put(parameters, "generator", TAGS.string(generator.getClass().getName()));
        TAGS.put(parameters, "min_y", TAGS.intTag(type.minY()));
        TAGS.put(parameters, "height", TAGS.intTag(type.height()));
        TAGS.put(parameters, "storage", storage);
        TAGS.put(parameters, "generator_depth", TAGS.intTag(generator.getGenDepth()));
        TAGS.put(parameters, "sea_level", TAGS.intTag(generator.getSeaLevel()));
        TAGS.put(parameters, "spawn_height", TAGS.intTag(generator.getSpawnHeight(LevelHeightAccessor.create(type.minY(), type.height()))));
        TAGS.put(parameters, "skylight", TAGS.byteTag(type.hasSkyLight() ? (byte) 1 : (byte) 0));
        TAGS.put(parameters, "ceiling", TAGS.byteTag(type.hasCeiling() ? (byte) 1 : (byte) 0));
        TAGS.put(parameters, "logical_height", TAGS.intTag(type.logicalHeight()));
        TAGS.put(parameters, "coordinate_scale", TAGS.doubleTag(type.coordinateScale()));
        TAGS.put(parameters, "base_height", TAGS.floatTag(64F));
        TAGS.put(parameters, "amplitude", TAGS.floatTag(48F));
        TAGS.put(parameters, "frequency", TAGS.floatTag(.008F));
        return parameters;
    }

    private static JsonObject profile(LevelStem stem, FlatLevelSource generator,
                                      FlatLevelGeneratorSettings adjusted, JsonObject encoded) {
        var palette = new LinkedHashMap<BlockState, Integer>();
        material(palette, Blocks.AIR.defaultBlockState());
        var world = new JsonObject();
        world.addProperty("ore_layout", 2);
        world.addProperty("biome_scale", 128);
        world.addProperty("blend", .55);
        world.addProperty("sea_level", generator.getSeaLevel());
        world.addProperty("generator_depth", generator.getGenDepth());
        world.addProperty("stone", material(palette, Blocks.STONE.defaultBlockState()));
        world.addProperty("water", material(palette, Blocks.WATER.defaultBlockState()));
        world.addProperty("bedrock", material(palette, Blocks.BEDROCK.defaultBlockState()));
        world.addProperty("deepslate", material(palette, Blocks.DEEPSLATE.defaultBlockState()));
        world.addProperty("snow", material(palette, Blocks.SNOW_BLOCK.defaultBlockState()));
        world.addProperty("ice", material(palette, Blocks.ICE.defaultBlockState()));

        var base = new JsonArray();
        var deferred = new JsonArray();
        int y = 0;
        for (var layer : adjusted.getLayersInfo()) {
            int id = material(palette, layer.getBlockState());
            for (int i = 0; i < layer.getHeight() && y < stem.type().value().height(); i++, y++) {
                boolean later = adjusted.getLayers().get(y) == null;
                base.add(later ? 0 : id);
                if (later) deferred.add(array(y, id));
            }
        }
        var biome = adjusted.getBiome();
        FlatProfileModel.writeLayers(world, encoded, base, deferred,
                biome.unwrapKey().orElseThrow().location().toString(), biome.value().getBaseTemperature());

        var air = writePalette(world, palette, stem.type().value().hasSkyLight());
        world.getAsJsonObject("flat").add("air_materials", air);
        return world;
    }

    static JsonArray writePalette(JsonObject world, LinkedHashMap<BlockState, Integer> palette, boolean skylight) {
        var materials = new JsonArray();
        var air = new JsonArray();
        var flags = new JsonArray();
        var masks = new JsonArray();
        palette.forEach((state, id) -> {
            materials.add(materialIdentity(state));
            if (state.isAir()) air.add(id);
            int flag = state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA) ? 64 : 0;
            if (state.is(Blocks.BEDROCK)) flag |= 128;
            flags.add(flag);
            int mask = 0;
            for (var heightmap : Heightmap.Types.values()) {
                if (heightmap.isOpaque().test(state)) mask |= 1 << heightmap.ordinal();
            }
            masks.add(mask);
        });
        world.add("materials", materials);
        world.add("material_flags", flags);
        world.add("heightmap_masks", masks);
        world.add("lighting", LightingExport.export(palette.keySet(), skylight));
        return air;
    }

    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }

    private static JsonObject materialIdentity(BlockState state) {
        var result = new JsonObject();
        result.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = new JsonObject();
        for (var property : state.getProperties()) {
            properties.addProperty(property.getName(), propertyValue(state, property));
        }
        result.add("properties", properties);
        return result;
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
