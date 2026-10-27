package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class MetadataDeliveryTest {
    @Test void modernSpawnerIdentityAndEmptyComponentsAreSupportedButOpaqueComponentsFail() {
        var delivery = new MetadataDelivery(Logger.getAnonymousLogger(), false);
        var tag = CompoundBinaryTag.builder().putString("id", "minecraft:mob_spawner").put("components", CompoundBinaryTag.empty()).build();
        var root = CompoundBinaryTag.builder().put("block_entities", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(tag))).build();
        assertDoesNotThrow(() -> delivery.validate(root));
        var opaque = CompoundBinaryTag.builder().put("components", CompoundBinaryTag.builder().putString("custom:opaque", "value").build()).build();
        assertThrows(IllegalStateException.class, () -> delivery.fields(opaque, java.util.Set.of(), "container"));
    }
    @Test void spawnerLightRangesRetainModernArraysAndLegacyForms() {
        for (String input : new String[]{"[I;2,12]", "[2,12]", "{min_inclusive:2,max_inclusive:12}"})
            assertArrayEquals(new int[]{2, 12}, SpawnerDelivery.lightRange(NbtMetadata.compound("{range:" + input + "}").get("range")));
        assertArrayEquals(new int[]{7, 7}, SpawnerDelivery.lightRange(IntBinaryTag.intBinaryTag(7)));
        assertThrows(IllegalStateException.class, () -> SpawnerDelivery.lightRange(IntArrayBinaryTag.intArrayBinaryTag(12, 2)));
    }

    @Test void actualMetadataIsRejectedBeforeBlockInstallation() {
        var delivery = new MetadataDelivery(Logger.getAnonymousLogger(), false);
        var tick = CompoundBinaryTag.builder().putString("i", "minecraft:lava").build();
        var queues = CompoundBinaryTag.builder().put("fluid_ticks", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(tick))).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> delivery.validate(queues)).getMessage().contains("fluid_ticks"));
        var chest = CompoundBinaryTag.builder().putString("id", "minecraft:trial_spawner").build();
        var entities = CompoundBinaryTag.builder().put("block_entities", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(chest))).build();
        assertTrue(assertThrows(IllegalStateException.class, () -> delivery.validate(entities)).getMessage().contains("minecraft:trial_spawner"));
        assertDoesNotThrow(() -> new MetadataDelivery(Logger.getAnonymousLogger(), true).validate(queues));
    }
}
