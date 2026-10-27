package art.arcane.retina.bukkit.qa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.EnderCrystal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Disposable server-only observer. Uses public snapshots, never native or NMS classes. */
public final class ProbePlugin extends JavaPlugin {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("retina.probe")) return true;
        try {
            if (args.length != 0 && args[0].equals("create-end")) { createEnd(); return true; }
            if (args.length != 0 && args[0].equals("reopen-end")) {
                if (!getServer().unloadWorld("retina_probe_end", true)) throw new IllegalStateException("Could not unload the probe End");
                System.setProperty("retina.qa.catalog.failMaterializer", "true");
                createEnd();
                getLogger().info("RETINA_PROBE_REOPEN_PASS materializer_disabled=true");
                return true;
            }
            Path path = getDataFolder().toPath().resolve("chunks.bin.gz"); Files.createDirectories(path.getParent());
            try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
                output.writeUTF("retina.bukkit.probe.v1");
                output.writeInt(3);
                for (String name : List.of("world", "world_nether", "world_the_end")) {
                    var world = Objects.requireNonNull(getServer().getWorld(name), name);
                    output.writeUTF(name); output.writeLong(world.getSeed());
                    output.writeInt(world.getMinHeight()); output.writeInt(world.getMaxHeight()); output.writeInt(4);
                    for (int[] at : new int[][]{{-1, -1}, {0, 0}, {1, 1}, {2, 0}}) {
                        var snapshot = world.getChunkAt(at[0], at[1]).getChunkSnapshot(false, true, false);
                        output.writeInt(at[0]); output.writeInt(at[1]);
                        var states = new ArrayList<String>(); var ids = new HashMap<String, Integer>();
                        int[] blocks = new int[(world.getMaxHeight() - world.getMinHeight()) * 256];
                        int cursor = 0;
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            String state = snapshot.getBlockData(x, y, z).getAsString();
                            blocks[cursor++] = ids.computeIfAbsent(state, key -> { states.add(key); return states.size() - 1; });
                        }
                        output.writeInt(states.size());
                        for (String state : states) {
                            output.writeUTF(state);
                            String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
                            output.writeUTF(getServer().createBlockData(id).getAsString());
                        }
                        output.writeInt(blocks.length); for (int block : blocks) output.writeInt(block);
                        var biomes = new ArrayList<String>(); ids.clear();
                        int[] biomeIds = new int[(world.getMaxHeight() - world.getMinHeight()) * 4]; cursor = 0;
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4) {
                            String biome = snapshot.getBiome(x, y, z).getKey().toString();
                            biomeIds[cursor++] = ids.computeIfAbsent(biome, key -> { biomes.add(key); return biomes.size() - 1; });
                        }
                        output.writeInt(biomes.size()); for (String biome : biomes) output.writeUTF(biome);
                        output.writeInt(biomeIds.length); for (int biome : biomeIds) output.writeInt(biome);
                    }
                }
            }
            getLogger().info("RETINA_PROBE_PASS " + path.toAbsolutePath() + " bytes=" + Files.size(path));
        } catch (Exception error) { throw new IllegalStateException("Bukkit snapshot probe failed", error); }
        return true;
    }

    private void createEnd() {
        var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
        var world = Objects.requireNonNull(new WorldCreator("retina_probe_end").seed(42).environment(World.Environment.THE_END)
                .generateStructures(false).generator(retina.getDefaultWorldGenerator("retina_probe_end", "end")).createWorld());
        world.getChunkAt(2, 0); world.save();
        for (var entity : world.getEntities()) if (entity instanceof EnderCrystal crystal)
            getLogger().info("RETINA_PROBE_CRYSTAL " + crystal.getLocation() + " bottom=" + crystal.isShowingBottom()
                    + " invulnerable=" + crystal.isInvulnerable() + " beam=" + crystal.getBeamTarget());
        getLogger().info("RETINA_PROBE_END_PASS seed=" + world.getSeed());
    }
}
