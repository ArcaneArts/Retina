package art.arcane.retina.worldgen;
import com.google.gson.*;
import java.util.Set;
/** Supported exact End placement operations; unknown providers are reported. */
final class EndIslandProfile {
    private EndIslandProfile() { }
    static int reach(JsonArray program) {
        int reach=6; // EndIslandFeature starts at radius4..6, then only shrinks.
        for (var value : program) if (Set.of("in_square","fixed_placement").contains(type(value.getAsJsonObject()))) reach=Math.addExact(reach,15);
        return reach;
    }
    static boolean supported(JsonArray program) {
        for (var value : program) {
            var op = value.getAsJsonObject(); String type = type(op);
            switch(type) {
                case "rarity_filter", "in_square", "biome", "fixed_placement" -> { }
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
