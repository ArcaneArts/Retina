package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NbtMetadataTest {
    @Test void requiredDataComparisonPreservesFullWidthNumbersAndFindsDiscardedFields() {
        var source = NbtMetadata.compound("{seed:-9223372036854775767L,nested:{retained:[I;1,2,3]}}");
        assertNull(NbtMetadata.difference(source, NbtMetadata.compound(NbtMetadata.text(source)), "source"));
        assertNotNull(NbtMetadata.difference(source.get("seed"), DoubleBinaryTag.doubleBinaryTag((double) (-9223372036854775767L)), "seed"));
        var changed = NbtMetadata.compound("{seed:-9223372036854775767L,nested:{}}");
        assertEquals("source.nested.retained is absent after public API decoding", NbtMetadata.difference(source, changed, "source"));
    }
}
