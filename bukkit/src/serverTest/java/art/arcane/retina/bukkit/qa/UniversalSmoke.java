package art.arcane.retina.bukkit.qa;

import art.arcane.retina.core.NativeLibrary;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** One chunk per dimension, checking the installed universal artifact and saved edits. */
final class UniversalSmoke {
    static void run(JavaPlugin probe, boolean reopened) throws Exception {
        if (System.getProperty("retina.native.path") != null) throw new IllegalStateException("Native override is active");
        var retina = Objects.requireNonNull(probe.getServer().getPluginManager().getPlugin("Retina"));
        if (!retina.isEnabled()) throw new IllegalStateException("Retina is not enabled");
        Path jar = Path.of(retina.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        for (String name : List.of("world", "world_nether", "world_the_end")) {
            var world = Objects.requireNonNull(probe.getServer().getWorld(name), name);
            if (world.getGenerator() == null || !world.getGenerator().getClass().getName()
                    .equals("art.arcane.retina.bukkit.RetinaGenerator")) {
                throw new IllegalStateException("Wrong generator in " + name);
            }
            var snapshot = world.getChunkAt(0, 0).getChunkSnapshot(false, true, false);
            int solid = 0;
            for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                if (!snapshot.getBlockType(8, y, 8).isAir()) solid++;
            }
            if (solid == 0) throw new IllegalStateException("Expected nonempty terrain at origin in " + name);
            var edit = world.getBlockAt(8, world.getMaxHeight() - 8, 8);
            if (reopened && edit.getType() != Material.EMERALD_BLOCK) throw new IllegalStateException("Saved edit lost in " + name);
            edit.setType(Material.EMERALD_BLOCK, false);
            world.save();
            probe.getLogger().info("RETINA_UNIVERSAL_DIMENSION_PASS world=" + name + " nonair=" + solid + " reopened=" + reopened);
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        probe.getLogger().info("RETINA_UNIVERSAL_BUKKIT_PASS jar_sha256=" + hash + " native_sha256="
                + HexFormat.of().formatHex(NativeLibrary.imageDigest()) + " reopened=" + reopened);
    }
}
