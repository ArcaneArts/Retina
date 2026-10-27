package art.arcane.retina.bukkit;

import net.kyori.adventure.nbt.*;
import org.bukkit.HeightMap;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.block.data.BlockData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Native chunk NBT decoded with a regular Java library; no Minecraft classes. */
final class ChunkSnapshot {
    record Section(int y, String[] states, long[] blocks, String[] biomes, long[] biomeData) { }
    final CompoundBinaryTag root;
    private final Map<Integer, Section> sections;
    private final int minimum, height;

    ChunkSnapshot(byte[] bytes, int minimum, int height) {
        this.minimum = minimum; this.height = height;
        try { root = BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(bytes), BinaryTagIO.Compression.NONE); }
        catch (IOException error) { throw new IllegalStateException("Invalid native chunk NBT", error); }
        var decoded = new HashMap<Integer, Section>();
        for (var raw : root.getList("sections", BinaryTagTypes.COMPOUND)) {
            var section = (CompoundBinaryTag) raw;
            if (!section.contains("block_states")) continue; // Boundary lighting sections have no blocks.
            var blocks = section.getCompound("block_states");
            var palette = blocks.getList("palette");
            var names = new String[palette.size()];
            for (int i = 0; i < names.length; i++) {
                var entry = palette.get(i);
                if (entry instanceof StringBinaryTag text) names[i] = text.value();
                else if (entry instanceof CompoundBinaryTag compound) names[i] = state(compound);
                else throw new IllegalStateException("Invalid native block palette entry " + entry.type());
            }
            if (names.length == 0) throw new IllegalStateException("Native block palette is empty");
            var biomes = section.getCompound("biomes"); var biomePalette = biomes.getList("palette", BinaryTagTypes.STRING);
            var biomeNames = new String[biomePalette.size()];
            for (int i = 0; i < biomeNames.length; i++) biomeNames[i] = biomePalette.getString(i);
            int y = section.getByte("Y") * 16;
            decoded.put(y, new Section(y, names, blocks.getLongArray("data", new long[0]), biomeNames,
                    biomes.getLongArray("data", new long[0])));
        }
        sections = Map.copyOf(decoded);
    }

    static String state(CompoundBinaryTag state) {
        String id = state.getString("id");
        if (id.isEmpty()) throw new IllegalStateException("Native block palette entry has no canonical id");
        var properties = new TreeMap<String, String>();
        for (var property : state.getCompound("properties")) properties.put(property.getKey(), ((StringBinaryTag) property.getValue()).value());
        if (properties.isEmpty()) return id;
        var values = new StringJoiner(",", id + "[", "]"); properties.forEach((key, value) -> values.add(key + "=" + value));
        return values.toString();
    }

    void install(ChunkGenerator.ChunkData target, Function<String, BlockData> bind) {
        if (target.getMinHeight() > minimum || target.getMaxHeight() < minimum + height)
            throw new IllegalStateException("ChunkData bounds " + target.getMinHeight() + ".." + target.getMaxHeight()
                    + " cannot contain saved storage " + minimum + ".." + (minimum + height));
        for (var section : sections.values()) {
            var states = Arrays.stream(section.states()).map(bind).toArray(BlockData[]::new);
            if (states.length == 1) {
                target.setRegion(0, section.y(), 0, 16, section.y() + 16, 16, states[0]);
            } else for (int i = 0; i < 4096; i++) {
                int state = packed(section.blocks(), i, bits(states.length, 4));
                target.setBlock(i & 15, section.y() + (i >>> 8), (i >>> 4) & 15, states[state]);
            }
        }
    }

    String biome(int x, int y, int z) {
        var section = sections.get(Math.floorDiv(y, 16) * 16);
        if (section == null) throw new IllegalStateException("Saved chunk has no biome section at " + y);
        int index = ((Math.floorMod(y, 16) >> 2) * 4 + ((z & 15) >> 2)) * 4 + ((x & 15) >> 2);
        return section.biomes()[section.biomes().length == 1 ? 0 : packed(section.biomeData(), index, bits(section.biomes().length, 1))];
    }

    int height(HeightMap type, int x, int z) {
        var maps = root.getCompound("Heightmaps");
        if (!maps.contains(type.name())) throw new IllegalStateException("Saved chunk lacks heightmap " + type);
        return minimum + packed(maps.getLongArray(type.name()), (z & 15) * 16 + (x & 15), 32 - Integer.numberOfLeadingZeros(height));
    }

    private static int bits(int count, int minimum) { return Math.max(minimum, 32 - Integer.numberOfLeadingZeros(count - 1)); }
    private static int packed(long[] data, int index, int bits) {
        int per = 64 / bits;
        return (int) ((data[index / per] >>> ((index % per) * bits)) & ((1L << bits) - 1));
    }
}
