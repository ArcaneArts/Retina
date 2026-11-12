package art.arcane.retina.worldgen;

import art.arcane.retina.definition.FlatProfileModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Flat source layers and adjusted features; no density graph or borrowed Overworld terrain settings. */
final class FlatTerrainProfile {
    private FlatTerrainProfile() { }

    static TerrainProfileData export(HolderLookup.Provider registry, GenerationSource source, long seed,
                                     StructureProfile.Context structures, boolean skyLight) {
        var original = ((FlatLevelSource) source.generator()).settings();
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var encoded = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, original).getOrThrow();
        // adjustGenerationSettings mutates the working layers. Never mutate the saved definition.
        var settings = FlatLevelGeneratorSettings.CODEC.parse(ops, encoded).getOrThrow();
        var effective = settings.adjustGenerationSettings(settings.getBiome());
        var biomes = List.of(settings.getBiome());
        int minY = source.dimension().minY(), height = source.dimension().height();
        var palette = new LinkedHashMap<BlockState, Integer>();
        material(palette, Blocks.AIR.defaultBlockState());
        var world = new JsonObject();
        world.addProperty("mca_compression", art.arcane.retina.config.RetinaConfiguration.get().mcaCompression().id());
        world.add("generation", source.describe());
        world.addProperty("ore_layout", 2);
        world.addProperty("biome_scale", 128);
        world.addProperty("blend", .55);
        world.addProperty("sea_level", source.generator().getSeaLevel());
        world.addProperty("generator_depth", source.generator().getGenDepth());
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
            for (int i = 0; i < layer.getHeight() && y < height; i++, y++) {
                boolean later = settings.getLayers().get(y) == null;
                base.add(later ? 0 : id);
                if (later) deferred.add(array(y, id));
            }
        }
        var entries = FlatProfileModel.writeLayers(world, encoded, base, deferred,
                settings.getBiome().unwrapKey().orElseThrow().identifier().toString(),
                settings.getBiome().value().getBaseTemperature());
        var disks = Collections.newSetFromMap(new IdentityHashMap<PlacedFeature, Boolean>());
        world.add("decorations", DecorationProfile.export(registry, biomes, entries, palette, world, disks, b -> effective));
        TerrainFeatureProfile.export(registry, biomes, entries, palette, world, null, seed, List.of(), b -> effective);
        world.add("structures", StructureProfile.export(registry, biomes, palette, structures.restrict(settings.structureOverrides())));
        GeologyProfile.export(registry, biomes, entries, palette, world, minY, height, source.generator().getSeaLevel(), b -> effective);
        DecorationProfile.finishPlacements(world.getAsJsonArray("decorations"), palette, minY, height);
        int beforeStructureTransforms = palette.size();
        StructureProfile.finishMaterials(world.getAsJsonObject("structures"),palette);
        if (palette.size() != beforeStructureTransforms)
            DecorationProfile.finishPlacements(world.getAsJsonArray("decorations"), palette, minY, height);
        TerrainFeatureProfile.finishReplacementTables(entries, palette);
        DecorationProfile.finishCurrentSurvival(world, palette);
        DecorationProfile.finishGenerationLighting(world,skyLight);
        DecorationProfile.materialFlags(world, palette);
        world.add("lighting", LightingProfile.export(palette.keySet(), skyLight));
        StructureProfile.finishMaterials(world.getAsJsonObject("structures"),palette);
        var air = new JsonArray();
        palette.forEach((state, id) -> { if (state.isAir()) air.add(id); });
        world.getAsJsonObject("flat").add("air_materials", air);
        var materials = new JsonArray();
        for (var state : palette.keySet()) materials.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow());
        world.add("materials", materials);
        return new TerrainProfileData(source.generator().getSeaLevel(), palette.keySet().toArray(BlockState[]::new), biomes, world.toString());
    }

    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }
}
