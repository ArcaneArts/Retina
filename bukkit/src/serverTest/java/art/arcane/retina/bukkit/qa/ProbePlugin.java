package art.arcane.retina.bukkit.qa;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import art.arcane.retina.core.NativeWorldDefinition;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Disposable server-only observer. Uses public snapshots, never native or NMS classes. */
public final class ProbePlugin extends JavaPlugin {
    private final Map<String, String> locations = new HashMap<>();
    @Override public void onEnable() {
        for (var world : getServer().getWorlds()) world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        recordLocations();
        getLogger().info("RETINA_PROBE_RUNTIME native_override=" + System.getProperty("retina.native.path", "none"));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("retina.probe")) return true;
        try {
            if (args.length != 0 && args[0].equals("create-nether")) {
                create("retina_probe_nether", Long.MAX_VALUE - 37, World.Environment.NETHER, "nether").getChunkAt(-1, -1);
                getLogger().info("RETINA_PROBE_NETHER_PASS seed=" + (Long.MAX_VALUE - 37)); return true;
            }
            if (args.length != 0 && args[0].equals("imported")) {
                long seed = Long.parseLong(args[1]);
                var world = create("retina_probe_imported", seed, World.Environment.NORMAL, "overworld");
                world.getChunkAt(-1, -1);
                for (int y : new int[]{-64, 80}) {
                    if (world.getBlockAt(-16, y, -16).getType() != org.bukkit.Material.AIR
                            || !world.getBiome(-16, y, -16).getKey().toString().equals("minecraft:forest"))
                        throw new IllegalStateException("Imported cropped definition lost fitted storage/biomes");
                }
                getLogger().info("RETINA_PROBE_IMPORTED_PASS seed=" + seed); return true;
            }
            if (args.length != 0 && args[0].equals("bad-geometry")) {
                try {
                    create("retina_probe_bad_geometry", Long.MIN_VALUE + 41, World.Environment.NETHER, "overworld");
                    throw new AssertionError("Incompatible host geometry was accepted");
                } catch (RuntimeException expected) {
                    if (!causes(expected).contains("bounds")) throw expected;
                    getLogger().info("RETINA_PROBE_GEOMETRY_FAILURE_PASS"); return true;
                }
            }
            if (args.length != 0 && args[0].equals("disable-retina")) {
                var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
                var definitions = definitions(retina).values().stream().toList();
                getServer().getPluginManager().disablePlugin(retina);
                for (var definition : definitions) requireClosed(definition);
                getLogger().info("RETINA_PROBE_DISABLE_PASS definitions=" + definitions.size()); return true;
            }
            if (args.length != 0 && args[0].equals("metadata-scene")) {
                var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
                var world = Objects.requireNonNull(new WorldCreator("retina_metadata_scene").seed(43).environment(World.Environment.THE_END)
                        .generateStructures(false).generator(new MetadataScene(retina)).createWorld());
                world.getChunkAt(0, 0); MetadataScene.verify(world); world.save();
                getLogger().info("RETINA_PROBE_METADATA_BLOCKS_PASS");
                getServer().getScheduler().runTaskLater(this, () -> {
                    MetadataScene.verifyEntities(world); world.save(); getLogger().info("RETINA_PROBE_METADATA_ENTITIES_PASS");
                }, 2);
                return true;
            }
            if (args.length != 0 && args[0].equals("create-end")) { createEnd(); return true; }
            if (args.length != 0 && args[0].equals("reopen-end")) {
                var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
                var definition = definitions(retina).entrySet().stream().filter(e -> e.getKey().getFileName().toString().equals("retina_probe_end")).findFirst().orElseThrow().getValue();
                var world = Objects.requireNonNull(getServer().getWorld("retina_probe_end"));
                var generator = world.getGenerator();
                var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
                var work = new ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 32; i++) {
                    int x = (i % 4 - 2) * 16, z = (i / 4 - 4) * 16;
                    work.add(executor.submit(() -> generator.getBaseHeight(world, new Random(0), x, z, HeightMap.WORLD_SURFACE)));
                }
                if (!getServer().unloadWorld("retina_probe_end", true)) throw new IllegalStateException("Could not unload the probe End");
                requireClosed(definition);
                executor.shutdown();
                if (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Native callbacks did not drain");
                int completed = 0, rejected = 0;
                for (var job : work) try { job.get(); completed++; }
                catch (java.util.concurrent.ExecutionException error) {
                    String cause = causes(error);
                    if (!(error.getCause() instanceof IllegalStateException) || !(cause.contains("unloading") || cause.contains("closed"))) throw error;
                    rejected++;
                }
                System.setProperty("retina.qa.catalog.failMaterializer", "true");
                createEnd();
                try { generator.getBaseHeight(world, new Random(0), 0, 0, HeightMap.WORLD_SURFACE); throw new AssertionError("Stale generator was admitted after recreation"); }
                catch (IllegalStateException expected) { if (!expected.getMessage().contains("earlier world lifetime")) throw expected; }
                getLogger().info("RETINA_PROBE_REOPEN_PASS materializer_disabled=true completed=" + completed + " rejected_after_unload=" + rejected);
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
        finally { recordLocations(); }
        return true;
    }

    private void recordLocations() {
        var encoder = Base64.getEncoder();
        for (var world : getServer().getWorlds()) {
            String folder = world.getWorldFolder().toPath().toAbsolutePath().normalize().toString(), key = world.getKey().toString();
            String value = folder + "\n" + key;
            if (value.equals(locations.put(world.getName(), value))) continue;
            getLogger().info("RETINA_PROBE_WORLD " + encoder.encodeToString(world.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + " "
                    + encoder.encodeToString(folder.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + " "
                    + encoder.encodeToString(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private void createEnd() {
        var world = create("retina_probe_end", 42, World.Environment.THE_END, "end");
        world.getChunkAt(2, 0).getEntities(); world.save();
        for (var entity : world.getEntities()) if (entity instanceof EnderCrystal crystal)
            getLogger().info("RETINA_PROBE_CRYSTAL " + crystal.getLocation() + " bottom=" + crystal.isShowingBottom()
                    + " invulnerable=" + crystal.isInvulnerable() + " beam=" + crystal.getBeamTarget());
        getLogger().info("RETINA_PROBE_END_PASS seed=" + world.getSeed());
    }

    private World create(String name, long seed, World.Environment environment, String id) {
        var retina = Objects.requireNonNull(getServer().getPluginManager().getPlugin("Retina"));
        var world = Objects.requireNonNull(new WorldCreator(name).seed(seed).environment(environment).generateStructures(false)
                .generator(retina.getDefaultWorldGenerator(name, id)).createWorld());
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        return world;
    }

    private static String causes(Throwable error) {
        var text = new StringBuilder(); for (Throwable next = error; next != null; next = next.getCause()) text.append(next.getMessage()).append('\n');
        return text.toString();
    }

    private static Map<Path, NativeWorldDefinition> definitions(org.bukkit.plugin.Plugin retina) throws ReflectiveOperationException {
        var repositoryField = retina.getClass().getDeclaredField("worlds"); repositoryField.setAccessible(true);
        var repository = repositoryField.get(retina);
        var ownersField = repository.getClass().getDeclaredField("owners"); ownersField.setAccessible(true);
        var owners = (Map<?, ?>) ownersField.get(repository);
        var result = new HashMap<Path, NativeWorldDefinition>();
        for (var entry : owners.entrySet()) {
            var definition = entry.getValue().getClass().getDeclaredField("definition"); definition.setAccessible(true);
            result.put((Path) entry.getKey(), (NativeWorldDefinition) definition.get(entry.getValue()));
        }
        return result;
    }
    private static void requireClosed(NativeWorldDefinition definition) {
        try { definition.request("minecraft:the_end", 0, 0); throw new AssertionError("Native owner survived unload/disable"); }
        catch (IllegalStateException expected) { if (!expected.getMessage().equals("World definition is closed")) throw expected; }
    }
}
