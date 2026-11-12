package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.spawner.SpawnRule;
import org.bukkit.block.spawner.SpawnerEntry;
import java.util.ArrayList;
import java.util.Set;

final class SpawnerEntries {
    private SpawnerEntries() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        if (tag.contains("SpawnData")) validateEntry(tag.getCompound("SpawnData"), delivery);
        for (var raw : tag.getList("SpawnPotentials", BinaryTagTypes.COMPOUND)) {
            var potential = (CompoundBinaryTag) raw;
            delivery.fields(potential, Set.of("weight", "data"), "spawner potential");
            validateEntry(potential.getCompound("data"), delivery);
        }
    }
    private static void validateEntry(CompoundBinaryTag data, MetadataDelivery delivery) {
        delivery.fields(data, Set.of("entity", "custom_spawn_rules"), "spawner entry");
        if (data.contains("custom_spawn_rules")) {
            var rules = data.getCompound("custom_spawn_rules");
            delivery.fields(rules, Set.of("block_light_limit", "sky_light_limit"), "spawner light rules");
            for (String key : rules.keySet()) {
                if (rules.get(key) instanceof CompoundBinaryTag range)
                    delivery.fields(range, Set.of("min_inclusive", "max_inclusive"), "spawner light range");
                SpawnerDelivery.lightRange(rules.get(key));
            }
        }
    }
    static void populate(CompoundBinaryTag tag, CreatureSpawner spawner, MetadataDelivery delivery) {
        if (tag.contains("SpawnData")) spawner.setSpawnedEntity(entry(tag.getCompound("SpawnData"), 1, delivery));
        var potentials = new ArrayList<SpawnerEntry>();
        for (var raw : tag.getList("SpawnPotentials", BinaryTagTypes.COMPOUND)) {
            var potential = (CompoundBinaryTag) raw;
            potentials.add(entry(potential.getCompound("data"), Math.toIntExact(NbtMetadata.number(potential, "weight", 1)), delivery));
        }
        if (tag.contains("SpawnPotentials")) spawner.setPotentialSpawns(potentials);
    }

    private static SpawnerEntry entry(CompoundBinaryTag data, int weight, MetadataDelivery delivery) {
        var saved = data.getCompound("entity");
        var entity = Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(saved));
        SpawnRule rule = null;
        if (data.contains("custom_spawn_rules")) {
            var rules = data.getCompound("custom_spawn_rules");
            var block = SpawnerDelivery.lightRange(rules.get("block_light_limit")); var sky = SpawnerDelivery.lightRange(rules.get("sky_light_limit"));
            rule = new SpawnRule(block[0], block[1], sky[0], sky[1]);
        }
        return new SpawnerEntry(entity, weight, rule);
    }

}
