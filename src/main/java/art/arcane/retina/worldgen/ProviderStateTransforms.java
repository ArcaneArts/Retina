package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import java.util.*;

/** Sparse transforms of live substrate states, closed before palette predicates. */
final class ProviderStateTransforms {
    private ProviderStateTransforms() { }

    static boolean requiredReadsCurrent(JsonObject p) {
        return nullable(p) || optionalReadsCurrent(p);
    }
    static boolean nullable(JsonObject p) {
        return switch(p.get("type").getAsString()) {
            case "rule_based" -> !p.has("fallback") || nullable(p.getAsJsonObject("fallback"));
            case "random_block" -> p.getAsJsonArray("states").isEmpty();
            default -> false;
        };
    }
    static boolean optionalReadsCurrent(JsonObject p) {
        return switch(p.get("type").getAsString()) {
            case "rotated", "randomized_int", "copy_properties" -> requiredReadsCurrent(p.getAsJsonObject("source"));
            case "weighted" -> p.getAsJsonArray("entries").asList().stream().anyMatch(v->requiredReadsCurrent(v.getAsJsonObject().getAsJsonObject("provider")));
            case "rule_based" -> p.getAsJsonArray("rules").asList().stream().anyMatch(v->optionalReadsCurrent(v.getAsJsonObject().getAsJsonObject("provider"))) || p.has("fallback") && optionalReadsCurrent(p.getAsJsonObject("fallback"));
            default -> false;
        };
    }
    private record Pending(JsonObject data,JsonArray table,Set<Integer> sources) { }
    static void prepare(JsonArray recipes,LinkedHashMap<BlockState,Integer> palette) {
        var pending=new ArrayList<Pending>();collect(recipes,pending);
        if(pending.isEmpty())return;
        // Nested operations can introduce further active substrate states. Only
        // registered transformations are added, until that finite domain closes.
        int before;
        do {
            before=palette.size();var states=palette.keySet().toArray(BlockState[]::new);
            for(var item:pending)for(int id=0;id<states.length;id++)if(item.sources.add(id)) {
                var row=transform(item.data,states[id],id,palette);
                if(row!=null)item.table.add(row);
            }
        } while(palette.size()!=before);
    }
    private static void collect(JsonElement value,List<Pending> pending) {
        if(value.isJsonObject()) {
            var object=value.getAsJsonObject();
            if(object.has("current_inputs") && object.get("current_inputs").getAsBoolean()) {
                String key=object.has("kind")?switch(object.get("kind").getAsString()) {
                    case "huge_mushroom" -> "faces";
                    case "fallen_tree" -> "axes";
                    default -> throw new IllegalArgumentException("Unknown current-state geometry "+object.get("kind"));
                }:"variants";
                var table=object.getAsJsonArray(key);var sources=new HashSet<Integer>();
                for(var row:table)sources.add(row.getAsJsonObject().get("source").getAsInt());
                pending.add(new Pending(object,table,sources));
            }
            for(var child:object.entrySet())collect(child.getValue(),pending);
        } else if(value.isJsonArray())for(var child:value.getAsJsonArray())collect(child,pending);
    }
    private static JsonObject transform(JsonObject data,BlockState state,int id,LinkedHashMap<BlockState,Integer> palette) {
        String kind=data.has("kind")?data.get("kind").getAsString():data.get("type").getAsString();
        var states=new JsonArray();var result=new JsonObject();result.addProperty("source",id);
        switch(kind) {
            case "rotated" -> {
                for(var direction:Direction.values()) {
                    var rotated=state.trySetValue(BlockStateProperties.AXIS,direction.getAxis()).trySetValue(BlockStateProperties.FACING,direction);
                    if(direction.getAxis().isHorizontal())rotated=rotated.trySetValue(BlockStateProperties.HORIZONTAL_FACING,direction);
                    states.add(material(palette,rotated));
                }
            }
            case "randomized_int" -> {
                var property=state.getBlock().getStateDefinition().getProperty(data.get("property").getAsString());
                if(!(property instanceof IntegerProperty integer))return null;
                var values=integer.getPossibleValues().stream().sorted().toList();
                result.addProperty("minimum",values.getFirst());
                for(int v:values)states.add(material(palette,state.setValue(integer,v)));
                // Even a one-value property consumes the configured draw.
                result.add("states",states);return result;
            }
            case "fallen_tree" -> {
                for(var axis:List.of(Direction.Axis.X,Direction.Axis.Z))states.add(material(palette,state.trySetValue(RotatedPillarBlock.AXIS,axis)));
            }
            case "huge_mushroom" -> {
                boolean red=data.get("red").getAsBoolean();
                if(!(state.hasProperty(HugeMushroomBlock.WEST) && state.hasProperty(HugeMushroomBlock.EAST) && state.hasProperty(HugeMushroomBlock.NORTH) && state.hasProperty(HugeMushroomBlock.SOUTH) && (!red || state.hasProperty(HugeMushroomBlock.UP))))return null;
                for(int mask=0;mask<32;mask++) {
                    var next=state.setValue(HugeMushroomBlock.WEST,(mask&1)!=0).setValue(HugeMushroomBlock.EAST,(mask&2)!=0)
                            .setValue(HugeMushroomBlock.NORTH,(mask&4)!=0).setValue(HugeMushroomBlock.SOUTH,(mask&8)!=0);
                    if(red)next=next.setValue(HugeMushroomBlock.UP,(mask&16)!=0);
                    states.add(material(palette,next));
                }
            }
            default -> throw new IllegalArgumentException("Unknown current-state transform "+kind);
        }
        if(states.asList().stream().allMatch(v->v.getAsInt()==id))return null;
        result.add("states",states);return result;
    }
    private static int material(LinkedHashMap<BlockState,Integer> palette,BlockState state) {
        return palette.computeIfAbsent(state,ignored->palette.size());
    }
}
