package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Explicit native End terrain from the actual loaded generator definition. */
final class EndTerrainProfile {
    private EndTerrainProfile() { }

    static TerrainProfileData export(HolderLookup.Provider registry, GenerationSource source, long seed,
                                     StructureProfile.Context structures) {
        boolean skyLight = source.dimension().hasSkyLight();
        String unsupported = EndTerrainSupport.unsupportedReason(registry, source);
        if (unsupported != null) throw new IllegalArgumentException(unsupported);
        var settings = source.noise().generatorSettings().value();
        var biomes = RetinaBiomeSource.fromSelected(source.generator().getBiomeSource()).biomes();
        var bounds = GenerationBounds.noise(settings.noiseSettings(),
                source.dimension().minY(), source.dimension().height());
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
        var end = new JsonObject();
        end.addProperty("material", material(palette, settings.defaultBlock()));
        var mapping = new JsonArray();
        for (String id : List.of("the_end", "end_highlands", "end_midlands", "small_end_islands", "end_barrens")) {
            int index = 0;
            if (!(source.generator().getBiomeSource() instanceof net.minecraft.world.level.biome.FixedBiomeSource)) {
                index = -1;
                for (int i = 0; i < biomes.size(); i++)
                    if (biomes.get(i).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:" + id)) index = i;
                if (index < 0) throw new IllegalArgumentException("End source is missing biome " + id);
            }
            mapping.add(index);
        }
        end.add("biomes", mapping); world.add("end", end);
        world.addProperty("ordered_decorations", true);
        world.addProperty("decoration_source_order", true);
        world.addProperty("decoration_patch_halo", true);
        var entries = new JsonArray();
        for (var biome : biomes) {
            var entry = new JsonObject();
            entry.addProperty("id", biome.unwrapKey().orElseThrow().identifier().toString());
            entry.addProperty("temperature", biome.value().getBaseTemperature());
            entry.add("climate", array(0, 0, 0, 0)); entry.add("terrain", array(0, 0, 0));
            int stone = end.get("material").getAsInt();
            entry.addProperty("top", stone); entry.addProperty("filler", stone); entry.addProperty("underwater", stone);
            entry.addProperty("flags", 0); entries.add(entry);
        }
        world.add("biomes", entries);
        world.add("climate_targets", new JsonArray());
        var noises = new JsonArray();
        // Shared GPU buffer layout reserves four noise slots; native End terrain never evaluates them.
        for (int i = 0; i < 4; i++) {
            var noise = new JsonObject();
            noise.addProperty("frequency", 1);
            noise.addProperty("amplitude", 1);
            noise.add("modifiers", array(1));
            noises.add(noise);
        }
        world.add("noises", noises);
        var disks = Collections.newSetFromMap(new IdentityHashMap<PlacedFeature, Boolean>());
        world.add("decorations", DecorationProfile.export(registry, biomes, entries, palette, world, disks, b -> b.value().getGenerationSettings(), new java.util.ArrayList<>(source.generator().getBiomeSource().possibleBiomes())));
        TerrainFeatureProfile.export(registry, biomes, entries, palette, world, settings.materialRule().value(), seed, List.of());
        var structureData = StructureProfile.export(registry, biomes, palette, structures, true);
        for (var definition : structureData.getAsJsonArray("definitions")) {
            var config = definition.getAsJsonObject().getAsJsonObject("config");
            if (config.has("start_height")) bounds.resolveHeightProvider(config.get("start_height"), source.generator().getSeaLevel());
            if (config.has("height")) bounds.resolveHeightProvider(config.get("height"), source.generator().getSeaLevel());
        }
        world.add("structures", structureData);
        GeologyProfile.export(registry, biomes, entries, palette, world, bounds, source.generator().getSeaLevel());
        finishPlacements(world.getAsJsonArray("decorations"), palette, source);
        int beforeStructureTransforms = palette.size();
        StructureProfile.finishMaterials(world.getAsJsonObject("structures"),palette);
        if (palette.size() != beforeStructureTransforms)
            finishPlacements(world.getAsJsonArray("decorations"), palette, source);
        TerrainFeatureProfile.finishReplacementTables(entries, palette);
        DecorationProfile.finishCurrentSurvival(world, palette);
        DecorationProfile.finishGenerationLighting(world,skyLight);
        DecorationProfile.materialFlags(world, palette);
        world.add("lighting", LightingProfile.export(palette.keySet(), skyLight));
        StructureProfile.finishMaterials(world.getAsJsonObject("structures"),palette);
        var materials = new JsonArray();
        for (var state : palette.keySet()) materials.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow());
        world.add("materials", materials);
        return new TerrainProfileData(source.generator().getSeaLevel(), palette.keySet().toArray(BlockState[]::new), biomes, world.toString());
    }

    static void finishPlacements(JsonArray recipes, LinkedHashMap<BlockState, Integer> palette, GenerationSource source) {
        var bounds = GenerationBounds.noise(source.noise().generatorSettings().value().noiseSettings(),
                source.dimension().minY(), source.dimension().height());
        DecorationProfile.finishPlacements(recipes, palette, bounds, source.generator().getSeaLevel());
    }

    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }

    private static JsonArray array(int... values) {
        var result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }
}
