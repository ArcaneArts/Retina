package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.spawner.SpawnRule;
import org.bukkit.block.spawner.SpawnerEntry;
import java.util.ArrayList;
import java.util.Set;

final class SpawnerDelivery {
    static boolean accepts(String id) { return id.equals("minecraft:spawner") || id.equals("minecraft:mob_spawner"); }
    private SpawnerDelivery() { }
    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        delivery.fields(tag, Set.of("id", "x", "y", "z", "keepPacked", "Delay", "MinSpawnDelay", "MaxSpawnDelay",
                "SpawnCount", "MaxNearbyEntities", "RequiredPlayerRange", "SpawnRange", "SpawnData", "SpawnPotentials"), "spawner");
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
                lightRange(rules.get(key));
            }
        }
    }
    static void populate(CompoundBinaryTag tag, CreatureSpawner spawner, MetadataDelivery delivery) {
        try {
            if (tag.contains("SpawnData")) spawner.setSpawnedEntity(entry(tag.getCompound("SpawnData"), 1, delivery));
            var potentials = new ArrayList<SpawnerEntry>();
            for (var raw : tag.getList("SpawnPotentials", BinaryTagTypes.COMPOUND)) {
                var potential = (CompoundBinaryTag) raw;
                potentials.add(entry(potential.getCompound("data"), Math.toIntExact(NbtMetadata.number(potential, "weight", 1)), delivery));
            }
            if (tag.contains("SpawnPotentials")) spawner.setPotentialSpawns(potentials);
            // Apply max before min after temporarily lowering min; all saved ranges are then exact.
            int min = Math.toIntExact(NbtMetadata.number(tag, "MinSpawnDelay", 200));
            int max = Math.toIntExact(NbtMetadata.number(tag, "MaxSpawnDelay", 800));
            spawner.setMinSpawnDelay(0); spawner.setMaxSpawnDelay(max); spawner.setMinSpawnDelay(min);
            spawner.setDelay(Math.toIntExact(NbtMetadata.number(tag, "Delay", 20)));
            spawner.setSpawnCount(Math.toIntExact(NbtMetadata.number(tag, "SpawnCount", 4)));
            spawner.setMaxNearbyEntities(Math.toIntExact(NbtMetadata.number(tag, "MaxNearbyEntities", 6)));
            spawner.setRequiredPlayerRange(Math.toIntExact(NbtMetadata.number(tag, "RequiredPlayerRange", 16)));
            spawner.setSpawnRange(Math.toIntExact(NbtMetadata.number(tag, "SpawnRange", 4)));
            if (!spawner.update(true, false)) throw new IllegalStateException("Could not publish spawner metadata");
        } catch (IllegalArgumentException | UnsupportedOperationException error) { delivery.failed("spawner data", error); }
    }
    private static SpawnerEntry entry(CompoundBinaryTag data, int weight, MetadataDelivery delivery) {
        var saved = data.getCompound("entity");
        var entity = Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(saved));
        SpawnRule rule = null;
        if (data.contains("custom_spawn_rules")) {
            var rules = data.getCompound("custom_spawn_rules");
            var block = lightRange(rules.get("block_light_limit")); var sky = lightRange(rules.get("sky_light_limit"));
            rule = new SpawnRule(block[0], block[1], sky[0], sky[1]);
        }
        return new SpawnerEntry(entity, weight, rule);
    }

    static int[] lightRange(BinaryTag tag) {
        int[] range;
        if (tag == null) return new int[]{0, 15};
        if (tag instanceof NumberBinaryTag number) range = new int[]{Math.toIntExact(number.longValue()), Math.toIntExact(number.longValue())};
        else if (tag instanceof IntArrayBinaryTag array && array.size() == 2) range = array.value();
        else if (tag instanceof ListBinaryTag list && list.size() == 2 && list.get(0) instanceof NumberBinaryTag min && list.get(1) instanceof NumberBinaryTag max)
            range = new int[]{Math.toIntExact(min.longValue()), Math.toIntExact(max.longValue())};
        else if (tag instanceof CompoundBinaryTag compound) range = new int[]{Math.toIntExact(NbtMetadata.number(compound, "min_inclusive", 0)),
                Math.toIntExact(NbtMetadata.number(compound, "max_inclusive", 15))};
        else throw new IllegalStateException("Invalid saved spawner light range");
        if (range[0] < 0 || range[1] > 15 || range[0] > range[1]) throw new IllegalStateException("Saved spawner light range is outside 0..15");
        return range;
    }
}
