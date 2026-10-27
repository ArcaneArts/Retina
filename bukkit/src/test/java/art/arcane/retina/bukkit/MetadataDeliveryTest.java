package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MetadataDeliveryTest {
    @Test void actualMetadataIsRejectedBeforeBlockInstallation() {
        var delivery = new MetadataDelivery(Logger.getAnonymousLogger(), false);
        var tick = CompoundBinaryTag.builder().putString("i", "minecraft:lava").build();
        var queues = CompoundBinaryTag.builder().put("fluid_ticks", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(tick))).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> delivery.validate(queues)).getMessage().contains("fluid_ticks"));
        var chest = CompoundBinaryTag.builder().putString("id", "minecraft:chest").build();
        var entities = CompoundBinaryTag.builder().put("block_entities", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(chest))).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> delivery.validate(entities)).getMessage().contains("minecraft:chest"));
        assertDoesNotThrow(() -> new MetadataDelivery(Logger.getAnonymousLogger(), true).validate(queues));
    }
}
