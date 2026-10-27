package art.arcane.retina.bukkit.qa;

import net.kyori.adventure.nbt.*;
import java.nio.file.*;
import java.util.ArrayList;

/** Explicit integration-test transforms of actual generated/mod-created definitions. */
public final class DefinitionFixtures {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output);
        CompoundBinaryTag root;
        try (var input = Files.newInputStream(source)) { root = BinaryTagIO.unlimitedReader().read(input, BinaryTagIO.Compression.GZIP); }
        Files.writeString(output.resolve("seed.txt"), Long.toString(root.getLong("seed")));
        var shared = root.getCompound("shared_types");
        var overworld = root.getCompound("dimensions").getCompound("minecraft:overworld");
        var materials = new ArrayList<BinaryTag>(); shared.getList("materials", BinaryTagTypes.COMPOUND).forEach(materials::add);
        int material = overworld.getIntArray("material_palette")[1];
        materials.set(material, replace((CompoundBinaryTag) materials.get(material), "id", StringBinaryTag.stringBinaryTag("retina:missing_material")));
        write(output.resolve("missing-material.dat"), replace(root, "shared_types", replace(shared, "materials", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, materials))));
        String biome = shared.getList("biomes", BinaryTagTypes.STRING).getString(overworld.getIntArray("biome_palette")[0]);
        // Keep profile/shared canonical references consistent so the actual host lookup is exercised.
        write(output.resolve("missing-biome.dat"), (CompoundBinaryTag) rename(root, biome, "retina:missing_biome"));
        write(output.resolve("original.dat"), root);
    }
    private static BinaryTag rename(BinaryTag tag, String from, String to) {
        if (tag instanceof StringBinaryTag value) return value.value().equals(from) ? StringBinaryTag.stringBinaryTag(to) : tag;
        if (tag instanceof CompoundBinaryTag compound) {
            var result = CompoundBinaryTag.builder(); compound.forEach(e -> result.put(e.getKey(), rename(e.getValue(), from, to))); return result.build();
        }
        if (tag instanceof ListBinaryTag list) {
            var result = new ArrayList<BinaryTag>(); list.forEach(value -> result.add(rename(value, from, to)));
            return ListBinaryTag.listBinaryTag(list.elementType(), result);
        }
        return tag;
    }
    private static CompoundBinaryTag replace(CompoundBinaryTag original, String key, BinaryTag value) {
        var builder = CompoundBinaryTag.builder(); original.forEach(e -> builder.put(e.getKey(), e.getValue()));
        return builder.put(key, value).build();
    }
    private static void write(Path path, CompoundBinaryTag root) throws Exception {
        try (var output = Files.newOutputStream(path)) { BinaryTagIO.writer().write(root, output, BinaryTagIO.Compression.GZIP); }
    }
}
