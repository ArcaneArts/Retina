package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Registered neighbor-shape predicates and state variants used by ruined portals. */
final class PortalShapeProfile {
    static final Direction[] DIRECTIONS={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
    static void prepare(LinkedHashMap<BlockState,Integer> materials) {
        var blocks=new LinkedHashSet<Block>();materials.keySet().forEach(s->blocks.add(s.getBlock()));
        for(var block:blocks)if(block instanceof SimpleWaterloggedBlock)
            block.getStateDefinition().getPossibleStates().forEach(s->materials.computeIfAbsent(s,k->materials.size()));
    }
    static void finish(JsonObject kit,JsonArray templates,LinkedHashMap<BlockState,Integer> materials) {
        var rows=new JsonArray();var variants=new JsonArray();var families=new HashMap<BlockState,Integer>();
        var states=materials.keySet().toArray(BlockState[]::new);var containers=new HashMap<Block,Boolean>();
        // Only portal template blocks can retain template container NBT. Inspect
        // each such block once, without constructing unrelated dimension entities.
        for(var pair:kit.getAsJsonArray("templates"))for(var id:pair.getAsJsonArray())
            for(var palette:templates.get(id.getAsInt()).getAsJsonObject().getAsJsonArray("palettes"))for(var rotations:palette.getAsJsonArray())for(var material:rotations.getAsJsonArray()) {
                var state=states[material.getAsInt()];
                if(state.getBlock() instanceof EntityBlock block)containers.computeIfAbsent(state.getBlock(),b->block.newBlockEntity(BlockPos.ZERO,state) instanceof RandomizableContainer);
            }
        var wallTests=Shapes.rotateHorizontal(Block.boxZ(2.0,16.0,0.0,9.0));var post=Block.column(2.0,0.0,16.0);
        for(var state:materials.keySet()) {
            var row=new JsonObject();int kind=state.getBlock() instanceof StairBlock?1:state.getBlock() instanceof IronBarsBlock?2:state.getBlock() instanceof WallBlock?3:0;
            row.addProperty("kind",kind);int connection=0;
            if(kind>0) {
                var base=state;
                if(kind==1) {
                    base=state.setValue(StairBlock.SHAPE,StairsShape.STRAIGHT);
                    row.addProperty("facing",Arrays.asList(DIRECTIONS).indexOf(state.getValue(StairBlock.FACING)));row.addProperty("half",state.getValue(StairBlock.HALF).ordinal());
                } else for(int i=0;i<4;i++) {
                    var d=DIRECTIONS[i];boolean on=kind==2?state.getValue(IronBarsBlock.PROPERTY_BY_DIRECTION.get(d)):state.getValue(WallBlock.PROPERTY_BY_DIRECTION.get(d))!=WallSide.NONE;
                    if(on)connection|=1<<i;
                    base=kind==2?base.setValue(IronBarsBlock.PROPERTY_BY_DIRECTION.get(d),false):base.setValue(WallBlock.PROPERTY_BY_DIRECTION.get(d),WallSide.NONE).setValue(WallBlock.UP,false);
                }
                row.addProperty("connections",connection);
                Integer family=families.get(base);
                if(family==null) {
                    family=variants.size();families.put(base,family);var table=new JsonArray();
                    int count=kind==1?5:kind==2?16:162;
                    for(int value=0;value<count;value++) {
                        var output=base;
                        if(kind==1)output=output.setValue(StairBlock.SHAPE,StairsShape.values()[value]);
                        else if(kind==2)for(int i=0;i<4;i++)output=output.setValue(IronBarsBlock.PROPERTY_BY_DIRECTION.get(DIRECTIONS[i]),(value&(1<<i))!=0);
                        else {
                            output=output.setValue(WallBlock.UP,value>=81);int v=value%81;
                            for(var d:DIRECTIONS){output=output.setValue(WallBlock.PROPERTY_BY_DIRECTION.get(d),WallSide.values()[v%3]);v/=3;}
                        }
                        table.add(Objects.requireNonNull(materials.get(output),"Portal shape material "+output));
                    }
                    variants.add(table);
                }
                row.addProperty("family",family);
            }
            int wallConnect=0,barsConnect=0,cover=0;
            var above=state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).getFaceShape(Direction.DOWN);
            for(int i=0;i<4;i++) {
                var opposite=DIRECTIONS[i].getOpposite();boolean solid=state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,opposite);
                if(state.is(BlockTags.WALLS)||!Block.isExceptionForConnection(state)&&solid||state.getBlock() instanceof IronBarsBlock||state.getBlock() instanceof FenceGateBlock&&FenceGateBlock.connectsToDirection(state,opposite))wallConnect|=1<<i;
                if(((IronBarsBlock)Blocks.IRON_BARS).attachsTo(state,solid))barsConnect|=1<<i;
                if(!Shapes.joinIsNotEmpty(wallTests.get(DIRECTIONS[i]),above,BooleanOp.ONLY_FIRST))cover|=1<<i;
            }
            if(!Shapes.joinIsNotEmpty(post,above,BooleanOp.ONLY_FIRST)||state.is(BlockTags.WALL_POST_OVERRIDE))cover|=16;
            if(state.getBlock() instanceof WallBlock&&state.getValue(WallBlock.UP))cover|=32;
            row.addProperty("wall_connect",wallConnect);row.addProperty("bars_connect",barsConnect);row.addProperty("wall_cover",cover);
            boolean wet=state.hasProperty(BlockStateProperties.WATERLOGGED)&&state.getValue(BlockStateProperties.WATERLOGGED);
            row.addProperty("waterlogged",wet);
            row.addProperty("wet",state.getBlock() instanceof SimpleWaterloggedBlock block&&block.canPlaceLiquid(null,EmptyBlockGetter.INSTANCE,BlockPos.ZERO,state,Fluids.WATER)?Objects.requireNonNull(materials.get(state.setValue(BlockStateProperties.WATERLOGGED,true))):materials.get(state));
            row.addProperty("water",state.getFluidState().is(Fluids.WATER));
            row.addProperty("source_fluid",state.getFluidState().isSource());
            row.addProperty("tick_fluid",state.getBlock() instanceof LiquidBlock||wet?net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString():"");
            row.addProperty("tick_block",state.getBlock() instanceof LiquidBlock&&state.getFluidState().is(net.minecraft.tags.FluidTags.BUBBLE_COLUMN_CAN_OCCUPY)&&state.getFluidState().isSource()&&state.getFluidState().isFull()?net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString():"");
            row.addProperty("bubble_support",state.is(BlockTags.ENABLES_BUBBLE_COLUMN_DRAG_DOWN)||state.is(BlockTags.ENABLES_BUBBLE_COLUMN_PUSH_UP));
            row.addProperty("entity",state.getBlock() instanceof EntityBlock);row.addProperty("container",containers.getOrDefault(state.getBlock(),false));rows.add(row);
        }
        var shapes=new JsonObject();shapes.add("states",rows);shapes.add("variants",variants);kit.add("shapes",shapes);
    }
}
