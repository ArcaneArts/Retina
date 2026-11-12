package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;

/** Assigns stable one-based references to the scheduled updates of a saved profile. */
final class LegacyFeatureTicks {
    private record Tick(String kind, String id) { }

    private final LinkedHashMap<Tick, Integer> definitions = new LinkedHashMap<>();

    int block(String id) {
        return add("block", id);
    }

    int fluid(String id) {
        return add("fluid", id);
    }

    private int add(String kind, String id) {
        return definitions.computeIfAbsent(new Tick(kind, id), key -> definitions.size() + 1);
    }

    JsonArray export() {
        var result = new JsonArray();
        for (var definition : definitions.keySet()) {
            var tick = new JsonObject();
            tick.addProperty("id", definition.id());
            tick.addProperty("kind", definition.kind());
            result.add(tick);
        }
        return result;
    }
}
