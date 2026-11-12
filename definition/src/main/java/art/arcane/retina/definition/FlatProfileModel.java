package art.arcane.retina.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Converts resolved flat layers and biome metadata into the shared engine model. */
public final class FlatProfileModel {
    private FlatProfileModel() { }

    public static JsonArray writeLayers(JsonObject world, JsonElement settings, JsonArray base, JsonArray deferred,
                                        String biomeId, float temperature) {
        var flat = new JsonObject();
        flat.add("settings", settings);
        flat.add("base_layers", base);
        flat.add("deferred_layers", deferred);
        world.add("flat", flat);
        var entries = new JsonArray();
        var entry = new JsonObject();
        entry.addProperty("id", biomeId);
        entry.addProperty("temperature", temperature);
        entry.add("climate", array(0, 0, 0, 0));
        entry.add("terrain", array(0, 0, 0));
        int top = 0;
        for (var layer : base) {
            int id = layer.getAsInt();
            if (id != 0) top = id;
        }
        entry.addProperty("top", top);
        entry.addProperty("filler", top);
        entry.addProperty("underwater", top);
        entry.addProperty("flags", 0);
        entries.add(entry);
        world.add("biomes", entries);
        world.add("climate_targets", new JsonArray());
        var noises = new JsonArray();
        // The shared GPU buffer reserves four noise slots. Flat terrain does not sample them.
        for (int i = 0; i < 4; i++) {
            var noise = new JsonObject();
            noise.addProperty("frequency", 1);
            noise.addProperty("amplitude", 1);
            noise.add("modifiers", array(1));
            noises.add(noise);
        }
        world.add("noises", noises);
        return entries;
    }

    public static JsonArray array(int... values) {
        var result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }
}
