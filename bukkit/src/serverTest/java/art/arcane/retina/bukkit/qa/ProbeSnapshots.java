package art.arcane.retina.bukkit.qa;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

final class ProbeSnapshots {
    private ProbeSnapshots() { }

    static void write(JavaPlugin probe) throws IOException {
        Path path = probe.getDataFolder().toPath().resolve("chunks.bin.gz"); Files.createDirectories(path.getParent());
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            output.writeUTF("retina.bukkit.probe.v1");
            output.writeInt(3);
            for (String name : List.of("world", "world_nether", "world_the_end")) {
                var world = Objects.requireNonNull(probe.getServer().getWorld(name), name);
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
                        output.writeUTF(probe.getServer().createBlockData(id).getAsString());
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
        probe.getLogger().info("RETINA_PROBE_PASS " + path.toAbsolutePath() + " bytes=" + Files.size(path));
    }
}
