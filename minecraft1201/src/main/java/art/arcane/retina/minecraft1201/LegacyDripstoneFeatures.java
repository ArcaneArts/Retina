package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.FloatProviderType;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.configurations.DripstoneClusterConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.LargeDripstoneConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.PointedDripstoneConfiguration;

import java.util.List;
import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

final class LegacyDripstoneFeatures {
    record Recipe(JsonObject data, int reach) { }

    private final RegistryOps<JsonElement> ops;
    private final LegacyFeaturePredicates predicates;
    private final ToIntFunction<BlockState> material;

    private LegacyDripstoneFeatures(RegistryOps<JsonElement> ops, LegacyFeaturePredicates predicates,
                                    ToIntFunction<BlockState> material) {
        this.ops = ops;
        this.predicates = predicates;
        this.material = material;
    }

    static Recipe compile(ConfiguredFeature<?, ?> configured, RegistryOps<JsonElement> ops, LegacyFeaturePredicates predicates,
                          ToIntFunction<BlockState> material, int height) {
        var exporter = new LegacyDripstoneFeatures(ops, predicates, material);
        if (configured.config() instanceof PointedDripstoneConfiguration config) return exporter.pointed(config);
        if (configured.config() instanceof DripstoneClusterConfiguration config) return exporter.cluster(config);
        if (configured.config() instanceof LargeDripstoneConfiguration config) return exporter.large(config, height);
        throw new IllegalArgumentException("unsupported dripstone configuration");
    }

    private Recipe pointed(PointedDripstoneConfiguration config) {
        var data = new JsonObject();
        data.add("spikes", spikes());
        data.add("open", predicates.material(DripstoneUtils::isEmptyOrWater));
        data.addProperty("taller", config.chanceOfTallerDripstone);
        var spread = new JsonArray();
        spread.add(config.chanceOfDirectionalSpread);
        spread.add(config.chanceOfSpreadRadius2);
        spread.add(config.chanceOfSpreadRadius3);
        data.add("spread", spread);
        return recipe("pointed", data, 3);
    }

    private Recipe cluster(DripstoneClusterConfiguration config) {
        var data = new JsonObject();
        data.add("spikes", spikes());
        var cluster = new JsonObject();
        data.add("cluster", cluster);
        cluster.add("open", predicates.material(DripstoneUtils::isEmptyOrWater));
        cluster.add("closed", predicates.material(DripstoneUtils::isNeitherEmptyNorWater));
        cluster.add("lava", predicates.blocks(array(0, 0, 0), Blocks.LAVA));
        cluster.addProperty("water", material.applyAsInt(Blocks.WATER.defaultBlockState()));
        cluster.add("pool_target", predicates.material(state -> !state.is(Blocks.WATER)
                && !state.is(Blocks.DRIPSTONE_BLOCK) && !state.is(Blocks.POINTED_DRIPSTONE)));
        cluster.add("pool_water_above", predicates.material(array(0, 1, 0), state -> state.getFluidState().is(FluidTags.WATER)));
        cluster.add("pool_edge", predicates.material(state -> state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.getFluidState().is(FluidTags.WATER)));
        cluster.addProperty("search", config.floorToCeilingSearchRange);
        cluster.add("height", integer(config.height));
        cluster.add("radius", integer(config.radius));
        cluster.addProperty("height_difference", config.maxStalagmiteStalactiteHeightDiff);
        cluster.addProperty("height_deviation", config.heightDeviation);
        cluster.add("layer_thickness", integer(config.dripstoneBlockLayerThickness));
        cluster.add("density", floating(config.density));
        cluster.add("wetness", floating(config.wetness));
        cluster.addProperty("edge_chance", config.chanceOfDripstoneColumnAtMaxDistanceFromCenter);
        cluster.addProperty("edge_distance", config.maxDistanceFromEdgeAffectingChanceOfDripstoneColumn);
        cluster.addProperty("height_bias_distance", config.maxDistanceFromCenterAffectingHeightBias);
        return recipe("cluster", data, config.radius.getMaxValue() + 1);
    }

    private Recipe large(LargeDripstoneConfiguration config, int height) {
        var data = new JsonObject();
        var large = new JsonObject();
        data.add("large", large);
        large.addProperty("block", material.applyAsInt(Blocks.DRIPSTONE_BLOCK.defaultBlockState()));
        large.add("open", predicates.material(DripstoneUtils::isEmptyOrWater));
        large.add("boundary", predicates.material(DripstoneUtils::isDripstoneBaseOrLava));
        large.add("replaceable", predicates.material(DripstoneUtils::isEmptyOrWaterOrLava));
        large.add("embedded", predicates.material(state -> !DripstoneUtils.isEmptyOrWaterOrLava(state)));
        large.add("base_stone", predicates.material(state -> state.is(BlockTags.BASE_STONE_OVERWORLD)));
        large.add("lava", predicates.blocks(array(0, 0, 0), Blocks.LAVA));
        large.addProperty("search", config.floorToCeilingSearchRange);
        large.add("radius", integer(config.columnRadius));
        large.add("height_scale", floating(config.heightScale));
        large.addProperty("radius_ratio", config.maxColumnRadiusToCaveHeightRatio);
        large.add("ceiling_bluntness", floating(config.stalactiteBluntness));
        large.add("floor_bluntness", floating(config.stalagmiteBluntness));
        large.add("wind_speed", floating(config.windSpeed));
        large.addProperty("wind_min_radius", config.minRadiusForWind);
        large.addProperty("wind_min_bluntness", config.minBluntnessForWind);
        return recipe("large", data, reach(config, height));
    }

    private JsonObject spikes() {
        var data = new JsonObject();
        data.addProperty("block", material.applyAsInt(Blocks.DRIPSTONE_BLOCK.defaultBlockState()));
        data.add("base", predicates.material(DripstoneUtils::isDripstoneBase));
        data.add("replaceable", predicates.material(state -> state.is(BlockTags.DRIPSTONE_REPLACEABLE)));
        data.add("water", predicates.material(state -> state.getFluidState().is(FluidTags.WATER)));
        var states = new JsonArray();
        for (var direction : List.of(Direction.DOWN, Direction.UP)) {
            var row = new JsonArray();
            for (var thickness : List.of(DripstoneThickness.BASE, DripstoneThickness.MIDDLE, DripstoneThickness.FRUSTUM,
                    DripstoneThickness.TIP, DripstoneThickness.TIP_MERGE)) {
                var state = Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, direction)
                        .setValue(PointedDripstoneBlock.THICKNESS, thickness);
                row.add(array(material.applyAsInt(state.setValue(PointedDripstoneBlock.WATERLOGGED, false)),
                        material.applyAsInt(state.setValue(PointedDripstoneBlock.WATERLOGGED, true))));
            }
            states.add(row);
        }
        data.add("states", states);
        return data;
    }

    private JsonElement integer(IntProvider value) {
        return LegacyFeaturePlacement.integer(decode(IntProvider.CODEC.encodeStart(ops, value)));
    }

    private JsonElement floating(FloatProvider value) {
        // Both provider codecs use the same wrapper for their typed configuration.
        return LegacyFeaturePlacement.integer(decode(FloatProvider.CODEC.encodeStart(ops, value)));
    }

    private static Recipe recipe(String shape, JsonObject data, int reach) {
        data.addProperty("shape", shape);
        var root = new JsonObject();
        root.addProperty("kind", "dripstone");
        root.add("dripstone", data);
        return new Recipe(root, reach);
    }

    private int reach(LargeDripstoneConfiguration config, int height) {
        // A bounded column scan can see at most range - 1 cells in either direction.
        int gap = Math.min(height, 2 * (config.floorToCeilingSearchRange - 1) - 1);
        int radius = Math.max(config.columnRadius.getMinValue(), Math.min(config.columnRadius.getMaxValue(),
                (int) (gap * config.maxColumnRadiusToCaveHeightRatio)));
        var ceiling = bounds(config.stalactiteBluntness);
        var floor = bounds(config.stalagmiteBluntness);
        var wind = bounds(config.windSpeed);
        double speed = Math.max(Math.abs(wind.min()), Math.abs(wind.max()));
        if (speed == 0 || radius < config.minRadiusForWind
                || Math.min(ceiling.max(), floor.max()) < config.minBluntnessForWind) return radius;

        double low = Math.max(config.minBluntnessForWind, Math.min(ceiling.min(), floor.min()));
        double high = Math.max(Math.max(ceiling.max(), floor.max()), radius);
        double scale = bounds(config.heightScale).max();
        double tallest = 0;
        // The shape function decreases up to normalized radius 1, then increases.
        // Its maximum over allowed radii is at an endpoint, including after a shrink.
        for (int r = 2; r <= radius; r++) {
            tallest = Math.max(tallest, Math.max(columnHeight(low, r, scale), columnHeight(high, r, scale)));
        }
        int distance = Math.min(height - 1, Math.max(config.floorToCeilingSearchRange + 7, (int) Math.ceil(tallest)));
        double reach = radius + Math.ceil(distance * speed);
        if (reach > Integer.MAX_VALUE) throw new IllegalArgumentException("dripstone wind footprint exceeds coordinate range");
        return (int) reach;
    }

    private record FloatBounds(float min, float max) { }

    private FloatBounds bounds(FloatProvider provider) {
        float min = provider.getMinValue(), max = provider.getMaxValue();
        if (provider.getType() != FloatProviderType.TRAPEZOID) return new FloatBounds(min, max);
        var value = floating(provider).getAsJsonObject();
        float span = max - min;
        float slope = (span - value.get("plateau").getAsFloat()) / 2;
        float flat = span - slope;
        // A negative plateau can move the sampled bounds beyond the codec's reported bounds.
        return new FloatBounds(min + Math.min(0, flat), min + Math.max(0, flat) + slope);
    }

    private static double columnHeight(double distance, int radius, double scale) {
        if (scale <= 0) return 0;
        double x = distance / radius * 0.384;
        double shape = 0.75 * Math.pow(x, 4.0 / 3.0) - Math.pow(x, 2.0 / 3.0) - Math.log(x) / 3.0;
        return scale * Math.max(shape, 0) / 0.384 * radius;
    }
}
