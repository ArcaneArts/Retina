package art.arcane.retina.worldgen;
import com.google.gson.*;
import java.util.Set;
/** Supported exact End placement operations; unknown providers are reported. */
final class EndPlacementProfile {
    private EndPlacementProfile() { }
    static int reach(JsonArray program) {
        return reach(program,6); // EndIslandFeature radius only shrinks from 4..6.
    }
    static int reach(JsonArray program,int geometryReach) {
        int reach=geometryReach;
        for (var value : program) {
            var op=value.getAsJsonObject();
            if (Set.of("in_square","fixed_placement").contains(type(op))) reach=Math.addExact(reach,15);
            if (type(op).equals("offset")) {
                var x=bounds(op.get("x"));var z=bounds(op.get("z"));
                reach=Math.addExact(reach,Math.max(Math.max(Math.abs(x[0]),Math.abs(x[1])),Math.max(Math.abs(z[0]),Math.abs(z[1]))));
            }
        }
        return reach;
    }
    static boolean supported(JsonArray program) {
        for (var value : program) {
            var op = value.getAsJsonObject(); String type = type(op);
            switch(type) {
                case "heightmap", "rarity_filter", "in_square", "biome", "fixed_placement" -> { }
                case "offset" -> { if (!integer(op.get("x")) || !integer(op.get("y")) || !integer(op.get("z"))) return false; }
                case "count" -> { if (!integer(op.get("count"))) return false; }
                case "height_range" -> {
                    var h = op.get("height");
                    if (h.isJsonObject() && h.getAsJsonObject().has("type") && !Set.of("constant","uniform").contains(type(h.getAsJsonObject()))) return false;
                }
                default -> { return false; }
            }
        }
        return true;
    }
    // Prove that block-entity metadata belongs to this anchor's chunk. Unknown
    // cross-chunk programs remain unsupported until their ownership is adapted.
    static boolean owned(JsonArray program) {
        long[] low={0,0},high={0,0};
        for(var value:program) {
            var op=value.getAsJsonObject();
            switch(type(op)) {
                case "in_square" -> { high[0]+=15;high[1]+=15; }
                case "fixed_placement" -> {
                    if(low[0]<0||low[1]<0||high[0]>15||high[1]>15)return false;
                    low=new long[]{0,0};high=new long[]{15,15};
                }
                case "offset" -> {
                    var x=bounds(op.get("x"));var z=bounds(op.get("z"));
                    low[0]+=x[0];high[0]+=x[1];low[1]+=z[0];high[1]+=z[1];
                }
                default -> { }
            }
        }
        return low[0]>=0&&low[1]>=0&&high[0]<=15&&high[1]<=15;
    }
    private static int[] bounds(JsonElement value) {
        if(value.isJsonPrimitive()) {int n=value.getAsInt();return new int[]{n,n};}
        var op=value.getAsJsonObject();
        return switch(type(op)) {
            case "constant" -> new int[]{op.get("value").getAsInt(),op.get("value").getAsInt()};
            case "uniform" -> new int[]{op.get("min_inclusive").getAsInt(),op.get("max_inclusive").getAsInt()};
            case "weighted_list" -> {
                int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
                for(var item:op.getAsJsonArray("distribution")) {var b=bounds(item.getAsJsonObject().get("data"));low=Math.min(low,b[0]);high=Math.max(high,b[1]);}
                yield new int[]{low,high};
            }
            default -> throw new IllegalArgumentException("Unsupported exact End integer provider: "+value);
        };
    }
    private static boolean integer(JsonElement value) {
        if (value.isJsonPrimitive()) return true;
        var op = value.getAsJsonObject();
        if (Set.of("constant","uniform").contains(type(op))) return true;
        if (type(op).equals("weighted_list")) {
            for (var v : op.getAsJsonArray("distribution")) if (!integer(v.getAsJsonObject().get("data"))) return false;
            return true;
        }
        return false;
    }
    private static String type(JsonObject value) { return value.get("type").getAsString().replace("minecraft:",""); }
}
