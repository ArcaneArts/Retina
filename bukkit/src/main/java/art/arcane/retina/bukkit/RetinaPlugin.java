package art.arcane.retina.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Properties;

/** Public Bukkit entry point. Generator selection itself cannot fall back to vanilla. */
public final class RetinaPlugin extends JavaPlugin implements Listener {
    private WorldRepository worlds;
    private String minecraftVersion;

    @Override public void onLoad() {
        minecraftVersion = readMinecraftVersion();
        saveDefaultConfig();
        art.arcane.retina.core.NativeLibrary.configureDebug(getConfig().getBoolean("debug", false));
        boolean directx = getConfig().getBoolean("allow_directx", true);
        boolean metal = getConfig().getBoolean("allow_metal", true);
        boolean vulkan = getConfig().getBoolean("allow_vulkan", true);
        art.arcane.retina.core.NativeLibrary.allowGpuBackends(directx, metal, vulkan);
        worlds = new WorldRepository(this);
    }

    String minecraftVersion() { return minecraftVersion; }

    private String readMinecraftVersion() {
        try (var input = getResource("retina-bukkit.properties")) {
            if (input == null) throw new IllegalStateException("Missing Retina Bukkit build metadata");
            var metadata = new Properties();
            metadata.load(input);
            return metadata.getProperty("minecraftVersion");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read Retina Bukkit build metadata", error);
        }
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void save(WorldSaveEvent event) { worlds.save(event.getWorld().getName()); }

    @Override public void onDisable() { if (worlds != null) worlds.close(); }
}
