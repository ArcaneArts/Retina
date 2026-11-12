package art.arcane.retina.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Resolves registered height providers to the native geology range. */
public final class GeologyHeightModel {
    private GeologyHeightModel() { }

    private static int anchor(JsonElement value, int min, int height, int sea) {
        var a = value.getAsJsonObject();
        if (a.has("absolute")) return a.get("absolute").getAsInt();
        if (a.has("above_bottom")) return min + a.get("above_bottom").getAsInt();
        if (a.has("below_top")) return min + height - 1 - a.get("below_top").getAsInt();
        if (a.has("relative_to_sea_level")) return sea + a.get("relative_to_sea_level").getAsInt();
        throw new IllegalArgumentException("Unsupported geology anchor: " + a);
    }
    public static JsonObject range(JsonElement value, int min, int height, int sea) {
        var data = value.getAsJsonObject(); var result = new JsonObject(); String kind = data.has("type") ? data.get("type").getAsString().replace("minecraft:", "") : "constant";
        if (kind.equals("constant")) {
            int y = anchor(data.has("value") ? data.get("value") : data, min, height, sea);
            result.addProperty("min", y); result.addProperty("max", y);
        } else if (kind.equals("uniform") || kind.equals("trapezoid")) {
            result.addProperty("min", anchor(data.get("min_inclusive"), min, height, sea));
            result.addProperty("max", anchor(data.get("max_inclusive"), min, height, sea));
        } else throw new IllegalArgumentException("Unsupported geology height distribution: " + data);
        // UniformHeight/TrapezoidHeight return the resolved minimum for an empty range.
        // This occurs naturally when a source's above-bottom/below-top anchors meet a short dimension.
        if (result.get("min").getAsInt() > result.get("max").getAsInt()) result.add("max", result.get("min"));
        result.addProperty("triangle", kind.equals("trapezoid"));
        result.addProperty("plateau", data.has("plateau") ? data.get("plateau").getAsInt() : 0);
        return result;
    }
}
