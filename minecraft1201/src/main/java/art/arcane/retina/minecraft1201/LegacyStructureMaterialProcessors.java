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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlackstoneReplaceProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

/** Material tables for processors whose output depends on target block properties. */
final class LegacyStructureMaterialProcessors {
    static final String AGE = "minecraft:block_age";
    static final String BLACKSTONE = "minecraft:blackstone_replace";
    static final String LAVA = "minecraft:lava_submerged_block";

    private LegacyStructureMaterialProcessors() { }

    static void prepare(Set<String> processors, LinkedHashMap<BlockState, Integer> materials) {
        if (processors.contains(LAVA)) material(materials, Blocks.LAVA.defaultBlockState());
        if (processors.contains(AGE)) {
            for (var block : List.of(Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_SLAB,
                    Blocks.STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.CRYING_OBSIDIAN)) {
                material(materials, block.defaultBlockState());
            }
            for (var block : List.of(Blocks.STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_STAIRS,
                    Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_WALL)) {
                block.getStateDefinition().getPossibleStates().forEach(state -> material(materials, state));
            }
        }
        if (processors.contains(BLACKSTONE)) {
            var settings = new StructurePlaceSettings();
            for (var block : BuiltInRegistries.BLOCK) {
                if (blackstone(block.defaultBlockState(), settings).getBlock() != block) {
                    block.getStateDefinition().getPossibleStates().forEach(state -> material(materials, blackstone(state, settings)));
                }
            }
        }
    }

    static JsonObject age(LinkedHashMap<BlockState, Integer> materials) {
        var table = new JsonObject();
        var kinds = new JsonArray();
        var mossy = new JsonArray();
        for (var state : materials.keySet()) {
            int kind = ageKind(state);
            kinds.add(kind);
            var aged = switch (kind) {
                case 2 -> Blocks.MOSSY_STONE_BRICK_STAIRS.withPropertiesOf(state);
                case 3 -> Blocks.MOSSY_STONE_BRICK_SLAB.withPropertiesOf(state);
                case 4 -> Blocks.MOSSY_STONE_BRICK_WALL.withPropertiesOf(state);
                default -> state;
            };
            mossy.add(materials.get(aged));
        }
        table.add("kinds", kinds);
        table.add("mossy", mossy);
        table.add("normal_stairs", stairs(Blocks.STONE_BRICK_STAIRS, materials));
        table.add("mossy_stairs", stairs(Blocks.MOSSY_STONE_BRICK_STAIRS, materials));
        table.addProperty("cracked", materials.get(Blocks.CRACKED_STONE_BRICKS.defaultBlockState()));
        table.addProperty("mossy_bricks", materials.get(Blocks.MOSSY_STONE_BRICKS.defaultBlockState()));
        table.addProperty("mossy_slab", materials.get(Blocks.MOSSY_STONE_BRICK_SLAB.defaultBlockState()));
        table.add("slabs", array(materials.get(Blocks.STONE_SLAB.defaultBlockState()), materials.get(Blocks.STONE_BRICK_SLAB.defaultBlockState())));
        table.addProperty("crying", materials.get(Blocks.CRYING_OBSIDIAN.defaultBlockState()));
        return table;
    }

    static JsonArray blackstone(LinkedHashMap<BlockState, Integer> materials) {
        var table = new JsonArray();
        var settings = new StructurePlaceSettings();
        materials.keySet().forEach(state -> table.add(materials.get(blackstone(state, settings))));
        return table;
    }

    static JsonArray lavaShape(LinkedHashMap<BlockState, Integer> materials) {
        var table = new JsonArray();
        materials.keySet().forEach(state ->
                table.add(Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))));
        return table;
    }

    private static JsonArray stairs(Block block, LinkedHashMap<BlockState, Integer> materials) {
        var table = new JsonArray();
        for (var direction : Direction.Plane.HORIZONTAL) {
            for (var half : Half.values()) {
                var state = block.defaultBlockState().setValue(StairBlock.FACING, direction).setValue(StairBlock.HALF, half);
                var row = new JsonArray();
                for (var rotation : Rotation.values()) row.add(materials.get(state.rotate(rotation)));
                table.add(row);
            }
        }
        return table;
    }

    private static int ageKind(BlockState state) {
        if (state.is(Blocks.STONE_BRICKS) || state.is(Blocks.STONE) || state.is(Blocks.CHISELED_STONE_BRICKS)) return 1;
        if (state.is(BlockTags.STAIRS)) return 2;
        if (state.is(BlockTags.SLABS)) return 3;
        if (state.is(BlockTags.WALLS)) return 4;
        return state.is(Blocks.OBSIDIAN) ? 5 : 0;
    }

    private static BlockState blackstone(BlockState state, StructurePlaceSettings settings) {
        var info = new StructureTemplate.StructureBlockInfo(BlockPos.ZERO, state, null);
        return BlackstoneReplaceProcessor.INSTANCE.processBlock(null, BlockPos.ZERO, BlockPos.ZERO, info, info, settings).state();
    }
}
