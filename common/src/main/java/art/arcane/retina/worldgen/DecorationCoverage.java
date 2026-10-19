package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.*;

/** Export evidence, not an activation gate or a claim of runtime semantic validation. */
final class DecorationCoverage {
    private static final Gson GSON = new Gson();
    private final RegistryOps<JsonElement> registered;
    private final RegistryOps<JsonElement> expanded;
    private final JsonArray roots = new JsonArray();
    private final Map<String, Integer> statuses = new TreeMap<>();
    private final Set<String> featureTypes = new TreeSet<>();
    private final Set<String> inputTypes = new TreeSet<>();
    private int inventoryErrors;

    DecorationCoverage(HolderLookup.Provider registry) {
        registered = registry.createSerializationContext(JsonOps.INSTANCE);
        // Inline dynamic feature/provider references through their actual codecs,
        // while keeping fixed registries and block tags available to those codecs.
        expanded = RegistryOps.create(JsonOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
            @Override public <T> Optional<HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                if (key.equals(Registries.PLACED_FEATURE) || key.equals(Registries.FEATURE)
                        || key.equals(Registries.BLOCK_STATE_PROVIDER) || key.equals(Registries.NOISE)) return Optional.empty();
                return registered.getter(key);
            }
        });
    }

    void record(PlacedFeature placed, String source, int step, int order, BitSet membership, List<Holder<Biome>> biomes,
                List<Integer> recipes, Set<String> omissions) {
        var root = new JsonObject(); root.addProperty("source", source); root.addProperty("generation_step", step); root.addProperty("root_order", order);
        var members = new JsonArray();
        for (int i = membership.nextSetBit(0); i >= 0; i = membership.nextSetBit(i + 1))
            members.add(biomes.get(i).unwrapKey().map(k -> k.identifier().toString()).orElse("inline/" + i));
        root.add("biomes", members); root.add("recipes", GSON.toJsonTree(recipes)); root.add("omissions", GSON.toJsonTree(omissions));
        String status = recipes.isEmpty() ? omissions.isEmpty() ? "no_commands" : "omitted" : omissions.isEmpty() ? "exported" : "partial";
        root.addProperty("status", status); statuses.merge(status, 1, Integer::sum);
        var types = new TreeSet<String>();
        placed.getFeatures().forEach(f -> {
            var key = BuiltInRegistries.FEATURE_TYPE.getKey(f.value().codec());
            types.add(key == null ? "unregistered:" + f.value().getClass().getName() : key.toString());
        });
        var encoded = PlacedFeature.DIRECT_CODEC.encodeStart(expanded, placed);
        var definition = encoded.result(); var errors = new JsonArray();
        root.addProperty("references_inlined", definition.isPresent());
        if (definition.isEmpty()) {
            errors.add(encoded.error().orElseThrow().message());
            // An audit encoding limitation must not disable a working adapter.
            // Retain its normal registry definition and report the missing expansion.
            var fallback = PlacedFeature.DIRECT_CODEC.encodeStart(registered, placed);
            definition = fallback.result();
            fallback.error().ifPresent(error -> errors.add(error.message()));
        }
        var inputs = new JsonArray();
        definition.ifPresent(value -> { root.add("source_definition", value); inputs(value, "", inputs, types); });
        root.add("feature_types", GSON.toJsonTree(types)); featureTypes.addAll(types);
        root.add("typed_inputs", inputs); root.add("inventory_errors", errors);
        inventoryErrors += errors.size(); roots.add(root);
    }

    private void inputs(JsonElement value, String path, JsonArray inputs, Set<String> features) {
        if (value.isJsonArray()) {
            int index = 0; for (var child : value.getAsJsonArray()) inputs(child, path + "/" + index++, inputs, features);
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            // getSubFeatures() does not expose every child: vanilla pillar cap
            // features, for example, are only visible in the serialized graph.
            if (object.has("placement") && object.has("feature") && object.get("feature").isJsonObject()) {
                var feature = object.getAsJsonObject("feature");
                if (feature.has("type")) features.add(feature.get("type").getAsString());
            }
            for (String discriminator : List.of("type", "predicate_type")) if (object.has(discriminator) && object.get(discriminator).isJsonPrimitive()) {
                String type = object.get(discriminator).getAsString();
                var input = new JsonObject(); input.addProperty("path", path); input.addProperty("discriminator", discriminator); input.addProperty("type", type);
                inputs.add(input); inputTypes.add(type);
            }
            for (var entry : object.entrySet()) inputs(entry.getValue(), path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"), inputs, features);
        }
    }

    JsonObject finish() {
        var result = new JsonObject(); result.addProperty("version", 1); result.add("roots", roots);
        result.add("statuses", GSON.toJsonTree(statuses)); result.add("feature_types", GSON.toJsonTree(featureTypes));
        result.add("input_types", GSON.toJsonTree(inputTypes)); result.addProperty("inventory_errors", inventoryErrors); return result;
    }
}
