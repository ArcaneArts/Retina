package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import java.util.Set;

final class SpawnerEntries {
    private SpawnerEntries() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        if (!tag.getList("SpawnPotentials").isEmpty())
            delivery.unavailable("weighted spawner entries on Bukkit 1.20.1");
        if (tag.get("SpawnData") != null) spawnedType(tag.getCompound("SpawnData"), delivery);
    }

    static void populate(CompoundBinaryTag tag, CreatureSpawner spawner, MetadataDelivery delivery) {
        if (tag.get("SpawnData") == null) return;
        var type = spawnedType(tag.getCompound("SpawnData"), delivery);
        if (type != null) spawner.setSpawnedType(type);
    }

    private static EntityType spawnedType(CompoundBinaryTag data, MetadataDelivery delivery) {
        delivery.fields(data, Set.of("entity"), "Bukkit 1.20.1 spawner entry");
        var entity = data.getCompound("entity");
        delivery.fields(entity, Set.of("id"), "Bukkit 1.20.1 spawner entity");
        String id = entity.getString("id");
        var key = NamespacedKey.fromString(id);
        var type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        if (type == null || !type.isSpawnable()) {
            delivery.unavailable("spawner entity " + id);
            return null;
        }
        return type;
    }
}
