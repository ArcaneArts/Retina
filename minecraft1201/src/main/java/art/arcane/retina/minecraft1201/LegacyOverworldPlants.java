package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyFeaturePredicates.all;
import static art.arcane.retina.minecraft1201.LegacyFeaturePredicates.always;
import static art.arcane.retina.minecraft1201.LegacyFeaturePredicates.any;
import static art.arcane.retina.minecraft1201.LegacyFeaturePredicates.not;

/** Survival rules used by the legacy registry's surface and cave plants. */
final class LegacyOverworldPlants {
    private final LegacyFeaturePredicates predicates;
    private final ToIntFunction<BlockState> material;
    private final boolean skylight;

    LegacyOverworldPlants(LegacyFeaturePredicates predicates, ToIntFunction<BlockState> material, boolean skylight) {
        this.predicates = predicates;
        this.material = material;
        this.skylight = skylight;
    }

    JsonObject simple(LegacyBlockProviders.Program provider) {
        var result = new JsonObject();
        result.addProperty("kind", "simple_block");
        result.add("provider", provider.data());
        var states = new JsonArray();
        for (var output : provider.states()) {
            var row = new JsonObject();
            int id = material.applyAsInt(output);
            row.addProperty("source", id);
            var lower = new JsonArray();
            var upper = new JsonArray();
            boolean paired = output.getBlock() instanceof DoublePlantBlock;
            for (boolean wet : new boolean[]{false, true}) {
                var state = output;
                if (paired && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    state = state.setValue(BlockStateProperties.WATERLOGGED, wet);
                }
                lower.add(paired ? material.applyAsInt(state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER)) : id);
                upper.add(paired ? material.applyAsInt(state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)) : 0);
            }
            row.add("lower", lower);
            row.add("upper", upper);
            row.add("survival", survival(output, array(0, 0, 0)));
            row.add("upper_allowed", paired ? predicates.material(BlockState::isAir) : always());
            states.add(row);
        }
        result.add("states", states);
        result.add("water", predicates.material(state -> state.getFluidState().is(FluidTags.WATER)));
        return result;
    }

    JsonObject survival(BlockState output, JsonArray origin) {
        var block = output.getBlock();
        var below = offset(origin, 0, -1, 0);
        if (output.is(Blocks.CACTUS)) return cactus(origin);
        if (output.is(Blocks.SUGAR_CANE)) return sugarCane(origin);
        if (output.is(Blocks.MANGROVE_PROPAGULE)) {
            if (output.getValue(BlockStateProperties.HANGING)) {
                return predicates.material(offset(origin, 0, 1, 0), state -> state.is(Blocks.MANGROVE_LEAVES));
            }
            return predicates.material(below, state -> soil(state) || state.is(Blocks.CLAY));
        }
        if (output.is(Blocks.BAMBOO)) return predicates.material(below, state -> state.is(BlockTags.BAMBOO_PLANTABLE_ON));
        if (output.is(Blocks.DEAD_BUSH)) return predicates.material(below, state -> state.is(BlockTags.DEAD_BUSH_MAY_PLACE_ON));
        if (output.is(Blocks.BROWN_MUSHROOM) || output.is(Blocks.RED_MUSHROOM)) {
            return predicates.material(below, state -> state.is(BlockTags.MUSHROOM_GROW_BLOCK)
                    || !skylight && state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        }
        if (output.is(Blocks.LILY_PAD)) {
            return all(predicates.material(below, state -> state.getFluidState().is(Fluids.WATER) || state.getBlock() instanceof IceBlock),
                    predicates.material(origin, state -> state.getFluidState().isEmpty()));
        }
        if (output.is(Blocks.SPORE_BLOSSOM)) {
            return all(predicates.material(offset(origin, 0, 1, 0), state ->
                            state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.DOWN, SupportType.CENTER)),
                    predicates.material(origin, state -> !state.getFluidState().is(FluidTags.WATER)));
        }
        if (output.is(Blocks.SEAGRASS)) {
            return predicates.material(below, state -> !state.is(Blocks.MAGMA_BLOCK) && sturdyUp(state));
        }
        if (output.is(Blocks.SMALL_DRIPLEAF)) {
            return any(predicates.material(below, state -> state.is(BlockTags.SMALL_DRIPLEAF_PLACEABLE)),
                    all(predicates.material(origin, state -> state.getFluidState().is(Fluids.WATER)), predicates.material(below, LegacyOverworldPlants::soil)));
        }
        if (output.is(Blocks.WITHER_ROSE)) {
            return predicates.material(below, state -> soil(state) || state.is(Blocks.NETHERRACK)
                    || state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL));
        }
        if (output.is(Blocks.PINK_PETALS) || block.getClass() == FlowerBlock.class || block.getClass() == TallGrassBlock.class
                || block.getClass() == SweetBerryBushBlock.class || block.getClass() == SaplingBlock.class
                || block.getClass() == DoublePlantBlock.class || block.getClass() == TallFlowerBlock.class) {
            if (output.hasProperty(DoublePlantBlock.HALF) && output.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
                return predicates.material(below, state -> state.is(block) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER);
            }
            return predicates.material(below, LegacyOverworldPlants::soil);
        }
        if (block.getClass() == Block.class || output.is(Blocks.MELON) || output.is(Blocks.PUMPKIN)
                || output.is(Blocks.MOSS_CARPET) || output.is(Blocks.AZALEA) || output.is(Blocks.FLOWERING_AZALEA)) {
            if (output.is(Blocks.MOSS_CARPET)) return predicates.material(below, state -> !state.isAir());
            if (output.is(Blocks.AZALEA) || output.is(Blocks.FLOWERING_AZALEA)) {
                return predicates.material(below, state -> soil(state) || state.is(Blocks.CLAY));
            }
            return always();
        }
        throw new IllegalArgumentException("plant-survival:" + output);
    }

    static boolean soil(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND);
    }

    private JsonObject cactus(JsonArray origin) {
        var tests = new ArrayList<JsonObject>();
        for (var direction : Direction.Plane.HORIZONTAL) {
            tests.add(predicates.material(offset(origin, direction.getStepX(), 0, direction.getStepZ()),
                    state -> !state.isSolid() && !state.getFluidState().is(FluidTags.LAVA)));
        }
        tests.add(predicates.material(offset(origin, 0, 1, 0), state -> !state.liquid()));
        tests.add(predicates.material(offset(origin, 0, -1, 0),
                state -> state.is(Blocks.CACTUS) || state.is(BlockTags.SAND)));
        return all(tests.toArray(JsonObject[]::new));
    }

    private JsonObject sugarCane(JsonArray origin) {
        var wet = new ArrayList<JsonObject>();
        for (var direction : Direction.Plane.HORIZONTAL) {
            wet.add(predicates.material(offset(origin, direction.getStepX(), -1, direction.getStepZ()),
                    state -> state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.FROSTED_ICE)));
        }
        var below = offset(origin, 0, -1, 0);
        return any(predicates.blocks(below, Blocks.SUGAR_CANE),
                all(predicates.material(below, state -> state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)),
                        any(wet.toArray(JsonObject[]::new))));
    }

    private static boolean sturdyUp(BlockState state) {
        return state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
    }

    private static JsonArray offset(JsonArray origin, int x, int y, int z) {
        return array(origin.get(0).getAsInt() + x, origin.get(1).getAsInt() + y, origin.get(2).getAsInt() + z);
    }
}
