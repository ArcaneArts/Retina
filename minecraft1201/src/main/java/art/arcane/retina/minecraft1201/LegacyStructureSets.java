package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Registry-backed sets and the original weights of all their members. */
final class LegacyStructureSets {
    enum RingBiomeSource {
        FLAT("flat"), FIXED("fixed"), MULTI_NOISE("multi_noise");

        private final String id;
        RingBiomeSource(String id) { this.id = id; }
    }

    private LegacyStructureSets() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Class<? extends Structure> family, String kind, Consumer<JsonObject> normalize) {
        return export(registry, biomes, Map.of(family, kind), normalize);
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Map<Class<? extends Structure>, String> families, Consumer<JsonObject> normalize) {
        return export(registry, biomes, families, normalize, (kind, config) -> true);
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Map<Class<? extends Structure>, String> families, Consumer<JsonObject> normalize,
                             BiPredicate<String, JsonObject> supports) {
        return export(registry, biomes, families, normalize, supports, Optional.empty());
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Map<Class<? extends Structure>, String> families, Consumer<JsonObject> normalize,
                             BiPredicate<String, JsonObject> supports, Optional<Set<ResourceLocation>> allowedSets) {
        return export(registry, biomes, families, normalize, supports, allowedSets, null);
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Map<Class<? extends Structure>, String> families, Consumer<JsonObject> normalize,
                             BiPredicate<String, JsonObject> supports, Optional<Set<ResourceLocation>> allowedSets,
                             RingBiomeSource ringBiomeSource) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registry);
        var definitions = new JsonArray();
        var sets = new JsonArray();
        var omissions = new JsonArray();
        var result = new JsonObject();
        result.add("templates", new JsonArray());
        result.add("definitions", definitions);
        result.add("sets", sets);
        result.add("pools", new JsonObject());
        result.add("omissions", omissions);
        var source = registry.registryOrThrow(Registries.STRUCTURE);
        var sourceSets = registry.registryOrThrow(Registries.STRUCTURE_SET);
        // Set and template indices must not depend on the registry map's iteration order.
        var orderedSets = sourceSets.entrySet().stream()
                .filter(entry -> allowedSets.isEmpty() || allowedSets.get().contains(entry.getKey().location()))
                .sorted(Comparator.comparing(entry -> entry.getKey().location()))
                .toList();
        var selected = allowedSets.map(ignored -> orderedSets.stream().flatMap(entry -> entry.getValue().structures().stream())
                .map(member -> member.structure().value()).collect(Collectors.toSet()));
        var executable = new LinkedHashMap<Structure, JsonObject>();
        var eligibility = new HashMap<Structure, JsonArray>();
        var perStep = new int[GenerationStep.Decoration.values().length];
        for (var structure : source.stream().toList()) {
            int step = structure.step().ordinal();
            int index = perStep[step]++;
            if (selected.isPresent() && !selected.get().contains(structure)) continue;
            var allowed = new JsonArray();
            for (int biome = 0; biome < biomes.size(); biome++) {
                if (structure.biomes().contains(biomes.get(biome))) allowed.add(biome);
            }
            if (allowed.isEmpty()) continue;
            eligibility.put(structure, allowed);
            String id = source.getKey(structure).toString();
            String kind = families.get(structure.getClass());
            if (kind == null) {
                omissions.add("structure:" + id);
                continue;
            }
            var definition = new JsonObject();
            var config = decode(Structure.DIRECT_CODEC.encodeStart(ops, structure)).getAsJsonObject();
            if (!supports.test(kind, config)) {
                omissions.add("structure:" + id + ":unsupported_setup");
                continue;
            }
            config.addProperty("retina_decoration_index", index);
            config.addProperty("retina_decoration_step", step);
            normalize.accept(config);
            definition.addProperty("id", id);
            definition.addProperty("kind", kind);
            definition.add("config", config);
            definition.add("biomes", allowed);
            executable.put(structure, definition);
        }
        var indexes = new LinkedHashMap<Structure, Integer>();
        var requiredPlacements = new LinkedHashMap<String, JsonObject>();
        for (var entry : orderedSets) {
            var set = entry.getValue();
            if (set.structures().stream().noneMatch(member -> executable.containsKey(member.structure().value()))) continue;
            String id = entry.getKey().location().toString();
            var setRow = encodePlacement(set.placement(), biomes, ringBiomeSource, ops);
            if (setRow == null) {
                omissions.add("placement:" + id + ":unsupported-placement");
                continue;
            }
            var dependencies = new LinkedHashMap<String, JsonObject>();
            if (!resolveDependencies(id, setRow, sourceSets, biomes, allowedSets, ringBiomeSource, ops,
                    dependencies, new LinkedHashSet<>())) {
                omissions.add("placement:" + id + ":unsupported-exclusion-zone");
                continue;
            }
            requiredPlacements.putAll(dependencies);
            var members = new JsonArray();
            for (var member : set.structures()) {
                int definition = indexes.computeIfAbsent(member.structure().value(), ignored -> {
                    var row = executable.get(member.structure().value());
                    if (row == null) {
                        row = new JsonObject();
                        row.addProperty("id", member.structure().unwrapKey().orElseThrow().location().toString());
                        row.addProperty("kind", "unavailable");
                        row.addProperty("unimplemented", true);
                        row.add("biomes", eligibility.getOrDefault(member.structure().value(), new JsonArray()));
                        row.add("config", new JsonObject());
                    }
                    definitions.add(row);
                    return definitions.size() - 1;
                });
                var encoded = new JsonObject();
                encoded.addProperty("definition", definition);
                encoded.addProperty("weight", member.weight());
                members.add(encoded);
            }
            setRow.addProperty("id", id);
            setRow.add("entries", members);
            sets.add(setRow);
        }
        for (var entry : requiredPlacements.entrySet()) {
            if (sets.asList().stream().anyMatch(row -> row.getAsJsonObject().get("id").getAsString().equals(entry.getKey()))) continue;
            var row = entry.getValue();
            row.addProperty("id", entry.getKey());
            row.addProperty("placement_only", true);
            row.add("entries", new JsonArray());
            sets.add(row);
        }
        return result;
    }

    private static JsonObject encodePlacement(StructurePlacement placement, List<Holder<Biome>> biomes,
                                              RingBiomeSource biomeSource, RegistryOps<JsonElement> ops) {
        if (placement.getClass() != RandomSpreadStructurePlacement.class
                && (placement.getClass() != ConcentricRingsStructurePlacement.class || biomeSource == null)) return null;
        var rules = decode(StructurePlacement.CODEC.encodeStart(ops, placement)).getAsJsonObject();
        var result = new JsonObject();
        if (placement instanceof ConcentricRingsStructurePlacement rings) {
            var settings = new JsonObject();
            settings.add("distance", rules.remove("distance"));
            settings.add("spread", rules.remove("spread"));
            settings.add("count", rules.remove("count"));
            rules.remove("preferred_biomes");
            settings.addProperty("biome_source", biomeSource.id);
            var preferred = new JsonArray();
            for (int index = 0; index < biomes.size(); index++) {
                if (rings.preferredBiomes().contains(biomes.get(index))) preferred.add(index);
            }
            settings.add("preferred", preferred);
            result.add("rings", settings);
        }
        result.add("placement", rules);
        return result;
    }

    private static boolean resolveDependencies(String id, JsonObject row, Registry<StructureSet> source,
                                               List<Holder<Biome>> biomes, Optional<Set<ResourceLocation>> allowedSets,
                                               RingBiomeSource ringBiomeSource,
                                               RegistryOps<JsonElement> ops, Map<String, JsonObject> dependencies,
                                               LinkedHashSet<String> path) {
        if (!path.add(id)) return false;
        var exclusion = row.getAsJsonObject("placement").getAsJsonObject("exclusion_zone");
        if (exclusion == null) {
            path.remove(id);
            return true;
        }
        var targetId = exclusion.get("other_set");
        if (!targetId.isJsonPrimitive()) return false;
        String other = targetId.getAsString();
        var targetIdKey = new ResourceLocation(other);
        var target = source.get(targetIdKey);
        if (target == null) return false;
        var encoded = encodePlacement(target.placement(), biomes, ringBiomeSource, ops);
        if (encoded == null) return false;
        if (encoded.has("rings") && (allowedSets.isPresent() && !allowedSets.get().contains(targetIdKey)
                || target.structures().stream().noneMatch(member -> biomes.stream().anyMatch(member.structure().value().biomes()::contains)))) {
            // Vanilla has no computed ring positions for a set excluded from the generator's state.
            encoded.getAsJsonObject("rings").addProperty("count", 0);
        }
        if (!resolveDependencies(other, encoded, source, biomes, allowedSets, ringBiomeSource, ops, dependencies, path)) return false;
        dependencies.put(other, encoded);
        path.remove(id);
        return true;
    }

    static JsonObject missingTemplate(JsonObject result, String id) {
        result.getAsJsonArray("omissions").add("missing_template:" + id);
        result.add("sets", new JsonArray());
        result.add("definitions", new JsonArray());
        return result;
    }
}
