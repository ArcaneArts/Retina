package art.arcane.retina.bukkit.qa;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.HeightMap;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.foreign.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Actual CraftBukkit delivery timing, using fixed bytes or the live native chunk call. */
final class TransferProbe extends ChunkGenerator {
    private static volatile Object sink;
    private ChunkData target(World world) { return createChunkData(world); }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        var method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }
    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    static void run(JavaPlugin probe, Path input, boolean live) throws Exception {
        run(probe, input, live, Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Retina")).getClass().getClassLoader());
    }
    static void runReference(JavaPlugin probe, Path input, Path jar) throws Exception {
        var parent = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Retina")).getClass().getClassLoader();
        // Isolate only the implementation under test; Bukkit and NBT types stay shared.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("art.arcane.retina.bukkit.ChunkSnapshot") && !name.startsWith("art.arcane.retina.bukkit.ChunkSnapshot$"))
                    return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }) { run(probe, input, false, loader); }
    }
    private static void run(JavaPlugin probe, Path input, boolean live, ClassLoader loader) throws Exception {
        var retina = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("Retina"));
        var snapshot = loader.loadClass("art.arcane.retina.bukkit.ChunkSnapshot");
        var compound = loader.loadClass("art.arcane.retina.internal.kyori.adventure.nbt.CompoundBinaryTag");
        var constructor = snapshot.getDeclaredConstructor(byte[].class, int.class, int.class); constructor.setAccessible(true);
        var root = snapshot.getDeclaredField("root"); root.setAccessible(true);
        var install = method(snapshot, "install", ChunkData.class, Function.class);
        var height = method(snapshot, "height", HeightMap.class, int.class, int.class);
        var biome = method(snapshot, "biome", int.class, int.class, int.class);
        var metadata = loader.loadClass("art.arcane.retina.bukkit.MetadataDelivery");
        var deliveryConstructor = metadata.getDeclaredConstructor(java.util.logging.Logger.class, boolean.class);
        deliveryConstructor.setAccessible(true); var delivery = deliveryConstructor.newInstance(probe.getLogger(), false);
        var validate = method(metadata, "validate", compound);
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        var factory = new TransferProbe();
        List<Path> fixtures;
        try (var paths = Files.list(input)) { fixtures = paths.filter(p -> p.toString().endsWith(".nbt")).sorted().toList(); }
        if (fixtures.isEmpty()) throw new IllegalArgumentException("No captured NBT fixtures in " + input);
        for (var fixture : fixtures) {
            String name = fixture.getFileName().toString();
            String worldName = name.startsWith("overworld_") ? "world" : name.startsWith("the_nether_") ? "world_nether" : "world_the_end";
            var world = Objects.requireNonNull(Bukkit.getWorld(worldName));
            int minimum = world.getMinHeight(), size = world.getMaxHeight() - minimum;
            var states = new HashMap<String, BlockData>();
            Function<String, BlockData> bind = key -> states.computeIfAbsent(key, Bukkit::createBlockData);
            Object definition = null;
            Method generate = null;
            String dimension = "minecraft:" + (worldName.equals("world") ? "overworld" : worldName.equals("world_nether") ? "the_nether" : "the_end");
            String[] coordinates = name.substring(0, name.length() - 4).split("_");
            int chunkX = Integer.parseInt(coordinates[coordinates.length - 2]);
            int chunkZ = Integer.parseInt(coordinates[coordinates.length - 1]);
            if (live) {
                var bindings = (Map<?, ?>) field(field(retina, "worlds"), "bindings");
                var context = field(Objects.requireNonNull(bindings.get(worldName)), "dimension");
                definition = field(field(context, "owner"), "definition");
                generate = definition.getClass().getMethod("chunk", String.class, int.class, int.class);
            }
            byte[] bytes = live ? (byte[]) generate.invoke(definition, dimension, chunkX, chunkZ) : Files.readAllBytes(fixture);
            var reference = constructor.newInstance(bytes, minimum, size);
            var target = factory.target(world); install.invoke(reference, target, bind);
            var digest = MessageDigest.getInstance("SHA-256");
            for (int y = minimum; y < minimum + size; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                digest.update((target.getBlockData(x, y, z).getAsString() + "\n").getBytes(StandardCharsets.UTF_8));
            for (int y = minimum; y < minimum + size; y += 4) for (int z = 0; z < 16; z += 4) for (int x = 0; x < 16; x += 4)
                digest.update((biome.invoke(reference, x, y, z) + "\n").getBytes(StandardCharsets.UTF_8));
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                digest.update((height.invoke(reference, HeightMap.WORLD_SURFACE, x, z) + "\n").getBytes(StandardCharsets.UTF_8));
            probe.getLogger().info("RETINA_TRANSFER_PARITY " + name + " " + HexFormat.of().formatHex(digest.digest()));
            try (var arena = Arena.ofConfined()) {
                var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
                for (int round = -2; round < 5; round++) {
                    long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long start = System.nanoTime();
                    int iterations = live ? 5 : 100;
                    for (int i = 0; i < iterations; i++) {
                        byte[] transferred = live ? (byte[]) generate.invoke(definition, dimension, chunkX, chunkZ)
                                : nativeBytes.toArray(ValueLayout.JAVA_BYTE);
                        var chunk = constructor.newInstance(transferred, minimum, size);
                        validate.invoke(delivery, root.get(chunk));
                        var data = factory.target(world); install.invoke(chunk, data, bind);
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                            sink = height.invoke(chunk, HeightMap.WORLD_SURFACE, x, z);
                        sink = data;
                    }
                    long elapsed = System.nanoTime() - start;
                    long allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                    if (round >= 0) probe.getLogger().info("RETINA_TRANSFER_SAMPLE " + name + " " + round + " " + elapsed / (double) iterations + " " + allocated / (double) iterations);
                }
            }
            for (int round = -2; round < 5; round++) {
                long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    var data = factory.target(world); install.invoke(reference, data, bind); sink = data;
                }
                long elapsed = System.nanoTime() - start;
                long allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                if (round >= 0) probe.getLogger().info("RETINA_INSTALL_SAMPLE " + name + " " + round + " " + elapsed / 100.0 + " " + allocated / 100.0);
            }
        }
        probe.getLogger().info("RETINA_TRANSFER_PASS");
    }
}
