package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.FlatProfileModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

/** Exports the adjusted 1.20.1 Flat layers, biome features and structure selection. */
final class LegacyFlatProfile {
    private LegacyFlatProfile() { }

    static JsonObject export(RegistryAccess registries, LevelStem stem, FlatLevelSource generator, boolean structuresEnabled,
                             Function<ResourceLocation, Optional<CompoundTag>> templates) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var original = generator.settings();
        var encoded = decode(FlatLevelGeneratorSettings.CODEC.encodeStart(ops, original));
        // The game nulls deferred entries during adjustment. Work on a copy of the saved settings.
        var settings = decode(FlatLevelGeneratorSettings.CODEC.parse(ops, encoded));
        var biome = settings.getBiome();
        var biomes = List.of(biome);
        var effective = settings.adjustGenerationSettings(biome);
        var inherited = Collections.newSetFromMap(new IdentityHashMap<PlacedFeature, Boolean>());
        biome.value().getGenerationSettings().features().forEach(step ->
                step.forEach(feature -> inherited.add(feature.value())));

        var type = stem.type().value();
        var storage = LevelHeightAccessor.create(type.minY(), type.height());
        var context = new WorldGenerationContext(generator, storage);
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
        for (var layer : original.getLayersInfo()) {
            int id = material(palette, layer.getBlockState());
            for (int i = 0; i < layer.getHeight() && y < type.height(); i++, y++) {
                boolean later = settings.getLayers().get(y) == null;
                base.add(later ? 0 : id);
                if (later) deferred.add(array(y, id));
            }
        }
        var biomeEntries = FlatProfileModel.writeLayers(world, encoded, base, deferred,
                biome.unwrapKey().orElseThrow().location().toString(), biome.value().getBaseTemperature());

        var features = new LegacyOverworldFeatures(registries, context, storage, palette, type.hasSkyLight(), true, templates);
        features.export(registries, biomes, biomes, biomeEntries, world, ignored -> effective,
                root -> root.feature().value().feature() == Feature.FILL_LAYER && !inherited.contains(root));
        features.reserveWaterlogging();

        JsonObject structures = null;
        if (structuresEnabled) {
            var sourceSets = registries.registryOrThrow(Registries.STRUCTURE_SET);
            var omitted = new JsonArray();
            var overrides = settings.structureOverrides().map(selected -> {
                Set<ResourceLocation> ids = new HashSet<>();
                for (var holder : selected) {
                    var id = sourceSets.getKey(holder.value());
                    if (id != null) ids.add(id);
                    else omitted.add("placement:inline_flat_structure_set");
                }
                return ids;
            });
            structures = type.hasCeiling()
                    ? LegacyNetherStructures.export(registries, biomes, palette, context, templates, overrides)
                    : LegacyLandmarks.export(registries, biomes, palette, overrides, LegacyStructureSets.RingBiomeSource.FLAT,
                            generator.getMinY());
            var omissions = structures.getAsJsonArray("omissions");
            omitted.forEach(omissions::add);
            LegacyJigsawProfile.add(registries, palette, templates, context, structures);
            world.add("structures", structures);
        }

        var geology = LegacyGeologyProfile.collect(registries, biomes, world, palette, context, ignored -> effective);
        LegacyStructureProcessors.prepare(structures, palette);
        LegacyPortalProfile.prepare(structures, palette);
        features.reserveSurfaceFreeze();
        geology.finish(world, storage, type.hasCeiling(), false, null);
        LegacyNetherStructures.finish(structures, palette);
        LegacyStructureProcessors.finish(structures, palette);
        features.finish(world, biomes);
        var air = WorldDefinitionCompiler.writePalette(world, palette, type.hasSkyLight(), true);
        world.getAsJsonObject("flat").add("air_materials", air);
        return world;
    }
}
