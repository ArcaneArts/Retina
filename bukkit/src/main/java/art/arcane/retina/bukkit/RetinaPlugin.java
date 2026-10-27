package art.arcane.retina.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

/** Public Bukkit entry point. Generator selection itself cannot fall back to vanilla. */
public final class RetinaPlugin extends JavaPlugin implements Listener {
    private WorldRepository worlds;

    @Override public void onLoad() {
        saveDefaultConfig();
        worlds = new WorldRepository(this);
    }

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Retina uses saved world definitions and bundled vanilla catalogs. Plugin hot reload is unsupported.");
    }

    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new RetinaGenerator(worlds, id, worlds.select(worldName));
    }

    @Override public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        return new RetinaGenerator(worlds, id, worlds.select(worldName)).provider();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(WorldUnloadEvent event) { worlds.unload(event.getWorld().getName()); }

    @Override public void onDisable() { if (worlds != null) worlds.close(); }
}
