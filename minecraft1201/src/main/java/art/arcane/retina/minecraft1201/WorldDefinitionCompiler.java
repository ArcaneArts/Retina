package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.BinaryDefinitionTags;
import art.arcane.retina.definition.DefinitionAssembler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Resolves supported 1.20.1 generators and records the reason for each unhandled dimension. */
public final class WorldDefinitionCompiler {
    private static final BinaryDefinitionTags TAGS = BinaryDefinitionTags.INSTANCE;

    private WorldDefinitionCompiler() { }

    public static BinaryDefinitionTags.Compound compile(RegistryAccess registries, Registry<LevelStem> stems,
                                                         WorldOptions options, List<String> packs, boolean noiseTerrainPreview,
                                                         Function<ResourceLocation, Optional<CompoundTag>> templates) {
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
                            ? LegacyEndProfile.compile(registries, stem, noise, options.generateStructures(), templates)
                            : LegacyTerrainProfile.compile(registries, stem, noise, options.seed(), options.generateStructures(), templates);
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
            try {
                assembly.supported(id, parameters, LegacyFlatProfile.export(registries, stem,
                        (FlatLevelSource) stem.generator(), options.generateStructures(), templates), null);
            } catch (IllegalArgumentException unsupported) {
                assembly.unsupported(id, parameters, unsupported.getMessage());
            }
        }
        return assembly.build();
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

    static JsonArray writePalette(JsonObject world, LinkedHashMap<BlockState, Integer> palette, boolean skylight) {
        return writePalette(world, palette, skylight, false);
    }

    static JsonArray writePalette(JsonObject world, LinkedHashMap<BlockState, Integer> palette,
                                  boolean skylight, boolean trees) {
        var materials = new JsonArray();
        var air = new JsonArray();
        var flags = new JsonArray();
        var masks = new JsonArray();
        palette.forEach((state, id) -> {
            materials.add(materialIdentity(state));
            if (state.isAir()) air.add(id);
            int flag = state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA) ? 64 : 0;
            if (state.is(Blocks.BEDROCK)) flag |= 128;
            if (trees) {
                if (LegacyOverworldPlants.soil(state)) flag |= 1;
                if (state.is(BlockTags.LEAVES)) flag |= 4;
                if (state.is(BlockTags.LOGS)) flag |= 8;
                if (state.is(BlockTags.REPLACEABLE_BY_TREES) && (flag & 64) == 0) flag |= 16;
                if (state.is(BlockTags.JUNGLE_LOGS)) flag |= 32;
            }
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
