package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static art.arcane.retina.definition.FlatProfileModel.array;
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
                case "heightmap", "surface_relative_threshold_filter" ->
                        op.addProperty("map", Heightmap.Types.valueOf(op.remove("heightmap").getAsString()).ordinal());
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

    static void bindOverworld(JsonArray program, WorldGenerationContext context, LegacyFeaturePredicates predicates,
                              boolean nested) {
        for (var value : program) {
            var op = value.getAsJsonObject();
            String kind = type(op);
            switch (kind) {
                case "count", "count_on_every_layer", "rarity_filter", "in_square", "heightmap", "offset",
                        "surface_relative_threshold_filter", "surface_water_depth_filter" -> { }
                case "biome", "noise_based_count", "noise_threshold_count" -> {
                    if (nested) throw new IllegalArgumentException("nested-placement:" + kind);
                }
                case "height_range" -> bindHeight(op.get("height"), context);
                case "block_predicate_filter" -> op.add("predicate", predicates.compile(op.getAsJsonObject("predicate")));
                case "environment_scan" -> {
                    String direction = op.remove("direction_of_search").getAsString();
                    op.addProperty("direction", switch (direction) {
                        case "up" -> 1;
                        case "down" -> -1;
                        default -> throw new IllegalArgumentException("environment-scan:" + direction);
                    });
                    op.add("target_condition", predicates.compile(op.getAsJsonObject("target_condition")));
                    if (op.has("allowed_search_condition")) {
                        op.add("allowed_search_condition", predicates.compile(op.getAsJsonObject("allowed_search_condition")));
                    }
                }
                default -> throw new IllegalArgumentException("placement:" + kind);
            }
        }
    }

    static int nestedHorizontalReach(JsonArray program) {
        long reach = 0;
        for (var value : program) {
            var op = value.getAsJsonObject();
            reach += switch (type(op)) {
                case "in_square", "count_on_every_layer" -> 15;
                case "offset" -> Math.max(extent(op.get("x")), extent(op.get("z")));
                default -> 0;
            };
        }
        if (reach > 15) throw new IllegalArgumentException("nested-placement:horizontal-reach");
        return (int) reach;
    }

    private static long extent(JsonElement provider) {
        if (provider.isJsonPrimitive()) return Math.abs((long) provider.getAsInt());
        var data = provider.getAsJsonObject();
        return switch (type(data)) {
            case "constant" -> extent(data.get("value"));
            case "uniform", "biased_to_bottom", "clamped", "clamped_normal" ->
                    Math.max(extent(data.get("min_inclusive")), extent(data.get("max_inclusive")));
            case "weighted_list" -> data.getAsJsonArray("distribution").asList().stream()
                    .mapToLong(entry -> extent(entry.getAsJsonObject().get("data"))).max().orElseThrow();
            default -> throw new IllegalArgumentException("offset-provider:" + type(data));
        };
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

    static long salt(String source, int step) {
        long salt = 0xcbf29ce484222325L;
        for (byte value : ("step/" + step + "/" + source).getBytes(StandardCharsets.UTF_8)) {
            salt = (salt ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        }
        return salt;
    }

    static JsonObject recipe(String source, int step, int order, JsonArray placement, JsonObject feature, int reach) {
        long salt = salt(source, step);
        var recipe = new JsonObject();
        recipe.add("kind", feature.get("kind"));
        recipe.addProperty("source", source);
        recipe.addProperty("root_order", order);
        recipe.addProperty("generation_step", step);
        recipe.addProperty("reach", reach);
        recipe.addProperty("salt", salt);
        recipe.addProperty("placement_salt", salt);
        recipe.addProperty("density", 1);
        recipe.addProperty("low_density", 1);
        recipe.addProperty("noise_count", false);
        recipe.addProperty("rarity", 1);
        recipe.addProperty("tries", 1);
        recipe.add("spread", array(0, 0, 0));
        recipe.add("placement", placement);
        feature.entrySet().stream().filter(entry -> !entry.getKey().equals("kind"))
                .forEach(entry -> recipe.add(entry.getKey(), entry.getValue()));
        return recipe;
    }

    static JsonObject placed(JsonArray placement, JsonObject feature) {
        var data = new JsonObject();
        data.add("placement", placement);
        data.add("feature", feature);
        return data;
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
