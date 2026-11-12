package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.Set;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;

/** Converts the 1.20.1 placement codec shapes to Retina's shared program. */
final class LegacyFeaturePlacement {
    private static final Set<String> NETHER_PLACEMENTS = Set.of(
            "count", "count_on_every_layer", "rarity_filter", "in_square", "heightmap", "height_range", "offset", "biome");

    private LegacyFeaturePlacement() { }

    static JsonArray encode(PlacedFeature feature, RegistryOps<JsonElement> ops) {
        var result = new JsonArray();
        for (var modifier : feature.placement()) {
            var op = decode(PlacementModifier.CODEC.encodeStart(ops, modifier)).getAsJsonObject();
            String kind = type(op);
            op.addProperty("type", kind);
            switch (kind) {
                case "count", "count_on_every_layer" -> op.add("count", integer(op.get("count")));
                case "heightmap" -> op.addProperty("map", Heightmap.Types.valueOf(op.remove("heightmap").getAsString()).ordinal());
                case "random_offset" -> {
                    var horizontal = integer(op.remove("xz_spread"));
                    op.addProperty("type", "offset");
                    op.add("x", horizontal);
                    op.add("y", integer(op.remove("y_spread")));
                    op.add("z", horizontal.deepCopy());
                }
                default -> { }
            }
            result.add(op);
        }
        return result;
    }

    static boolean bindNether(JsonArray program, WorldGenerationContext context) {
        for (var value : program) {
            var op = value.getAsJsonObject();
            String kind = type(op);
            if (!NETHER_PLACEMENTS.contains(kind)) return false;
            if (kind.equals("height_range")) bindHeight(op.get("height"), context);
        }
        return true;
    }

    static JsonElement integer(JsonElement value) {
        if (!value.isJsonObject()) return value;
        var provider = value.getAsJsonObject().deepCopy();
        provider.addProperty("type", type(provider));
        if (provider.get("value") instanceof JsonObject limits) {
            provider.remove("value");
            limits.entrySet().forEach(entry -> provider.add(entry.getKey(), entry.getValue()));
        }
        if (provider.has("source")) provider.add("source", integer(provider.get("source")));
        if (provider.get("distribution") instanceof JsonArray distribution) {
            for (var raw : distribution) {
                var entry = raw.getAsJsonObject();
                entry.add("data", integer(entry.get("data")));
            }
        }
        return provider;
    }

    private static void bindHeight(JsonElement value, WorldGenerationContext context) {
        if (value instanceof JsonArray array) {
            array.forEach(item -> bindHeight(item, context));
        } else if (value instanceof JsonObject object) {
            if (object.has("above_bottom")) {
                object.addProperty("absolute", context.getMinGenY() + object.remove("above_bottom").getAsInt());
            } else if (object.has("below_top")) {
                object.addProperty("absolute", context.getMinGenY() + context.getGenDepth() - 1 - object.remove("below_top").getAsInt());
            } else {
                if (object.has("type")) object.addProperty("type", type(object));
                object.entrySet().forEach(entry -> bindHeight(entry.getValue(), context));
            }
        }
    }
}
