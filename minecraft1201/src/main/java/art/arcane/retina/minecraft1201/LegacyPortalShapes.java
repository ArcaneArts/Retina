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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

/** The target game's connection and fluid tables used during native template placement. */
final class LegacyPortalShapes {
    private static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final VoxelShape[] WALL_TESTS = {
            Block.box(7, 0, 0, 9, 16, 9), Block.box(7, 0, 7, 16, 16, 9),
            Block.box(7, 0, 7, 9, 16, 16), Block.box(0, 0, 7, 9, 16, 9)
    };
    private static final VoxelShape POST_TEST = Block.box(7, 0, 7, 9, 16, 9);

    private LegacyPortalShapes() { }

    static void prepare(LinkedHashMap<BlockState, Integer> materials) {
        var shapes = new HashSet<Block>();
        for (var state : List.copyOf(materials.keySet())) {
            var block = state.getBlock();
            if (kind(state) != 0 && shapes.add(block)) {
                block.getStateDefinition().getPossibleStates().forEach(output -> material(materials, output));
            } else if (block instanceof SimpleWaterloggedBlock && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                material(materials, state.setValue(BlockStateProperties.WATERLOGGED, true));
            }
        }
    }

    static void finish(JsonObject kit, JsonArray templates, LinkedHashMap<BlockState, Integer> materials) {
        var rows = new JsonArray();
        var variants = new JsonArray();
        var families = new HashMap<BlockState, Integer>();
        var states = materials.keySet().toArray(BlockState[]::new);
        var containers = containers(kit, templates, states);
        for (var state : states) {
            var row = new JsonObject();
            int kind = kind(state);
            row.addProperty("kind", kind);
            if (kind != 0) shapeFamily(row, state, kind, families, variants, materials);
            connections(row, state);
            fluids(row, state, materials);
            row.addProperty("entity", state.getBlock() instanceof EntityBlock);
            row.addProperty("container", containers.getOrDefault(state.getBlock(), false));
            rows.add(row);
        }
        var shapes = new JsonObject();
        shapes.add("states", rows);
        shapes.add("variants", variants);
        kit.add("shapes", shapes);
    }

    private static HashMap<Block, Boolean> containers(JsonObject kit, JsonArray templates, BlockState[] states) {
        var result = new HashMap<Block, Boolean>();
        for (var pair : kit.getAsJsonArray("templates")) {
            for (var id : pair.getAsJsonArray()) {
                var template = templates.get(id.getAsInt()).getAsJsonObject();
                for (var palette : template.getAsJsonArray("palettes")) {
                    for (var rotations : palette.getAsJsonArray()) {
                        for (var material : rotations.getAsJsonArray()) {
                            var state = states[material.getAsInt()];
                            if (state.getBlock() instanceof EntityBlock block) {
                                result.computeIfAbsent(state.getBlock(),
                                        ignored -> block.newBlockEntity(BlockPos.ZERO, state) instanceof RandomizableContainerBlockEntity);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static void shapeFamily(JsonObject row, BlockState state, int kind, HashMap<BlockState, Integer> families,
                                    JsonArray variants, LinkedHashMap<BlockState, Integer> materials) {
        int connections = 0;
        var base = state;
        if (kind == 1) {
            base = state.setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
            row.addProperty("facing", HORIZONTAL.indexOf(state.getValue(StairBlock.FACING)));
            row.addProperty("half", state.getValue(StairBlock.HALF).ordinal());
        } else {
            for (int i = 0; i < HORIZONTAL.size(); i++) {
                boolean connected = kind == 2 ? state.getValue(bars(i)) : state.getValue(wall(i)) != WallSide.NONE;
                if (connected) connections |= 1 << i;
                base = kind == 2 ? base.setValue(bars(i), false) : base.setValue(wall(i), WallSide.NONE);
            }
            if (kind == 3) base = base.setValue(WallBlock.UP, false);
        }
        row.addProperty("connections", connections);
        var family = families.get(base);
        if (family == null) {
            family = variants.size();
            families.put(base, family);
            var table = new JsonArray();
            int count = kind == 1 ? 5 : kind == 2 ? 16 : 162;
            for (int value = 0; value < count; value++) {
                var output = variant(base, kind, value);
                table.add(Objects.requireNonNull(materials.get(output), () -> "Missing portal shape material " + output));
            }
            variants.add(table);
        }
        row.addProperty("family", family);
    }

    private static BlockState variant(BlockState base, int kind, int value) {
        if (kind == 1) return base.setValue(StairBlock.SHAPE, StairsShape.values()[value]);
        var output = kind == 3 ? base.setValue(WallBlock.UP, value >= 81) : base;
        int side = value % 81;
        for (int i = 0; i < HORIZONTAL.size(); i++) {
            if (kind == 2) {
                output = output.setValue(bars(i), (value & (1 << i)) != 0);
            } else {
                output = output.setValue(wall(i), WallSide.values()[side % 3]);
                side /= 3;
            }
        }
        return output;
    }

    private static void connections(JsonObject row, BlockState state) {
        int wall = 0;
        int bars = 0;
        int cover = 0;
        var below = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(Direction.DOWN);
        for (int i = 0; i < HORIZONTAL.size(); i++) {
            var opposite = HORIZONTAL.get(i).getOpposite();
            boolean solid = state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, opposite);
            if (state.is(BlockTags.WALLS) || !Block.isExceptionForConnection(state) && solid
                    || state.getBlock() instanceof IronBarsBlock
                    || state.getBlock() instanceof FenceGateBlock && FenceGateBlock.connectsToDirection(state, opposite)) {
                wall |= 1 << i;
            }
            if (((IronBarsBlock) Blocks.IRON_BARS).attachsTo(state, solid)) bars |= 1 << i;
            if (!Shapes.joinIsNotEmpty(WALL_TESTS[i], below, BooleanOp.ONLY_FIRST)) cover |= 1 << i;
        }
        if (!Shapes.joinIsNotEmpty(POST_TEST, below, BooleanOp.ONLY_FIRST) || state.is(BlockTags.WALL_POST_OVERRIDE)) cover |= 16;
        if (state.getBlock() instanceof WallBlock && state.getValue(WallBlock.UP)) cover |= 32;
        row.addProperty("wall_connect", wall);
        row.addProperty("bars_connect", bars);
        row.addProperty("wall_cover", cover);
    }

    private static void fluids(JsonObject row, BlockState state, LinkedHashMap<BlockState, Integer> materials) {
        boolean wet = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
        var fluid = state.getFluidState();
        row.addProperty("waterlogged", wet);
        var waterlogged = state.getBlock() instanceof SimpleWaterloggedBlock block
                && block.canPlaceLiquid(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, Fluids.WATER)
                ? state.setValue(BlockStateProperties.WATERLOGGED, true) : state;
        row.addProperty("wet", materials.get(waterlogged));
        row.addProperty("water", fluid.is(Fluids.WATER));
        row.addProperty("source_fluid", fluid.isSource());
        row.addProperty("tick_fluid", state.getBlock() instanceof LiquidBlock || wet
                ? BuiltInRegistries.FLUID.getKey(fluid.getType()).toString() : "");
        row.addProperty("tick_block", state.is(Blocks.WATER) && fluid.isSource() ? "minecraft:water" : "");
        row.addProperty("bubble_support", state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SOUL_SAND));
    }

    private static int kind(BlockState state) {
        if (state.getBlock() instanceof StairBlock) return 1;
        if (state.getBlock() instanceof IronBarsBlock) return 2;
        return state.getBlock() instanceof WallBlock ? 3 : 0;
    }

    private static BooleanProperty bars(int index) {
        return switch (index) {
            case 0 -> BlockStateProperties.NORTH;
            case 1 -> BlockStateProperties.EAST;
            case 2 -> BlockStateProperties.SOUTH;
            default -> BlockStateProperties.WEST;
        };
    }

    private static EnumProperty<WallSide> wall(int index) {
        return switch (index) {
            case 0 -> WallBlock.NORTH_WALL;
            case 1 -> WallBlock.EAST_WALL;
            case 2 -> WallBlock.SOUTH_WALL;
            default -> WallBlock.WEST_WALL;
        };
    }
}
