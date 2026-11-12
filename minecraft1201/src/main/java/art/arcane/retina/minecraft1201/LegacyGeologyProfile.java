package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.GeologyHeightModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;

final class LegacyGeologyProfile {
    private record Ore(JsonObject recipe, OreConfiguration configuration) { }

    private final RegistryOps<JsonElement> ops;
    private final WorldGenerationContext bounds;
    private final int sea;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final ArrayList<Ore> ores = new ArrayList<>();
    private final HashSet<Block> carverTargets = new HashSet<>();
    private final TreeSet<String> omissions = new TreeSet<>();

    private LegacyGeologyProfile(RegistryAccess registries, WorldGenerationContext bounds, int sea,
                                 LinkedHashMap<BlockState, Integer> palette) {
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.bounds = bounds;
        this.sea = sea;
        this.palette = palette;
    }

    static void export(RegistryAccess registries, LegacyBiomeProfile biomes, JsonObject world,
                       LinkedHashMap<BlockState, Integer> palette, WorldGenerationContext bounds, LevelHeightAccessor storage,
                       boolean ceiling, boolean oreVeins, LegacyDensityCompiler densityCompiler) {
        var export = new LegacyGeologyProfile(registries, bounds, world.get("sea_level").getAsInt(), palette);
        var profiles = world.getAsJsonArray("biomes");
        var oreIndices = new IdentityHashMap<PlacedFeature, Integer>();
        for (int i = 0; i < biomes.biomes().size(); i++) {
            var biome = biomes.biomes().get(i);
            var settings = biome.value().getGenerationSettings();
            var ids = new JsonArray();
            for (var step : settings.features()) for (var holder : step) {
                var placed = holder.value();
                var feature = placed.feature().value().feature();
                if (feature != Feature.ORE && feature != Feature.SCATTERED_ORE) continue;
                String id = holder.unwrapKey().map(key -> key.location().toString())
                        .orElse("inline/placed_ore_" + oreIndices.size());
                int index = oreIndices.computeIfAbsent(placed, p -> export.ore(id, p));
                if (index >= 0) ids.add(index);
            }
            var carvers = new JsonArray();
            for (var holder : settings.getCarvers(GenerationStep.Carving.AIR)) {
                String id = holder.unwrapKey().map(key -> key.location().toString()).orElse("inline/carver");
                if (carvers.size() == 4) {
                    export.omissions.add(id + ":native-per-biome-carver-limit");
                    continue;
                }
                var recipe = export.carver(id, holder.value());
                if (recipe != null) carvers.add(recipe);
            }
            for (var holder : settings.getCarvers(GenerationStep.Carving.LIQUID)) {
                export.omissions.add(holder.unwrapKey().map(key -> key.location().toString()).orElse("inline/carver")
                        + ":liquid-carver");
            }
            var profile = profiles.get(i).getAsJsonObject();
            profile.add("ores", ids);
            profile.add("carvers", carvers);
        }

        var recipes = new JsonArray();
        var random = RandomSource.create(0);
        for (var ore : export.ores) {
            var replacements = new JsonArray();
            for (var state : palette.keySet()) {
                int output = 0;
                for (var target : ore.configuration().targetStates) {
                    if (target.target.test(state, random)) {
                        output = palette.get(target.state);
                        break;
                    }
                }
                replacements.add(output);
            }
            var band = new JsonObject();
            band.addProperty("min", storage.getMinBuildHeight());
            band.addProperty("max", storage.getMaxBuildHeight() - 1);
            band.add("materials", replacements);
            var bands = new JsonArray();
            bands.add(band);
            ore.recipe().add("replacement_bands", bands);
            recipes.add(ore.recipe());
        }
        world.add("ores", recipes);
        var carveable = new JsonArray();
        palette.keySet().forEach(state -> carveable.add(export.carverTargets.contains(state.getBlock())
                && state.getFluidState().isEmpty()));
        world.add("carveable", carveable);
        world.addProperty("geology_min_y", bounds.getMinGenY());
        world.addProperty("geology_height", bounds.getGenDepth());
        var noises = new JsonArray();
        if (!ceiling) {
            for (String noise : List.of("cave_cheese", "spaghetti_3d_1", "spaghetti_3d_2", "cave_layer", "spaghetti_roughness", "pillar")) {
                noises.add(LegacyTerrainProfile.fallbackNoise(densityCompiler, noise));
            }
        }
        world.add("cave_noises", noises);
        if (oreVeins) export.omissions.add("noise_router:large_ore_veins");
        var reports = new JsonArray();
        export.omissions.forEach(reports::add);
        world.add("geology_omissions", reports);
    }

    private int ore(String id, PlacedFeature placed) {
        var configured = placed.feature().value();
        if (!(configured.config() instanceof OreConfiguration ore)) return omitOre(id, "configuration");
        for (var target : ore.targetStates) {
            var rule = target.target;
            if (!(rule instanceof BlockMatchTest || rule instanceof BlockStateMatchTest || rule instanceof TagMatchTest)) {
                return omitOre(id, "replacement-rule");
            }
        }
        int min = 1, max = 1, rarity = 1;
        boolean spread = false;
        JsonObject height = null;
        for (var modifier : placed.placement()) {
            var data = decode(PlacementModifier.CODEC.encodeStart(ops, modifier)).getAsJsonObject();
            switch (type(data)) {
                case "count" -> {
                    var count = data.get("count");
                    if (count.isJsonPrimitive()) min = max = count.getAsInt();
                    else {
                        var provider = count.getAsJsonObject();
                        if (!type(provider).equals("uniform")) return omitOre(id, "count");
                        var limits = provider.getAsJsonObject("value");
                        min = limits.get("min_inclusive").getAsInt();
                        max = limits.get("max_inclusive").getAsInt();
                    }
                }
                case "rarity_filter" -> rarity = data.get("chance").getAsInt();
                case "height_range" -> {
                    try {
                        height = height(data.get("height"));
                    } catch (IllegalArgumentException unsupported) {
                        return omitOre(id, "height-range");
                    }
                }
                case "in_square" -> spread = true;
                case "biome" -> { }
                default -> { return omitOre(id, type(data)); }
            }
        }
        if (height == null || !spread || min < 0 || min > max || max > 1024 || ore.size > 64 || rarity < 1) {
            return omitOre(id, "placement");
        }
        var recipe = new JsonObject();
        recipe.addProperty("id", id);
        recipe.addProperty("size", ore.size);
        recipe.addProperty("discard", ore.discardChanceOnAirExposure);
        recipe.addProperty("scattered", configured.feature() == Feature.SCATTERED_ORE);
        recipe.addProperty("count_min", min);
        recipe.addProperty("count_max", max);
        recipe.addProperty("rarity", rarity);
        recipe.add("height", height);
        ore.targetStates.forEach(target -> palette.computeIfAbsent(target.state, ignored -> palette.size()));
        int index = ores.size();
        ores.add(new Ore(recipe, ore));
        return index;
    }

    private int omitOre(String id, String reason) {
        omissions.add(id + ":" + reason);
        return -1;
    }

    private JsonObject carver(String id, ConfiguredWorldCarver<?> configured) {
        var config = configured.config();
        var carver = configured.worldCarver();
        if (config.debugSettings.isDebugMode()) {
            omissions.add(id + ":debug-carver");
            return null;
        }
        var out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("probability", config.probability);
        try {
            out.add("height", height(decode(HeightProvider.CODEC.encodeStart(ops, config.y))));
        } catch (IllegalArgumentException unsupported) {
            omissions.add(id + ":height-range");
            return null;
        }
        if ((carver == WorldCarver.CAVE || carver == WorldCarver.NETHER_CAVE)
                && config instanceof CaveCarverConfiguration cave) {
            boolean nether = carver == WorldCarver.NETHER_CAVE;
            var encoded = decode(CaveCarverConfiguration.CODEC.encodeStart(ops, cave)).getAsJsonObject();
            var floor = decode(FloatProvider.CODEC.parse(ops, encoded.get("floor_level")));
            // The legacy carver draws a nested uniform count below 15 or 10.
            out.addProperty("kind", 0);
            out.addProperty("count", (nether ? 9 : 14) / 8.0);
            out.addProperty("thickness", nether ? 3.0 : 1.6125);
            out.addProperty("horizontal", mean(cave.horizontalRadiusMultiplier));
            out.addProperty("vertical", mean(cave.verticalRadiusMultiplier) * (nether ? 5 : 1));
            out.addProperty("floor", mean(floor));
            out.addProperty("room", 0.5);
            out.addProperty("start_vertical", mean(cave.yScale));
        } else if (carver == WorldCarver.CANYON && config instanceof CanyonCarverConfiguration canyon) {
            var shape = canyon.shape;
            out.addProperty("kind", 1);
            out.addProperty("count", 1);
            out.addProperty("thickness", mean(shape.thickness));
            out.addProperty("horizontal", mean(shape.horizontalRadiusFactor));
            out.addProperty("vertical", mean(canyon.yScale));
            out.addProperty("floor", -1);
            out.addProperty("room", mean(shape.distanceFactor));
            out.addProperty("width_smoothness", shape.widthSmoothness);
            out.addProperty("vertical_default", shape.verticalRadiusDefaultFactor);
            out.addProperty("vertical_center", shape.verticalRadiusCenterFactor);
            out.add("thickness_range", interval(shape.thickness));
            out.add("horizontal_range", interval(shape.horizontalRadiusFactor));
            out.add("distance_range", interval(shape.distanceFactor));
            out.add("vertical_range", interval(canyon.yScale));
            out.add("rotation_range", interval(canyon.verticalRotation));
        } else {
            omissions.add(id + ":unsupported-carver");
            return null;
        }
        config.replaceable.forEach(block -> carverTargets.add(block.value()));
        return out;
    }

    private JsonObject height(JsonElement value) {
        return GeologyHeightModel.range(value, bounds.getMinGenY(), bounds.getGenDepth(), sea);
    }

    private static double mean(FloatProvider value) {
        return (value.getMinValue() + value.getMaxValue()) * 0.5;
    }

    private static JsonArray interval(FloatProvider value) {
        var result = new JsonArray();
        result.add(value.getMinValue());
        result.add(value.getMaxValue());
        return result;
    }
}
