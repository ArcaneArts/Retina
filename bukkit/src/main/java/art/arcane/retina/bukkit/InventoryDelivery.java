package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import java.util.Set;
import java.util.StringJoiner;

final class InventoryDelivery {
    static final Set<String> TYPES = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:shulker_box", "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper");
    private InventoryDelivery() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        delivery.fields(tag, Set.of("id", "x", "y", "z", "keepPacked", "Items", "LootTable", "LootTableSeed"), "container");
        if (!tag.getString("LootTable").isEmpty() && !tag.getList("Items").isEmpty())
            delivery.unavailable("simultaneous lazy loot table and prefilled inventory");
        for (var raw : tag.getList("Items", BinaryTagTypes.COMPOUND))
            delivery.fields((CompoundBinaryTag) raw, Set.of("Slot", "id", "count", "Count", "components"), "inventory item");
    }

    static void populate(CompoundBinaryTag tag, Container container, MetadataDelivery delivery) {
        var inventory = container.getSnapshotInventory(); inventory.clear();
        for (var raw : tag.getList("Items", BinaryTagTypes.COMPOUND)) {
            var saved = (CompoundBinaryTag) raw;
            int slot = Math.toIntExact(NbtMetadata.number(saved, "Slot", -1));
            if (slot < 0 || slot >= inventory.getSize()) throw new IllegalStateException("Saved item slot outside container: " + slot);
            try { inventory.setItem(slot, item(saved, delivery)); }
            catch (IllegalArgumentException | UnsupportedOperationException error) { delivery.failed("inventory item " + saved.getString("id"), error); }
        }
        // Set lazy loot after touching the snapshot inventory, avoiding premature loot rolls.
        if (container instanceof Lootable lootable) {
            String name = tag.getString("LootTable");
            var key = name.isEmpty() ? null : NamespacedKey.fromString(name);
            var table = key == null ? null : Bukkit.getLootTable(key);
            if (!name.isEmpty() && table == null) delivery.unavailable("loot table " + name);
            lootable.setSeed(NbtMetadata.number(tag, "LootTableSeed", 0));
            lootable.setLootTable(table);
        } else if (!tag.getString("LootTable").isEmpty()) delivery.unavailable("loot table on " + tag.getString("id"));
        if (!container.update(true, false)) throw new IllegalStateException("Could not publish container metadata");
    }

    private static ItemStack item(CompoundBinaryTag saved, MetadataDelivery delivery) {
        String id = saved.getString("id");
        var components = saved.getCompound("components");
        var arguments = new StringJoiner(",", "[", "]");
        for (String key : components.keySet()) {
            if (key.startsWith("!")) arguments.add(key);
            else arguments.add(key + "=" + NbtMetadata.text(components.get(key)));
        }
        var result = Bukkit.getItemFactory().createItemStack(id + arguments);
        result.setAmount(Math.toIntExact(NbtMetadata.number(saved, "count", NbtMetadata.number(saved, "Count", 1))));
        if (!result.getType().getKey().toString().equals(id)) delivery.unavailable("inventory item identity " + id);
        if (result.getItemMeta() == null) {
            if (!components.keySet().isEmpty()) delivery.unavailable("components on an empty item");
            return result;
        }
        // This is an in-process observation, not storage of Bukkit's diagnostic format.
        var decoded = NbtMetadata.compound(result.getItemMeta().getAsString());
        for (String key : components.keySet()) {
            String difference = NbtMetadata.difference(components.get(key), decoded.get(key), "item component " + key);
            if (difference != null) delivery.unverified(difference);
        }
        return result;
    }
}
