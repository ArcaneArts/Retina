package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.util.Set;
import java.util.StringJoiner;

final class ItemDelivery {
    private ItemDelivery() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        delivery.fields(tag, Set.of("Slot", "id", "count", "Count", "components"), "inventory item");
    }

    static ItemStack create(CompoundBinaryTag saved, MetadataDelivery delivery) {
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
