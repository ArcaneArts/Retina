package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Registry-backed random-spread sets and the original weights of all their members. */
final class LegacyStructureSets {
    private LegacyStructureSets() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes,
                             Class<? extends Structure> family, String kind, Consumer<JsonObject> normalize) {
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
        var executable = new LinkedHashMap<Structure, JsonObject>();
        var eligibility = new HashMap<Structure, JsonArray>();
        var perStep = new int[GenerationStep.Decoration.values().length];
        for (var structure : source.stream().toList()) {
            int step = structure.step().ordinal();
            int index = perStep[step]++;
            var allowed = new JsonArray();
            for (int biome = 0; biome < biomes.size(); biome++) {
                if (structure.biomes().contains(biomes.get(biome))) allowed.add(biome);
            }
            if (allowed.isEmpty()) continue;
            eligibility.put(structure, allowed);
            String id = source.getKey(structure).toString();
            if (structure.getClass() != family) {
                omissions.add("structure:" + id);
                continue;
            }
            var definition = new JsonObject();
            var config = decode(Structure.DIRECT_CODEC.encodeStart(ops, structure)).getAsJsonObject();
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
        var sourceSets = registry.registryOrThrow(Registries.STRUCTURE_SET);
        for (var entry : sourceSets.entrySet()) {
            var set = entry.getValue();
            if (set.structures().stream().noneMatch(member -> executable.containsKey(member.structure().value()))) continue;
            String id = entry.getKey().location().toString();
            if (set.placement().getClass() != RandomSpreadStructurePlacement.class) {
                omissions.add("placement:" + id + ":non-random-spread");
                continue;
            }
            var placement = decode(StructurePlacement.CODEC.encodeStart(ops, set.placement())).getAsJsonObject();
            if (placement.has("exclusion_zone")) {
                omissions.add("placement:" + id + ":exclusion-zone");
                continue;
            }
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
            var row = new JsonObject();
            row.addProperty("id", id);
            row.add("placement", placement);
            row.add("entries", members);
            sets.add(row);
        }
        return result;
    }

    static JsonObject missingTemplate(JsonObject result, String id) {
        result.getAsJsonArray("omissions").add("missing_template:" + id);
        result.add("sets", new JsonArray());
        result.add("definitions", new JsonArray());
        return result;
    }
}
