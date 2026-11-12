package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;

/** Resolve worldgen predicates against the final local material palette. */
final class LegacyFeaturePredicates {
    private record Binding(JsonArray allowed, Predicate<BlockState> matches) { }

    private final List<Binding> bindings = new ArrayList<>();

    JsonObject material(JsonArray offset, Predicate<BlockState> matches) {
        var result = operation("material");
        var allowed = new JsonArray();
        result.add("offset", offset);
        result.add("allowed", allowed);
        bindings.add(new Binding(allowed, matches));
        return result;
    }

    JsonObject material(Predicate<BlockState> matches) {
        return material(array(0, 0, 0), matches);
    }

    JsonObject blocks(JsonArray offset, Block... blocks) {
        var selected = List.of(blocks);
        return material(offset, state -> selected.contains(state.getBlock()));
    }

    JsonObject compile(JsonObject input) {
        String kind = type(input);
        var offset = input.has("offset") ? input.getAsJsonArray("offset") : array(0, 0, 0);
        return switch (kind) {
            case "matching_blocks" -> material(offset, selection(input.get("blocks")));
            case "matching_block_tag" -> material(offset, selection(new JsonPrimitive("#" + input.get("tag").getAsString())));
            case "replaceable" -> material(offset, BlockState::canBeReplaced);
            case "solid" -> material(offset, BlockState::isSolid);
            case "true" -> operation(kind);
            case "not" -> {
                var result = operation(kind);
                result.add("predicate", compile(input.getAsJsonObject("predicate")));
                yield result;
            }
            case "all_of", "any_of" -> {
                var children = new JsonArray();
                for (var child : input.getAsJsonArray("predicates")) children.add(compile(child.getAsJsonObject()));
                yield group(kind, children);
            }
            default -> throw new IllegalArgumentException("predicate:" + kind);
        };
    }

    static JsonObject all(JsonObject... predicates) {
        var children = new JsonArray();
        for (var predicate : predicates) children.add(predicate);
        return group("all_of", children);
    }

    void finish(LinkedHashMap<BlockState, Integer> palette) {
        for (var binding : bindings) {
            palette.forEach((state, id) -> {
                if (binding.matches().test(state)) binding.allowed().add(id);
            });
        }
    }

    private static Predicate<BlockState> selection(JsonElement value) {
        if (value instanceof JsonArray array) {
            var children = array.asList().stream().map(LegacyFeaturePredicates::selection).toList();
            return state -> children.stream().anyMatch(child -> child.test(state));
        }
        String name = value.getAsString();
        if (name.startsWith("#")) {
            var tag = TagKey.create(Registries.BLOCK, new ResourceLocation(name.substring(1)));
            return state -> state.is(tag);
        }
        var block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(name))
                .orElseThrow(() -> new IllegalArgumentException("Unknown predicate block " + name));
        return state -> state.is(block);
    }

    private static JsonObject group(String kind, JsonArray children) {
        var result = operation(kind);
        result.add("predicates", children);
        return result;
    }

    private static JsonObject operation(String kind) {
        var result = new JsonObject();
        result.addProperty("type", kind);
        return result;
    }
}
