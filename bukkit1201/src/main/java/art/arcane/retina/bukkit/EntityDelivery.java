package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.LimitedRegion;
import java.util.Set;

final class EntityDelivery {
    private EntityDelivery() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        delivery.fields(tag, Set.of("id", "Pos", "Rotation", "Invulnerable", "NoGravity"),
                "Bukkit 1.20.1 entity");
        type(tag, delivery);
    }

    static void populate(CompoundBinaryTag tag, LimitedRegion region, World world, MetadataDelivery delivery) {
        var type = type(tag, delivery);
        if (type == null) return;
        var position = tag.getList("Pos", BinaryTagTypes.DOUBLE);
        if (position.size() != 3) {
            delivery.unavailable("entity with invalid saved position");
            return;
        }
        var rotation = tag.getList("Rotation", BinaryTagTypes.FLOAT);
        var at = new Location(world, position.getDouble(0), position.getDouble(1), position.getDouble(2),
                rotation.getFloat(0, 0), rotation.getFloat(1, 0));
        if (!region.isInRegion(at)) {
            delivery.unavailable("entity outside its limited population region");
            return;
        }
        try {
            region.spawn(at, type.getEntityClass(), false, entity -> {
                if (entity.getType() != type)
                    throw new IllegalArgumentException("Bukkit resolved entity type " + entity.getType() + " for " + type);
                entity.setRotation(at.getYaw(), at.getPitch());
                entity.setInvulnerable(tag.getByte("Invulnerable") != 0);
                entity.setGravity(tag.getByte("NoGravity") == 0);
            });
        } catch (IllegalArgumentException | UnsupportedOperationException error) {
            delivery.failed("entity " + tag.getString("id"), error);
        }
    }

    private static EntityType type(CompoundBinaryTag tag, MetadataDelivery delivery) {
        String id = tag.getString("id");
        var key = NamespacedKey.fromString(id);
        var type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        if (type == null || type.getEntityClass() == null || !type.isSpawnable()) {
            delivery.unavailable("entity " + id);
            return null;
        }
        return type;
    }
}
