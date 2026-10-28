package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.nbt.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Repackages an existing snapshot without recapturing Minecraft registries. */
public final class WorldCatalogRepack {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        CompoundTag catalog = NbtIo.readCompressed(input.resolve(WorldCatalogCompiler.NAME), NbtAccounter.unlimitedHeap());
        CompoundTag packed = WorldCatalogCompiler.pack(catalog);
        Path file = WorldDefinitionFile.writeNew(output, WorldCatalogCompiler.NAME, packed);
        CompoundTag reread = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        for (String name : catalog.getCompoundOrEmpty("variants").keySet()) {
            byte[] bytes = reread.getCompoundOrEmpty("compressed_variants").getByteArray(name).orElseThrow();
            try (var stream = new java.io.ByteArrayInputStream(bytes)) {
                if (!NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap()).equals(
                        catalog.getCompoundOrEmpty("variants").getCompoundOrEmpty(name)))
                    throw new IllegalStateException("Repacked variant differs: " + name);
            }
        }
        var manifest = JsonParser.parseString(Files.readString(input.resolve("manifest.json"))).getAsJsonObject();
        manifest.addProperty("catalog_version", WorldCatalogCompiler.CONTAINER_VERSION);
        manifest.addProperty("compressed_bytes", Files.size(file));
        manifest.addProperty("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        Files.writeString(output.resolve("manifest.json"), new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + "\n");
        System.out.println("Repacked and verified every variant: " + file);
    }
}
