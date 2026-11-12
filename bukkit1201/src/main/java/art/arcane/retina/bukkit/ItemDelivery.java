package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.util.Set;

final class ItemDelivery {
    private ItemDelivery() { }

    static void validate(CompoundBinaryTag tag, MetadataDelivery delivery) {
        delivery.fields(tag, Set.of("Slot", "id", "Count", "count", "tag"), "Bukkit 1.20.1 inventory item");
    }

    static ItemStack create(CompoundBinaryTag saved, MetadataDelivery delivery) {
        String id = saved.getString("id");
        var tag = saved.getCompound("tag");
        var item = Bukkit.getItemFactory().createItemStack(tag.isEmpty() ? id : id + NbtMetadata.text(tag));
        item.setAmount(Math.toIntExact(NbtMetadata.number(saved, "Count", NbtMetadata.number(saved, "count", 1))));
        if (!item.getType().getKey().toString().equals(id)) delivery.unavailable("inventory item identity " + id);
        if (!tag.isEmpty() && item.getItemMeta() != null) {
            var observed = NbtMetadata.compound(item.getItemMeta().getAsString());
            String difference = NbtMetadata.difference(tag, observed, "Bukkit 1.20.1 item tag");
            if (difference != null) delivery.unverified(difference);
        }
        return item;
    }
}
