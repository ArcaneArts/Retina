package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import art.arcane.retina.mixin.ChunkGeneratorStructureStateAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.*;

/** Reconstructs placement objects from saved rules; registries supply only Structure identities. */
final class SavedStructureSets {
    private final List<Holder<StructureSet>> executable;

    SavedStructureSets(HolderLookup.Provider registry, JsonObject metadata) {
        var structures = metadata.getAsJsonObject("structures");
        var definitions = structures.getAsJsonArray("definitions");
        var descriptions = new LinkedHashMap<String, JsonObject>();
        for (var value : structures.getAsJsonArray("sets")) {
            var data = value.getAsJsonObject(); descriptions.put(data.get("id").getAsString(), data);
        }
        var built = new HashMap<String, Holder<StructureSet>>();
        var active = new HashSet<String>();
        class Builder {
            Holder<StructureSet> build(String key) {
                var existing = built.get(key);
                if (existing != null) return existing;
                if (!active.add(key)) throw new IllegalArgumentException("Cyclic saved structure placement: " + key);
                var data = Objects.requireNonNull(descriptions.get(key), "Missing saved structure set " + key);
                var rules = data.getAsJsonObject("placement");
                var basic = rules.deepCopy(); basic.remove("exclusion_zone");
                var placement = RandomSpreadStructurePlacement.CODEC.codec().parse(JsonOps.INSTANCE, basic).getOrThrow();
                if (rules.has("exclusion_zone")) {
                    var exclusion = rules.getAsJsonObject("exclusion_zone");
                    var zone = new AbstractSpreadingStructurePlacement.ExclusionZone(
                            build(exclusion.get("other_set").getAsString()), exclusion.get("chunk_count").getAsInt());
                    var frequencyMethod = rules.has("frequency_reduction_method")
                            ? AbstractSpreadingStructurePlacement.FrequencyReductionMethod.CODEC.parse(JsonOps.INSTANCE,
                                rules.get("frequency_reduction_method")).getOrThrow()
                            : AbstractSpreadingStructurePlacement.FrequencyReductionMethod.DEFAULT;
                    placement = new RandomSpreadStructurePlacement(placement.locateOffset(), frequencyMethod,
                            rules.has("frequency") ? rules.get("frequency").getAsFloat() : 1F,
                            rules.get("salt").getAsInt(), Optional.of(zone), placement.spacing(), placement.separation(), placement.spreadType());
                }
                var entries = new ArrayList<StructureSet.StructureSelectionEntry>();
                for (var value : data.getAsJsonArray("entries")) {
                    var entry = value.getAsJsonObject();
                    var definition = definitions.get(entry.get("definition").getAsInt()).getAsJsonObject();
                    if (definition.get("unimplemented").getAsBoolean() || definition.getAsJsonArray("biomes").isEmpty()) continue;
                    var structure = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(
                            Registries.STRUCTURE, Identifier.parse(definition.get("id").getAsString())));
                    entries.add(new StructureSet.StructureSelectionEntry(structure, entry.get("weight").getAsInt()));
                }
                var result = Holder.<StructureSet>direct(new StructureSet(List.copyOf(entries), placement));
                active.remove(key); built.put(key, result); return result;
            }
        }
        var builder = new Builder();
        executable = descriptions.entrySet().stream()
                .filter(entry -> !entry.getValue().has("placement_only") || !entry.getValue().get("placement_only").getAsBoolean())
                .map(entry -> builder.build(entry.getKey())).filter(holder -> !holder.value().structures().isEmpty()).toList();
    }

    ChunkGeneratorStructureState createState(RandomState random, long seed, ChunkPos origin, BiomeSource biomes) {
        var state = ChunkGeneratorStructureStateAccessor.retina$create(random, biomes, seed, origin, 0L, executable);
        var access = (ChunkGeneratorStructureStateAccessor) (Object) state;
        for (var set : executable) for (var entry : set.value().structures())
            access.retina$placements().computeIfAbsent(entry.structure().value(), ignored -> new ArrayList<>()).add(set.value().placement());
        access.retina$generated(true);
        return state;
    }
}
