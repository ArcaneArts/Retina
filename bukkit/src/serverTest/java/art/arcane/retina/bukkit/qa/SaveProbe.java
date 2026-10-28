package art.arcane.retina.bukkit.qa;

import org.bukkit.World;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual listener/callback timing with unchanged real biome answers in a disposable server. */
final class SaveProbe {
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static Method method(Object owner, String name, Class<?>... arguments) throws ReflectiveOperationException {
        Method value = owner.getClass().getDeclaredMethod(name, arguments); value.setAccessible(true); return value;
    }
    static void withPending(JavaPlugin probe, World world, Runnable close) throws Exception {
        Plugin retina = Objects.requireNonNull(probe.getServer().getPluginManager().getPlugin("Retina"));
        Objects.requireNonNull(world.getBiomeProvider()).getBiome(world, 0, 64, 0);
        var repository = field(retina, "worlds");
        var bindings = (Map<?, ?>) field(repository, "bindings");
        var dimension = field(Objects.requireNonNull(bindings.get(world.getName())), "dimension");
        var cache = field(field(dimension, "biomeTiles"), "persisted");
        var tiles = (Map<?, ?>) field(cache, "tiles");
        Object tile; int[] values;
        synchronized (cache) {
            var first = tiles.entrySet().iterator().next(); tile = first.getKey(); values = ((int[]) first.getValue()).clone();
        }
        Class<?> type = retina.getClass().getClassLoader().loadClass("art.arcane.retina.bukkit.CoalescedSave");
        Field writers = type.getDeclaredField("WRITERS"); writers.setAccessible(true);
        var executor = (Executor) writers.get(null);
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Probe release timeout"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        };
        executor.execute(block); executor.execute(block);
        try {
            if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writers did not enter probe");
            method(cache, "put", tile.getClass(), int[].class).invoke(cache, tile, values);
            retina.getClass().getMethod("save", WorldSaveEvent.class).invoke(retina, new WorldSaveEvent(world));
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(release::countDown);
            close.run();
            if (release.getCount() != 0) throw new AssertionError("Close returned before the pending writer could run");
            synchronized (cache) { if (!tiles.isEmpty()) throw new AssertionError("Closed cache retained tiles"); }
            probe.getLogger().info("RETINA_PENDING_SAVE_CLOSE_PASS world=" + world.getName());
        } finally { release.countDown(); }
    }
    static void run(JavaPlugin probe) throws Exception {
        Plugin retina = Objects.requireNonNull(probe.getServer().getPluginManager().getPlugin("Retina"));
        World world = Objects.requireNonNull(probe.getServer().getWorld("world"));
        var repository = field(retina, "worlds");
        var bindings = (Map<?, ?>) field(repository, "bindings");
        var dimension = field(Objects.requireNonNull(bindings.get("world")), "dimension");
        var cache = field(field(dimension, "biomeTiles"), "persisted");
        var tiles = (Map<?, ?>) field(cache, "tiles");
        Object tile; int[] values; int count;
        synchronized (cache) {
            count = tiles.size();
            var first = tiles.entrySet().iterator().next(); tile = first.getKey(); values = ((int[]) first.getValue()).clone();
        }
        var put = method(cache, "put", tile.getClass(), int[].class);
        var flush = method(cache, "save");
        var callback = retina.getClass().getMethod("save", WorldSaveEvent.class);
        int x = (int) field(tile, "x") * 32, y = (int) field(tile, "y") * 4, z = (int) field(tile, "z") * 32;
        var provider = Objects.requireNonNull(world.getBiomeProvider());
        var expected = provider.getBiome(world, x, y, z);
        try (var queries = Executors.newSingleThreadExecutor()) {
            for (int trial = -5; trial < 20; trial++) {
                put.invoke(cache, tile, values.clone());
                var ready = new CountDownLatch(1);
                var reads = queries.submit(() -> {
                    long worst = 0;
                    ready.countDown();
                    for (int i = 0; i < 1000; i++) {
                        long started = System.nanoTime(); var biome = provider.getBiome(world, x, y, z);
                        worst = Math.max(worst, System.nanoTime() - started);
                        if (biome != expected) throw new AssertionError("Save changed biome answer");
                    }
                    return worst;
                });
                if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Query worker did not start");
                long started = System.nanoTime(); callback.invoke(retina, new WorldSaveEvent(world));
                long callbackNanos = System.nanoTime() - started;
                long worst = reads.get(10, TimeUnit.SECONDS);
                flush.invoke(cache); // Compare completed checkpoints, never just queue throughput.
                put.invoke(cache, tile, values.clone());
                started = System.nanoTime(); world.save(); long worldNanos = System.nanoTime() - started;
                flush.invoke(cache);
                probe.getLogger().info("RETINA_SAVE_SAMPLE {\"trial\":" + trial + ",\"tiles\":" + count
                        + ",\"callback_ms\":" + callbackNanos / 1e6 + ",\"worst_read_ms\":" + worst / 1e6
                        + ",\"world_save_ms\":" + worldNanos / 1e6 + "}");
            }
        }
        probe.getLogger().info("RETINA_SAVE_PASS");
    }
}
