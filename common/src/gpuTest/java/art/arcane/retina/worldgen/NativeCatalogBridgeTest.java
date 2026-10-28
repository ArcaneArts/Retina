package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeWorldDefinition;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;

/** Tests the real catalog publication and saved reopen using only the core JAR and JDK. */
public final class NativeCatalogBridgeTest {
    public static void main(String[] args) throws Exception {
        System.setProperty("retina.native.path", args[0]);
        try { Class.forName("net.minecraft.SharedConstants"); throw new AssertionError("Minecraft on catalog bridge classpath"); }
        catch (ClassNotFoundException expected) { }
        Path catalog = Path.of(args[1]), output = Files.createTempDirectory("retina-catalog-bridge-");
        try {
            for (String variant : new String[]{"normal", "normal-no-structures", "normal-bonus-chest", "normal-no-structures-bonus-chest"}) {
                long seed = variant.startsWith("normal-no-structures") ? Long.MAX_VALUE - 11 : Long.MIN_VALUE + 17;
                var root = output.resolve(variant);
                try (var world = NativeCatalog.create(catalog, variant, seed, root)) {
                    require(world.metadata().contains("\"seed\":" + seed), "seed belongs to materialized file");
                    require(world.metadata().contains("\"generate_structures\":" + !variant.startsWith("normal-no-structures")), "variant flags");
                    require(world.metadata().contains("\"bonus_chest\":" + variant.endsWith("-bonus-chest")), "bonus chest flag");
                    require(world.metadata().contains("minecraft:the_end") && world.metadata().contains("minecraft:the_nether"), "complete logical world");
                    try (var saved = NativeWorldDefinition.open(root.resolve("retina.dat"))) {
                        require(world.metadata().equals(saved.metadata()), "created handle matches the published file");
                        for (String dimension : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"})
                            require(world.diagnosticProfile(dimension).equals(saved.diagnosticProfile(dimension)), "file profile " + dimension);
                    }
                }
                byte[] before = Files.readAllBytes(root.resolve("retina.dat"));
                try { NativeCatalog.create(catalog, variant, seed + 1, root).close(); throw new AssertionError("Replaced existing definition"); }
                catch (IllegalStateException expected) { require(expected.getMessage().contains("retina.dat"), "actual destination error"); }
                System.setProperty("retina.qa.catalog.failMaterializer", "true");
                try (var reopened = NativeWorldDefinition.open(root.resolve("retina.dat"))) {
                    require(reopened.metadata().contains("\"seed\":" + seed), "saved reopen skips materializer");
                } finally { System.clearProperty("retina.qa.catalog.failMaterializer"); }
                require(Arrays.equals(before, Files.readAllBytes(root.resolve("retina.dat"))), "reopen preserves bytes");
            }
            var broken = output.resolve("malformed"); Files.createDirectories(broken);
            Files.writeString(broken.resolve("retina.dat"), "existing damaged world");
            try { NativeCatalog.create(catalog, "normal", 3, broken).close(); throw new AssertionError("Replaced malformed definition"); }
            catch (IllegalStateException expected) { }
            require(Files.readString(broken.resolve("retina.dat")).equals("existing damaged world"), "malformed present file preserved");
            System.out.println("QA_EVT {\"event\":\"jdk_only_catalog_materialization\",\"status\":\"pass\",\"variants\":4,\"dimensions\":3}");
        } finally {
            try (var paths = Files.walk(output)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
