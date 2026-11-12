package art.arcane.retina.bukkit.qa;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class Probe1201 extends JavaPlugin {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) ProbeSnapshots.write(this);
            else if (args[0].equals("imported")) imported(Long.parseLong(args[1]));
            else if (args[0].equals("metadata")) metadata();
            else return false;
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("Bukkit snapshot probe failed", error);
        }
    }

    private void metadata() throws ReflectiveOperationException {
        var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
        var world = Objects.requireNonNull(new WorldCreator("retina_metadata_scene").seed(42)
                .generator(new MetadataScene1201(retina)).createWorld());
        world.getChunkAt(0, 0);
        MetadataScene1201.verifyBlocks(world);
        getServer().getScheduler().runTaskLater(this, () -> {
            MetadataScene1201.verifyEntities(world);
            world.save();
            getLogger().info("RETINA_PROBE_METADATA_ENTITIES_PASS");
        }, 2);
    }

    private void imported(long seed) {
        var world = Objects.requireNonNull(new WorldCreator("retina_probe_imported").seed(seed)
                .environment(World.Environment.NORMAL).generator("Retina:overworld").createWorld());
        var generator = world.getGenerator();
        var chunk = world.getChunkAt(0, 0);
        var snapshot = chunk.getChunkSnapshot(false, true, false);
        boolean marker = false;
        for (int y = 4; y <= 24 && !marker; y++)
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                marker |= snapshot.getBlockType(x, y, z) == Material.EMERALD_BLOCK;
        if (!marker || snapshot.getBiome(0, 24, 0) != Biome.DESERT)
            throw new IllegalStateException("Imported datapack ore or biome is missing");
        if (!getServer().unloadWorld(world, true))
            throw new IllegalStateException("Could not unload imported definition");
        try {
            generator.getBaseHeight(world, new java.util.Random(0), 0, 0, org.bukkit.HeightMap.WORLD_SURFACE);
            throw new IllegalStateException("Generator admitted a callback after unload");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("unloading")) throw expected;
        }
        getLogger().info("RETINA_PROBE_IMPORTED_PASS");
    }
}
