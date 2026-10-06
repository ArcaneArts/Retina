package art.arcane.retina.worldgen;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Decode terrain sections by their world Y, retaining checks for lighting padding. */
final class McaTestSections {
    private McaTestSections() { }

    static List<CompoundTag> terrain(CompoundTag chunk, int minY, int height) {
        var terrain = new CompoundTag[height / 16];
        var seen = new HashSet<Integer>();
        int first = Math.floorDiv(minY, 16), end = first + terrain.length;
        for (var value : chunk.getListOrEmpty("sections")) {
            var section = (CompoundTag) value;
            int y = section.getByte("Y").orElseThrow();
            require(seen.add(y), "duplicate MCA section Y=" + y);
            if (y < first || y >= end) {
                require(y == first - 1 || y == end, "lighting padding is adjacent to terrain: " + y);
                require(!section.contains("block_states") && !section.contains("biomes"), "lighting padding has no terrain palettes");
                // Uniform zero light is represented by omitting its array.
                for (String key : List.of("SkyLight", "BlockLight")) {
                    if (section.contains(key)) require(section.getByteArray(key).orElseThrow().length == 2048, "packed light array length");
                }
            } else {
                require(section.contains("block_states") && section.contains("biomes"), "terrain palettes exist at Y=" + y);
                terrain[y - first] = section;
            }
        }
        for (int i = 0; i < terrain.length; i++) require(terrain[i] != null, "missing terrain section Y=" + (first + i));
        return Arrays.asList(terrain);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
