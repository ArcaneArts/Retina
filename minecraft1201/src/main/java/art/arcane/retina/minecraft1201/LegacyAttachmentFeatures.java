package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import net.minecraft.world.level.material.Fluids;

import java.util.Comparator;
import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Exports the 1.20.1 face states, attachment predicates and source spread order. */
final class LegacyAttachmentFeatures {
    static final int REACH = 3;

    private LegacyAttachmentFeatures() { }

    static JsonObject vines(LegacyFeaturePredicates predicates, ToIntFunction<BlockState> material) {
        return encode(Blocks.VINE, null, predicates, material);
    }

    static JsonObject multiface(MultifaceGrowthConfiguration growth, LegacyFeaturePredicates predicates,
                                 ToIntFunction<BlockState> material) {
        var block = growth.placeBlock;
        // These two registered blocks use the known native rules. Other blocks can supply
        // a different spreader, which the feature configuration does not describe.
        if (block != Blocks.GLOW_LICHEN && block != Blocks.SCULK_VEIN) {
            throw new IllegalArgumentException("multiface_growth:spreader:" + BuiltInRegistries.BLOCK.getKey(block));
        }
        return encode(block, growth, predicates, material);
    }

    private static JsonObject encode(Block block, MultifaceGrowthConfiguration growth, LegacyFeaturePredicates predicates,
                                     ToIntFunction<BlockState> material) {
        boolean vines = growth == null;
        boolean sculk = block == Blocks.SCULK_VEIN;
        var initial = block.defaultBlockState();
        var wet = initial.hasProperty(BlockStateProperties.WATERLOGGED)
                ? initial.setValue(BlockStateProperties.WATERLOGGED, true) : initial;
        var result = new JsonObject();
        result.addProperty("kind", "attachment_growth");
        result.addProperty("vines", vines);
        result.addProperty("sculk", sculk);
        result.addProperty("initial", material.applyAsInt(initial));
        result.addProperty("wet_initial", material.applyAsInt(wet));

        var supports = new JsonArray();
        var sturdy = new JsonArray();
        int faceMask = 0;
        for (var face : Direction.values()) {
            var property = faceProperty(vines, face);
            if (property == null || !initial.hasProperty(property)) {
                supports.add(LegacyFeaturePredicates.always());
            } else {
                faceMask |= 1 << face.ordinal();
                supports.add(predicates.material(array(face.getStepX(), face.getStepY(), face.getStepZ()),
                        state -> MultifaceBlock.canAttachTo(EmptyBlockGetter.INSTANCE, face, BlockPos.ZERO, state)));
            }
            sturdy.add(sculk ? predicates.material(state -> state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, face))
                    : LegacyFeaturePredicates.always());
        }
        result.addProperty("face_mask", faceMask);
        result.add("support", supports);
        result.add("sturdy", sturdy);
        result.add("states", states(block, vines, material));

        var directions = new JsonArray();
        var spreadOrder = new JsonArray();
        if (vines) {
            for (var face : Direction.values()) if (face != Direction.DOWN) directions.add(face.ordinal());
        } else {
            if (growth.canPlaceOnCeiling) directions.add(Direction.UP.ordinal());
            if (growth.canPlaceOnFloor) directions.add(Direction.DOWN.ordinal());
            if (growth.canPlaceOnWall) Direction.Plane.HORIZONTAL.forEach(face -> directions.add(face.ordinal()));
            for (var spread : MultifaceSpreader.DEFAULT_SPREAD_ORDER) spreadOrder.add(spread.ordinal());
        }
        result.add("directions", directions);
        result.add("spread_order", spreadOrder);
        result.addProperty("search_range", vines ? 0 : growth.searchRange);
        result.addProperty("spread_chance", vines ? 0 : growth.chanceOfSpreading);
        result.add("air", predicates.material(BlockState::isAir));
        result.add("water", vines ? LegacyFeaturePredicates.always() : predicates.material(state -> state.is(Blocks.WATER)));
        result.add("source_water", vines ? LegacyFeaturePredicates.always()
                : predicates.material(state -> state.getFluidState().isSourceOfType(Fluids.WATER)));
        result.add("placed_on", vines ? LegacyFeaturePredicates.always()
                : predicates.material(state -> state.is(growth.canBePlacedOn)));
        result.add("spread_replaceable", sculk ? predicates.material(state -> !state.is(BlockTags.FIRE)
                && (state.getFluidState().isEmpty() || state.getFluidState().is(Fluids.WATER)) && state.canBeReplaced())
                : LegacyFeaturePredicates.always());
        result.add("spread_support", sculk ? predicates.material(state ->
                !state.is(Blocks.SCULK) && !state.is(Blocks.SCULK_CATALYST) && !state.is(Blocks.MOVING_PISTON))
                : LegacyFeaturePredicates.always());
        return result;
    }

    private static JsonArray states(Block block, boolean vines, ToIntFunction<BlockState> material) {
        var result = new JsonArray();
        for (var state : block.getStateDefinition().getPossibleStates()) {
            int faces = 0;
            var added = new JsonArray();
            for (var face : Direction.values()) {
                var property = faceProperty(vines, face);
                if (property != null && state.hasProperty(property)) {
                    if (state.getValue(property)) faces |= 1 << face.ordinal();
                    added.add(material.applyAsInt(state.setValue(property, true)));
                } else {
                    added.add(material.applyAsInt(state));
                }
            }
            var row = new JsonObject();
            row.addProperty("source", material.applyAsInt(state));
            row.addProperty("faces", faces);
            row.add("added", added);
            result.add(row);
        }
        result.asList().sort(Comparator.comparingInt(row -> row.getAsJsonObject().get("source").getAsInt()));
        return result;
    }

    private static BooleanProperty faceProperty(boolean vines, Direction face) {
        if (!vines) return MultifaceBlock.getFaceProperty(face);
        return face == Direction.DOWN ? null : VineBlock.getPropertyForFace(face);
    }
}
