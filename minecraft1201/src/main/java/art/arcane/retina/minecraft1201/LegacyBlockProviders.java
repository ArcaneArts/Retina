package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;

final class LegacyBlockProviders {
    record Program(JsonObject data, Set<BlockState> states) { }

    private final RegistryOps<JsonElement> ops;
    private final LegacyFeaturePredicates predicates;
    private final ToIntFunction<BlockState> material;
    private final LegacyDecorationNoise noise;

    LegacyBlockProviders(RegistryOps<JsonElement> ops, LegacyFeaturePredicates predicates,
                         ToIntFunction<BlockState> material, LegacyDecorationNoise noise) {
        this.ops = ops;
        this.predicates = predicates;
        this.material = material;
        this.noise = noise;
    }

    Program encode(BlockStateProvider provider) {
        return encode(decode(BlockStateProvider.CODEC.encodeStart(ops, provider)), 0);
    }

    Program ruleBased(JsonObject input) {
        var states = new LinkedHashSet<BlockState>();
        var program = operation("rule_based");
        if (input.has("fallback")) {
            var fallback = encode(input.get("fallback"), 0);
            program.add("fallback", fallback.data());
            states.addAll(fallback.states());
        }
        var rules = new JsonArray();
        for (var value : input.getAsJsonArray("rules")) {
            var rule = value.getAsJsonObject();
            var next = encode(rule.get("then"), 0);
            var output = new JsonObject();
            output.add("predicate", predicates.compile(rule.getAsJsonObject("if_true")));
            output.add("provider", next.data());
            rules.add(output);
            states.addAll(next.states());
        }
        program.add("rules", rules);
        return new Program(program, states);
    }

    private Program encode(JsonElement value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("provider:recursion");
        var input = value.getAsJsonObject();
        var states = new LinkedHashSet<BlockState>();
        var data = switch (type(input)) {
            case "simple_state_provider" -> {
                var state = operation("state");
                state.addProperty("material", state(input.get("state"), states));
                yield state;
            }
            case "weighted_state_provider" -> {
                var weighted = operation("weighted");
                var entries = new JsonArray();
                for (var valueEntry : input.getAsJsonArray("entries")) {
                    var entry = valueEntry.getAsJsonObject();
                    var child = operation("state");
                    child.addProperty("material", state(entry.get("data"), states));
                    var output = new JsonObject();
                    output.add("weight", entry.get("weight"));
                    output.add("provider", child);
                    entries.add(output);
                }
                weighted.add("entries", entries);
                yield weighted;
            }
            case "randomized_int_state_provider" -> randomized(input, states, depth);
            case "noise_provider" -> {
                var provider = operation("noise");
                provider.addProperty("program", noise.register(input, false));
                provider.add("states", states(input.getAsJsonArray("states"), states));
                yield provider;
            }
            case "dual_noise_provider" -> {
                var provider = operation("dual_noise");
                provider.addProperty("fast", noise.register(input, false));
                provider.addProperty("slow", noise.register(input, true));
                provider.add("variety", input.get("variety"));
                provider.add("states", states(input.getAsJsonArray("states"), states));
                yield provider;
            }
            case "noise_threshold_provider" -> {
                var provider = operation("noise_threshold");
                provider.addProperty("program", noise.register(input, false));
                provider.add("threshold", input.get("threshold"));
                provider.add("high_chance", input.get("high_chance"));
                provider.addProperty("default_state", state(input.get("default_state"), states));
                provider.add("low_states", states(input.getAsJsonArray("low_states"), states));
                provider.add("high_states", states(input.getAsJsonArray("high_states"), states));
                yield provider;
            }
            default -> throw new IllegalArgumentException("provider:" + type(input));
        };
        return new Program(data, states);
    }

    private JsonObject randomized(JsonObject input, Set<BlockState> states, int depth) {
        var source = encode(input.get("source"), depth + 1);
        var values = decode(IntProvider.CODEC.parse(ops, input.get("values")));
        int min = values.getMinValue();
        int max = values.getMaxValue();
        if ((long) max - min > 128) throw new IllegalArgumentException("provider:randomized-int-range");
        String name = input.get("property").getAsString();
        var variants = new JsonArray();
        for (var state : source.states()) {
            if (!(state.getBlock().getStateDefinition().getProperty(name) instanceof IntegerProperty property)) {
                throw new IllegalArgumentException("provider:randomized-int-property:" + name);
            }
            var row = new JsonObject();
            row.addProperty("source", material.applyAsInt(state));
            row.addProperty("minimum", min);
            var ids = new JsonArray();
            for (int i = min; ; i++) {
                var next = state.setValue(property, i);
                ids.add(material.applyAsInt(next));
                states.add(next);
                if (i == max) break;
            }
            row.add("states", ids);
            variants.add(row);
        }
        var result = operation("randomized_int");
        result.add("source", source.data());
        result.add("values", LegacyFeaturePlacement.integer(input.get("values")));
        result.add("variants", variants);
        return result;
    }

    private JsonArray states(JsonArray input, Set<BlockState> states) {
        var ids = new JsonArray();
        for (var value : input) ids.add(state(value, states));
        return ids;
    }

    private int state(JsonElement value, Set<BlockState> states) {
        var state = decode(BlockState.CODEC.parse(ops, value));
        states.add(state);
        return material.applyAsInt(state);
    }

    static JsonObject operation(String type) {
        var result = new JsonObject();
        result.addProperty("type", type);
        return result;
    }
}
