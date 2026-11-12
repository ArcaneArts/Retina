package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.generator.LimitedRegion;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Public-API metadata delivery with explicit errors or reported opt-in loss. */
final class MetadataDelivery {
    private static final Set<String> DEFAULT_SCULK_ENTITIES = Set.of(
            "minecraft:sculk_catalyst", "minecraft:sculk_sensor", "minecraft:sculk_shrieker");
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
            else if (DEFAULT_SCULK_ENTITIES.contains(id)) {
                // ChunkData installs these tiles from their block states. The legacy generator
                // supplies no custom catalyst cursors, vibration listeners or warning state.
                fields(tag, Set.of("id", "x", "y", "z", "keepPacked"), id);
            }
            else if (id.equals("minecraft:brushable_block")) {
                fields(tag, Set.of("id", "x", "y", "z", "keepPacked", "LootTable", "LootTableSeed"), "brushable block");
            }
            else if (id.equals("minecraft:end_gateway")) {
                fields(tag, Set.of("id", "x", "y", "z", "keepPacked", "Age", "ExactTeleport", "exit_portal", "ExitPortal"), "end gateway");
                position(tag, "exit_portal", "ExitPortal");
            }
            else unavailable("block entity " + id);
        }
        for (var raw : root.getList("entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_crystal")) {
                EntityDelivery.validate(tag, this);
                continue;
            }
            fields(tag, Set.of("id", "Pos", "Motion", "Rotation", "fall_distance", "FallDistance", "Fire", "Air", "OnGround",
                    "Invulnerable", "PortalCooldown", "ShowBottom", "beam_target", "BeamTarget", "UUID"), "end crystal");
            position(tag, "beam_target", "BeamTarget");
            for (var component : tag.getList("Motion", BinaryTagTypes.DOUBLE))
                if (((DoubleBinaryTag) component).value() != 0) unavailable("nonzero initial end crystal motion");
            if (tag.getDouble("fall_distance") != 0 || tag.getFloat("FallDistance") != 0
                    || tag.getShort("Fire") != 0 || tag.getShort("Air", (short) 300) != 300
                    || tag.getByte("OnGround") != 0 || tag.getInt("PortalCooldown") != 0)
                unavailable("nondefault end crystal physics data");
        }
    }

    private static int[] position(CompoundBinaryTag tag, String modern, String legacy) {
        var encoded = tag.get(modern);
        if (encoded instanceof IntArrayBinaryTag array) {
            int[] values = array.value();
            if (values.length == 3 || values.length == 0) return values;
        }
        if (encoded != null) throw new IllegalStateException("Invalid saved position " + modern);
        encoded = tag.get(legacy);
        if (encoded == null) return new int[0];
        if (encoded instanceof CompoundBinaryTag pos && pos.get("X") instanceof IntBinaryTag x
                && pos.get("Y") instanceof IntBinaryTag y && pos.get("Z") instanceof IntBinaryTag z)
            return new int[]{x.value(), y.value(), z.value()};
        throw new IllegalStateException("Invalid saved position " + legacy);
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
            boolean brushable = id.equals("minecraft:brushable_block");
            if (!brushable && !id.equals("minecraft:end_gateway") && !SpawnerDelivery.accepts(id)
                    && !InventoryDelivery.TYPES.contains(id)) continue;
            if (!region.isInRegion(x, y, z)) throw new IllegalStateException("Native block entity outside its population region");
            var target = region.getBlockState(x, y, z);
            if (brushable) {
                if (!(target instanceof BrushableBlock sand)) throw new IllegalStateException("Brushable metadata has no brushable block");
                InventoryDelivery.applyLoot(tag, sand, this);
                if (!sand.update(true, false)) throw new IllegalStateException("Could not publish brushable block metadata");
                continue;
            }
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
            int[] exit = position(tag, "exit_portal", "ExitPortal");
            if (exit.length == 3) gateway.setExitLocation(new Location(gateway.isPlaced() ? gateway.getWorld() : null, exit[0], exit[1], exit[2]));
            else gateway.setExitLocation(null);
            if (!gateway.update(true, false)) throw new IllegalStateException("Could not install native gateway metadata");
        }
        for (var raw : root.getList("entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_crystal")) {
                EntityDelivery.populate(tag, region, world, this); continue;
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
                int[] target = position(tag, "beam_target", "BeamTarget");
                if (target.length == 3) crystal.setBeamTarget(new Location(crystal.getWorld(), target[0], target[1], target[2]));
                // Bukkit owns runtime entity UUIDs. No raw NBT/UUID injection is attempted.
            });
        }
    }

}
