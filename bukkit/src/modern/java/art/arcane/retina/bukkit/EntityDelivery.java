package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.generator.LimitedRegion;
import java.util.UUID;

final class EntityDelivery {
    private EntityDelivery() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        try { Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(tag)); }
        catch (IllegalArgumentException | UnsupportedOperationException error) {
            delivery.failed("entity " + tag.getString("id"), error);
        }
    }

    static void populate(CompoundBinaryTag tag, LimitedRegion region, World world, MetadataDelivery delivery) {
        try {
            var snapshot = Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(tag));
            // The World overload constructs an unspawned object. Only LimitedRegion publishes it.
            var entity = snapshot.createEntity(world);
            if (!region.isInRegion(entity.getLocation())) { delivery.unavailable("entity outside its limited population region"); return; }
            var observed = CompoundBinaryTag.builder().putString("id", entity.getType().getKey().toString());
            var at = entity.getLocation(); var velocity = entity.getVelocity();
            observed.put("Pos", ListBinaryTag.from(java.util.List.of(DoubleBinaryTag.doubleBinaryTag(at.getX()), DoubleBinaryTag.doubleBinaryTag(at.getY()), DoubleBinaryTag.doubleBinaryTag(at.getZ()))));
            observed.put("Rotation", ListBinaryTag.from(java.util.List.of(FloatBinaryTag.floatBinaryTag(at.getYaw()), FloatBinaryTag.floatBinaryTag(at.getPitch()))));
            observed.put("Motion", ListBinaryTag.from(java.util.List.of(DoubleBinaryTag.doubleBinaryTag(velocity.getX()), DoubleBinaryTag.doubleBinaryTag(velocity.getY()), DoubleBinaryTag.doubleBinaryTag(velocity.getZ()))));
            UUID uuid = entity.getUniqueId();
            observed.putIntArray("UUID", new int[]{(int) (uuid.getMostSignificantBits() >>> 32), (int) uuid.getMostSignificantBits(),
                    (int) (uuid.getLeastSignificantBits() >>> 32), (int) uuid.getLeastSignificantBits()});
            if (entity instanceof LivingEntity living) observed.putInt("Air", living.getRemainingAir());
            observed.putInt("Fire", entity.getFireTicks())
                    .putByte("OnGround", (byte) (entity.isOnGround() ? 1 : 0)).putByte("Invulnerable", (byte) (entity.isInvulnerable() ? 1 : 0))
                    .putByte("NoGravity", (byte) (entity.hasGravity() ? 0 : 1)).putFloat("fall_distance", entity.getFallDistance())
                    .putInt("PortalCooldown", entity.getPortalCooldown());
            var physical = observed.build();
            var remaining = CompoundBinaryTag.builder();
            for (String key : tag.keySet()) {
                if (physical.get(key) != null) {
                    String difference = NbtMetadata.difference(tag.get(key), physical.get(key), "entity." + key);
                    if (difference != null) delivery.unavailable(difference);
                } else remaining.put(key, tag.get(key));
            }
            var roundtrip = entity.createSnapshot();
            if (roundtrip == null) delivery.unverified("entity snapshot " + tag.getString("id"));
            else {
                String difference = NbtMetadata.difference(remaining.build(), NbtMetadata.compound(roundtrip.getAsString()), "entity " + tag.getString("id"));
                if (difference != null) delivery.unverified(difference);
            }
            region.addEntity(entity);
        } catch (IllegalArgumentException | UnsupportedOperationException error) { delivery.failed("entity " + tag.getString("id"), error); }
    }
}
