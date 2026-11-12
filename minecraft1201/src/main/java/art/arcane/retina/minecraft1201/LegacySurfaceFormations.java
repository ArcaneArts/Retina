package art.arcane.retina.minecraft1201;

import com.google.gson.JsonObject;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration;

import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Binds surface formation materials and replace rules to the loaded world palette. */
final class LegacySurfaceFormations {
    record Recipe(JsonObject data, int reach) { }

    private LegacySurfaceFormations() { }

    static Recipe compile(ConfiguredFeature<?, ?> configured, LegacyFeaturePredicates predicates,
                          ToIntFunction<BlockState> material) {
        var data = new JsonObject();
        var shape = new JsonObject();
        data.addProperty("kind", "surface_formation");
        data.add("formation", shape);
        var here = array(0, 0, 0);
        var feature = configured.feature();
        int reach;
        if (feature == Feature.FOREST_ROCK) {
            shape.addProperty("shape", "block_blob");
            shape.addProperty("block", material.applyAsInt(((BlockStateConfiguration) configured.config()).state));
            shape.add("ground", predicates.material(state -> !state.isAir()
                    && (state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD))));
            reach = 3;
        } else if (feature == Feature.BLUE_ICE) {
            shape.addProperty("shape", "blue_ice");
            shape.addProperty("block", material.applyAsInt(Blocks.BLUE_ICE.defaultBlockState()));
            shape.add("water", predicates.blocks(here, Blocks.WATER));
            shape.add("packed_ice", predicates.blocks(here, Blocks.PACKED_ICE));
            shape.add("replaceable", predicates.material(state -> state.isAir() || state.is(Blocks.WATER)
                    || state.is(Blocks.PACKED_ICE) || state.is(Blocks.ICE)));
            reach = 3;
        } else if (feature == Feature.ICE_SPIKE) {
            shape.addProperty("shape", "ice_spike");
            shape.addProperty("block", material.applyAsInt(Blocks.PACKED_ICE.defaultBlockState()));
            shape.add("air", predicates.material(BlockState::isAir));
            shape.add("snow", predicates.blocks(here, Blocks.SNOW_BLOCK));
            shape.add("replaceable", predicates.material(state -> state.isAir() || state.is(BlockTags.DIRT)
                    || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.ICE)));
            reach = 3;
        } else if (feature == Feature.ICEBERG) {
            shape.addProperty("shape", "iceberg");
            var iceberg = new JsonObject();
            shape.add("iceberg", iceberg);
            iceberg.addProperty("block", material.applyAsInt(((BlockStateConfiguration) configured.config()).state));
            iceberg.addProperty("snow_block", material.applyAsInt(Blocks.SNOW_BLOCK.defaultBlockState()));
            iceberg.addProperty("water_block", material.applyAsInt(Blocks.WATER.defaultBlockState()));
            iceberg.addProperty("air_block", material.applyAsInt(Blocks.AIR.defaultBlockState()));
            iceberg.add("air", predicates.material(BlockState::isAir));
            iceberg.add("water", predicates.blocks(here, Blocks.WATER));
            iceberg.add("ice", predicates.blocks(here, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK));
            iceberg.add("snow_layer", predicates.blocks(here, Blocks.SNOW));
            iceberg.add("replaceable", predicates.material(state -> state.isAir() || state.is(Blocks.SNOW_BLOCK)
                    || state.is(Blocks.ICE) || state.is(Blocks.WATER)));
            reach = 12;
        } else {
            throw new IllegalArgumentException("not a surface formation: " + feature);
        }
        return new Recipe(data, reach);
    }
}
