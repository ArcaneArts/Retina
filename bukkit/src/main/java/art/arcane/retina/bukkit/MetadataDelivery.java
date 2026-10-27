package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.LivingEntity;
import org.bukkit.generator.LimitedRegion;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Public-API metadata delivery with explicit errors or reported opt-in loss. */
final class MetadataDelivery {
    private final Logger log;
    private final boolean allowLoss;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    MetadataDelivery(Logger log, boolean allowLoss) { this.log = log; this.allowLoss = allowLoss; }

    void unavailable(String feature) {
        if (!allowLoss) throw new IllegalStateException("Bukkit API cannot deliver " + feature
                + ". Use the bukkit-api catalog for new worlds, a compatible snapshot, or explicitly opt into allow-metadata-loss. The saved file is preserved.");
        if (reported.add(feature)) log.warning("Explicit allow-metadata-loss: omitting " + feature);
    }

    void failed(String feature, RuntimeException cause) {
        if (!allowLoss) throw new IllegalStateException("Bukkit API could not deliver " + feature + "; saved definition is preserved", cause);
        if (reported.add(feature)) log.warning("Explicit allow-metadata-loss: omitting " + feature + " (" + cause.getClass().getSimpleName() + ")");
    }

    void unverified(String field) {
        if (reported.add("unverified:" + field)) log.warning("Public API observation cannot verify " + field
                + "; delivery is attempted through the API. This is not a chunk-NBT parity guarantee.");
    }

    void validate(CompoundBinaryTag root) {
        var structures = root.getCompound("structures");
        if (structures.getCompound("starts").size() != 0 || structures.getCompound("References").size() != 0)
            unavailable("structure starts/references and locate metadata");
        for (String name : new String[]{"block_ticks", "fluid_ticks"})
            if (!root.getList(name).isEmpty()) unavailable("saved " + name + " queues");
        for (var section : root.getList("PostProcessing"))
            if (section instanceof ListBinaryTag list && !list.isEmpty()) unavailable("saved chunk post-processing queues");
        for (var raw : root.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            String id = tag.getString("id");
            if (SpawnerDelivery.accepts(id)) SpawnerDelivery.validate(tag, this);
            else if (InventoryDelivery.TYPES.contains(id)) InventoryDelivery.validate(tag, this);
            else if (id.equals("minecraft:end_gateway")) fields(tag, Set.of("id", "x", "y", "z", "keepPacked", "Age", "ExactTeleport", "exit_portal"), "end gateway");
            else unavailable("block entity " + id);
        }
        for (var raw : root.getList("entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_crystal")) {
                try { Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(tag)); }
                catch (IllegalArgumentException | UnsupportedOperationException error) { failed("entity " + tag.getString("id"), error); }
                continue;
            }
            fields(tag, Set.of("id", "Pos", "Motion", "Rotation", "fall_distance", "Fire", "Air", "OnGround",
                    "Invulnerable", "PortalCooldown", "ShowBottom", "beam_target", "UUID"), "end crystal");
            for (var component : tag.getList("Motion", BinaryTagTypes.DOUBLE))
                if (((DoubleBinaryTag) component).value() != 0) unavailable("nonzero initial end crystal motion");
            if (tag.getDouble("fall_distance") != 0 || tag.getShort("Fire") != 0 || tag.getShort("Air", (short) 300) != 300
                    || tag.getByte("OnGround") != 0 || tag.getInt("PortalCooldown") != 0)
                unavailable("nondefault end crystal physics data");
        }
    }

    void fields(CompoundBinaryTag tag, Set<String> supported, String kind) {
        for (String key : tag.keySet()) {
            if (key.equals("components") && tag.get(key) instanceof CompoundBinaryTag components && components.isEmpty()) continue;
            if (!supported.contains(key)) unavailable(kind + " field " + key);
        }
    }

    void populate(CompoundBinaryTag root, LimitedRegion region, World world) {
        for (var raw : root.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            int x = tag.getInt("x"), y = tag.getInt("y"), z = tag.getInt("z");
            String id = tag.getString("id");
            if (!id.equals("minecraft:end_gateway") && !SpawnerDelivery.accepts(id) && !InventoryDelivery.TYPES.contains(id)) continue;
            if (!region.isInRegion(x, y, z)) throw new IllegalStateException("Native block entity outside its population region");
            var target = region.getBlockState(x, y, z);
            if (SpawnerDelivery.accepts(id)) {
                if (!(target instanceof CreatureSpawner spawner)) throw new IllegalStateException("Spawner metadata has no spawner block");
                SpawnerDelivery.populate(tag, spawner, this); continue;
            }
            if (InventoryDelivery.TYPES.contains(id)) {
                if (!(target instanceof Container container)) throw new IllegalStateException("Container metadata has no container block");
                InventoryDelivery.populate(tag, container, this); continue;
            }
            if (!(target instanceof EndGateway gateway))
                throw new IllegalStateException("Native gateway metadata has no gateway block at " + x + "," + y + "," + z);
            gateway.setAge(tag.getLong("Age"));
            gateway.setExactTeleport(tag.getByte("ExactTeleport") != 0);
            int[] exit = tag.getIntArray("exit_portal", new int[0]);
            if (exit.length == 3) gateway.setExitLocation(new Location(gateway.isPlaced() ? gateway.getWorld() : null, exit[0], exit[1], exit[2]));
            else if (exit.length == 0) gateway.setExitLocation(null);
            else throw new IllegalStateException("Invalid saved gateway exit position");
            if (!gateway.update(true, false)) throw new IllegalStateException("Could not install native gateway metadata");
        }
        for (var raw : root.getList("entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_crystal")) {
                entity(tag, region, world); continue;
            }
            var position = tag.getList("Pos", BinaryTagTypes.DOUBLE);
            var rotation = tag.getList("Rotation", BinaryTagTypes.FLOAT);
            var at = new Location(null, position.getDouble(0), position.getDouble(1), position.getDouble(2),
                    rotation.size() == 2 ? rotation.getFloat(0) : 0, rotation.size() == 2 ? rotation.getFloat(1) : 0);
            if (!region.isInRegion(at)) throw new IllegalStateException("Native crystal outside its population region");
            region.spawn(at, EnderCrystal.class, crystal -> {
                crystal.setRotation(at.getYaw(), at.getPitch());
                crystal.setShowingBottom(tag.getByte("ShowBottom", (byte) 1) != 0);
                crystal.setInvulnerable(tag.getByte("Invulnerable") != 0);
                int[] target = tag.getIntArray("beam_target", new int[0]);
                if (target.length == 3) crystal.setBeamTarget(new Location(crystal.getWorld(), target[0], target[1], target[2]));
                else if (target.length != 0) throw new IllegalStateException("Invalid saved crystal beam target");
                // Bukkit owns runtime entity UUIDs. No raw NBT/UUID injection is attempted.
            });
        }
    }

    private void entity(CompoundBinaryTag tag, LimitedRegion region, World world) {
        try {
            var snapshot = Bukkit.getEntityFactory().createEntitySnapshot(NbtMetadata.text(tag));
            // The World overload constructs an unspawned object. Only LimitedRegion publishes it.
            var entity = snapshot.createEntity(world);
            if (!region.isInRegion(entity.getLocation())) { unavailable("entity outside its limited population region"); return; }
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
                if (physical.contains(key)) {
                    String difference = NbtMetadata.difference(tag.get(key), physical.get(key), "entity." + key);
                    if (difference != null) unavailable(difference);
                } else remaining.put(key, tag.get(key));
            }
            var roundtrip = entity.createSnapshot();
            if (roundtrip == null) unverified("entity snapshot " + tag.getString("id"));
            else {
                String difference = NbtMetadata.difference(remaining.build(), NbtMetadata.compound(roundtrip.getAsString()), "entity " + tag.getString("id"));
                if (difference != null) unverified(difference);
            }
            region.addEntity(entity);
        } catch (IllegalArgumentException | UnsupportedOperationException error) { failed("entity " + tag.getString("id"), error); }
    }
}
