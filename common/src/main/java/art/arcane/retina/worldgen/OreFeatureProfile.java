package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

import java.util.*;
import java.util.function.ToIntFunction;

/** Ordered ore features retain target rules and their RNG, rather than a first-match replacement table. */
final class OreFeatureProfile {
    static JsonObject export(AbstractOreFeature feature, HolderLookup.Provider registry, ToIntFunction<BlockState> material) {
        // Subclasses can override placement/canPlaceOre; accepting their base fields would lose that behavior.
        if (feature.getClass() != OreFeature.class && feature.getClass() != ScatteredOreFeature.class) return null;
        var targets = new JsonArray();
        for (var target : feature.targetStates()) {
            var rule = RuleTest.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), target.target()).getOrThrow().getAsJsonObject();
            if (!supported(rule)) return null;
            var entry = new JsonObject(); entry.add("rule", rule); entry.addProperty("state", material.applyAsInt(target.state())); targets.add(entry);
        }
        var data = new JsonObject(); data.addProperty("kind", "ore"); data.add("targets", targets);
        data.addProperty("size", feature.size()); data.addProperty("discard", feature.discardChanceOnAirExposure());
        data.addProperty("scattered", feature instanceof ScatteredOreFeature);
        data.addProperty("heightmap", Heightmap.Types.OCEAN_FLOOR_WG.ordinal());
        int reach = feature instanceof ScatteredOreFeature ? 7 : (int)Math.ceil(feature.size() / 8.0F) + (int)Math.ceil((feature.size() / 8.0F + 1) / 2);
        var result = new JsonObject(); result.addProperty("kind", "nether_feature"); result.addProperty("reach", reach); result.add("nether", data); return result;
    }

    private static String type(JsonObject rule) { return rule.get("predicate_type").getAsString().replace("minecraft:", ""); }

    private static boolean supported(JsonObject rule) {
        return switch (type(rule)) {
            case "always_true", "block_match", "tag_match", "blockstate_match", "random_block_match", "random_blockstate_match", "height_match" -> true;
            case "all_of", "any_of" -> rule.getAsJsonArray("rules").asList().stream().allMatch(v -> supported(v.getAsJsonObject()));
            case "not" -> supported(rule.getAsJsonObject("rule"));
            default -> false;
        };
    }

    static void finish(JsonObject data, LinkedHashMap<BlockState, Integer> palette) {
        for (var target : data.getAsJsonArray("targets")) {
            var entry = target.getAsJsonObject(); entry.add("rule", compile(entry.getAsJsonObject("rule"), palette));
        }
        var air = new JsonArray(); palette.forEach((state, id) -> { if (state.isAir()) air.add(id); }); data.add("air", air);
    }

    private static JsonObject compile(JsonObject input, LinkedHashMap<BlockState, Integer> palette) {
        String type = type(input); var result = new JsonObject(); result.addProperty("kind", type);
        if (type.equals("all_of") || type.equals("any_of")) {
            var children = new JsonArray(); for (var child : input.getAsJsonArray("rules")) children.add(compile(child.getAsJsonObject(), palette));
            result.add("rules", children);
        } else if (type.equals("not")) result.add("rule", compile(input.getAsJsonObject("rule"), palette));
        else if (type.equals("height_match")) {
            result.add("min", input.get("min_inclusive")); result.add("max", input.get("max_inclusive"));
        } else if (!type.equals("always_true")) {
            var staticRule = input.deepCopy(); result.addProperty("kind", "material");
            if (type.startsWith("random_")) {
                staticRule.addProperty("predicate_type", "minecraft:" + type.substring("random_".length()));
                result.add("chance", staticRule.remove("probability"));
            }
            var rule = RuleTest.CODEC.parse(JsonOps.INSTANCE, staticRule).getOrThrow();
            var allowed = new JsonArray(); var rng = RandomSource.create(0);
            palette.forEach((state, id) -> { if (rule.test(state, BlockPos.ZERO, rng)) allowed.add(id); });
            result.add("allowed", allowed);
        }
        return result;
    }

    static Set<Integer> materials(JsonObject data) {
        var result = new LinkedHashSet<Integer>(); for (var target : data.getAsJsonArray("targets")) result.add(target.getAsJsonObject().get("state").getAsInt()); return result;
    }
}
