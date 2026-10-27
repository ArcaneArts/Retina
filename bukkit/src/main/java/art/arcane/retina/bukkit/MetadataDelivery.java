package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Location;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.generator.LimitedRegion;
import java.util.Set;
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
            if (!tag.getString("id").equals("minecraft:end_gateway")) { unavailable("block entity " + tag.getString("id")); continue; }
            unknown(tag, Set.of("id", "x", "y", "z", "keepPacked", "Age", "ExactTeleport", "exit_portal"), "end gateway");
        }
        for (var raw : root.getList("entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_crystal")) { unavailable("entity " + tag.getString("id")); continue; }
            unknown(tag, Set.of("id", "Pos", "Motion", "Rotation", "fall_distance", "Fire", "Air", "OnGround",
                    "Invulnerable", "PortalCooldown", "ShowBottom", "beam_target", "UUID"), "end crystal");
            for (var component : tag.getList("Motion", BinaryTagTypes.DOUBLE))
                if (((DoubleBinaryTag) component).value() != 0) unavailable("nonzero initial end crystal motion");
            if (tag.getDouble("fall_distance") != 0 || tag.getShort("Fire") != 0 || tag.getShort("Air", (short) 300) != 300
                    || tag.getByte("OnGround") != 0 || tag.getInt("PortalCooldown") != 0)
                unavailable("nondefault end crystal physics data");
        }
    }

    private void unknown(CompoundBinaryTag tag, Set<String> supported, String kind) {
        for (String key : tag.keySet()) if (!supported.contains(key)) unavailable(kind + " field " + key);
    }

    void populate(CompoundBinaryTag root, LimitedRegion region) {
        for (var raw : root.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            var tag = (CompoundBinaryTag) raw;
            if (!tag.getString("id").equals("minecraft:end_gateway")) continue; // Already explicitly rejected/reported.
            int x = tag.getInt("x"), y = tag.getInt("y"), z = tag.getInt("z");
            if (!region.isInRegion(x, y, z)) throw new IllegalStateException("Native gateway outside its population region");
            if (!(region.getBlockState(x, y, z) instanceof EndGateway gateway))
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
            if (!tag.getString("id").equals("minecraft:end_crystal")) continue;
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
}
