package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.*;

/** Material closure and registry-derived tables for native template processors. */
final class StructureProcessorProfile {
    static void finish(JsonElement root, LinkedHashMap<BlockState,Integer> materials) {
        var processors=new ArrayList<JsonObject>();collect(root,processors);
        boolean age=processors.stream().anyMatch(p->type(p).equals("block_age"));
        boolean blackstone=processors.stream().anyMatch(p->type(p).equals("blackstone_replace"));
        boolean lava=processors.stream().anyMatch(p->type(p).equals("lava_submerged_block"));
        if(!age&&!blackstone&&!lava)return;
        java.util.function.Consumer<BlockState> add=state->materials.computeIfAbsent(state,s->materials.size());
        if(lava)add.accept(Blocks.LAVA.defaultBlockState());
        if(age) {
            for(var block:List.of(Blocks.CRACKED_STONE_BRICKS,Blocks.MOSSY_STONE_BRICKS,Blocks.STONE_SLAB,Blocks.STONE_BRICK_SLAB,Blocks.MOSSY_STONE_BRICK_SLAB,Blocks.CRYING_OBSIDIAN))add.accept(block.defaultBlockState());
            for(var block:List.of(Blocks.STONE_BRICK_STAIRS,Blocks.MOSSY_STONE_BRICK_STAIRS))
                for(var direction:Direction.Plane.HORIZONTAL)for(var half:Half.values())
                    add.accept(block.defaultBlockState().setValue(StairBlock.FACING,direction).setValue(StairBlock.HALF,half));
        }
        // Include every possible output property state now, so decoration can append
        // inputs later without extending the palette after flags/lighting are built.
        if(age)for(var block:List.of(Blocks.MOSSY_STONE_BRICK_STAIRS,Blocks.MOSSY_STONE_BRICK_SLAB,Blocks.MOSSY_STONE_BRICK_WALL))
            block.getStateDefinition().getPossibleStates().forEach(add);
        if(blackstone)for(var block:BuiltInRegistries.BLOCK) {
            if(blackstone(block.defaultBlockState()).getBlock()!=block)
                block.getStateDefinition().getPossibleStates().forEach(state->add.accept(blackstone(state)));
        }
        for(var p:processors) switch(type(p)) {
            case "block_age" -> {
                var table=new JsonObject();var kinds=new JsonArray();var mossy=new JsonArray();
                for(var state:materials.keySet()){kinds.add(kind(state));mossy.add(materials.get(mossy(state)));}
                table.add("kinds",kinds);table.add("mossy",mossy);
                table.add("normal_stairs",stairs(Blocks.STONE_BRICK_STAIRS,materials));
                table.add("mossy_stairs",stairs(Blocks.MOSSY_STONE_BRICK_STAIRS,materials));
                table.addProperty("cracked",materials.get(Blocks.CRACKED_STONE_BRICKS.defaultBlockState()));
                table.addProperty("mossy_bricks",materials.get(Blocks.MOSSY_STONE_BRICKS.defaultBlockState()));
                table.addProperty("mossy_slab",materials.get(Blocks.MOSSY_STONE_BRICK_SLAB.defaultBlockState()));
                table.add("slabs",StructureProfile.ints(materials.get(Blocks.STONE_SLAB.defaultBlockState()),materials.get(Blocks.STONE_BRICK_SLAB.defaultBlockState())));
                table.addProperty("crying",materials.get(Blocks.CRYING_OBSIDIAN.defaultBlockState()));p.add("retina_age",table);
            }
            case "blackstone_replace" -> {
                var map=new JsonArray();materials.keySet().forEach(state->map.add(materials.get(blackstone(state))));p.add("retina_blackstone",map);
            }
            case "lava_submerged_block" -> {
                var full=new JsonArray();materials.keySet().forEach(state->full.add(Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO))));
                p.add("retina_full_shape",full);p.addProperty("retina_lava",materials.get(Blocks.LAVA.defaultBlockState()));
            }
            default -> {}
        }
    }
    private static JsonArray stairs(Block block,LinkedHashMap<BlockState,Integer> materials) {
        var table=new JsonArray();
        // Direction.Plane.HORIZONTAL and Half.values are the source's draw order.
        for(var direction:Direction.Plane.HORIZONTAL)for(var half:Half.values()) {
            var state=block.defaultBlockState().setValue(StairBlock.FACING,direction).setValue(StairBlock.HALF,half);
            var rotations=new JsonArray();for(var r:Rotation.values())rotations.add(materials.get(state.rotate(r)));table.add(rotations);
        }
        return table;
    }
    static int kind(BlockState state) {
        if(state.is(Blocks.STONE_BRICKS)||state.is(Blocks.STONE)||state.is(Blocks.CHISELED_STONE_BRICKS))return 1;
        if(state.is(BlockTags.STAIRS))return 2;
        if(state.is(BlockTags.SLABS))return 3;
        if(state.is(BlockTags.WALLS))return 4;
        if(state.is(Blocks.OBSIDIAN))return 5;
        return 0;
    }
    private static BlockState mossy(BlockState state) {
        return switch(kind(state)) {
            case 2 -> Blocks.MOSSY_STONE_BRICK_STAIRS.withPropertiesOf(state);
            case 3 -> Blocks.MOSSY_STONE_BRICK_SLAB.withPropertiesOf(state);
            case 4 -> Blocks.MOSSY_STONE_BRICK_WALL.withPropertiesOf(state);
            default -> state;
        };
    }
    private static BlockState blackstone(BlockState state) {
        return BlackstoneReplaceProcessor.INSTANCE.processBlock(null,BlockPos.ZERO,BlockPos.ZERO,BlockPos.ZERO,
                new StructureTemplate.StructureBlockInfo(BlockPos.ZERO,state,null),new StructurePlaceSettings()).state();
    }
    private static String type(JsonObject object){return object.get("processor_type").getAsString().replace("minecraft:","");}
    private static void collect(JsonElement value,List<JsonObject> out) {
        if(value.isJsonArray())value.getAsJsonArray().forEach(v->collect(v,out));
        else if(value.isJsonObject()) {
            var object=value.getAsJsonObject();if(object.has("processor_type"))out.add(object);
            object.entrySet().stream().filter(e->!e.getKey().startsWith("retina_")).forEach(e->collect(e.getValue(),out));
        }
    }
}
