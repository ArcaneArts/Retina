import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeLibrary;
import art.arcane.retina.core.NativeWorldDefinition;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs against the actual distribution on a JDK-only classpath, without a GPU. */
public class UniversalNativeSmoke {
    public static void main(String[] args) throws Exception {
        if (System.getProperty("retina.native.path") != null) throw new AssertionError("Use the packaged native image");
        Path root = Files.createTempDirectory("retina-release-smoke-");
        try {
            for (String snapshot : List.of("vanilla-binary", "vanilla-recompiled")) {
                Path catalog = root.resolve(snapshot + ".dat");
                String resource = "/catalogs/" + args[0] + "/" + snapshot + "/retina-catalog.dat";
                try (var input = NativeCatalog.class.getResourceAsStream(resource)) {
                    if (input == null) throw new AssertionError(resource);
                    Files.copy(input, catalog);
                }
                Path world = root.resolve(snapshot);
                Files.createDirectories(world);
                String metadata;
                try (var definition = NativeCatalog.create(catalog, "normal", -123456789012345L, world)) {
                    metadata = definition.metadata();
                    for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
                        if (!metadata.contains(dimension)) throw new AssertionError("Missing " + dimension);
                    }
                    if (!metadata.contains("-123456789012345")) throw new AssertionError("Wrong seed: " + metadata);
                }
                byte[] saved = Files.readAllBytes(world.resolve("retina.dat"));
                try (var reopened = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
                    if (!metadata.equals(reopened.metadata())) throw new AssertionError("Reopened metadata changed");
                }
                if (!Arrays.equals(saved, Files.readAllBytes(world.resolve("retina.dat")))) throw new AssertionError("Saved definition changed");
                System.out.println("CATALOG_PASS " + snapshot + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved)));
            }
            System.out.println("PACKAGED_NATIVE_PASS " + HexFormat.of().formatHex(NativeLibrary.imageDigest()));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
