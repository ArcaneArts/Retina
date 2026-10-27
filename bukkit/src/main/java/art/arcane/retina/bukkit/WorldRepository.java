package art.arcane.retina.bukkit;

import art.arcane.retina.core.NativeCatalog;
import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.WorldInfo;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** One file/native owner per explicitly mapped logical world, independent of Bukkit folders. */
final class WorldRepository implements AutoCloseable {
    record Mapping(String world, String root, String dimension) { }
    private final RetinaPlugin plugin;
    private final Map<String, Mapping> mappings = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new HashMap<>();
    private final Map<Path, Owner> owners = new HashMap<>();
    private final Set<String> unloaded = new HashSet<>();
    private final Path container, mappingFile;
    private boolean closed;

    WorldRepository(RetinaPlugin plugin) {
        this.plugin = plugin;
        container = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        mappingFile = plugin.getDataFolder().toPath().resolve("worlds.yml");
        try {
            var saved = new YamlConfiguration();
            try { saved.load(mappingFile.toFile()); }
            catch (java.io.FileNotFoundException absent) { /* First installation. */ }
            readMappings(saved.getMapList("worlds"));
            readMappings(plugin.getConfig().getMapList("worlds"));
            var properties = new Properties();
            try (var input = Files.newInputStream(Path.of("server.properties"))) { properties.load(input); }
            catch (NoSuchFileException absent) { /* Bukkit's documented default. */ }
            String base = properties.getProperty("level-name", "world");
            mappings.putIfAbsent(base, new Mapping(base, base, "minecraft:overworld"));
            mappings.putIfAbsent(base + "_nether", new Mapping(base + "_nether", base, "minecraft:the_nether"));
            mappings.putIfAbsent(base + "_the_end", new Mapping(base + "_the_end", base, "minecraft:the_end"));
            saveMappings();
        } catch (Exception error) { throw new IllegalStateException("Cannot load Retina world ownership from " + mappingFile, error); }
    }

    private void readMappings(List<Map<?, ?>> entries) {
        for (var entry : entries) {
            var mapping = new Mapping(required(entry, "world"), required(entry, "root"), required(entry, "dimension"));
            mappings.put(mapping.world(), mapping);
        }
    }
    private static String required(Map<?, ?> entry, String key) {
        if (!(entry.get(key) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("World mapping requires string " + key + ": " + entry);
        return value;
    }
    private void saveMappings() throws IOException {
        var saved = new YamlConfiguration();
        saved.set("worlds", mappings.values().stream().map(m -> Map.of("world", m.world(), "root", m.root(), "dimension", m.dimension())).toList());
        // save() creates the plugin directory. Definitions themselves use native atomic publication.
        saved.save(mappingFile.toFile());
    }

    synchronized void select(String world) { requireOpen(); unloaded.remove(world); }

    synchronized Binding resolve(WorldInfo info, String selection) {
        requireOpen();
        if (unloaded.contains(info.getName())) throw new IllegalStateException("Retina world is unloading: " + info.getName());
        String chosen = selection == null || selection.isBlank() ? null : switch (selection) {
            case "overworld", "minecraft:overworld" -> "minecraft:overworld";
            case "nether", "minecraft:the_nether" -> "minecraft:the_nether";
            case "end", "minecraft:the_end" -> "minecraft:the_end";
            default -> throw new IllegalArgumentException("Unknown Retina generator selection: " + selection);
        };
        var bound = bindings.get(info.getName());
        if (bound != null) {
            bound.dimension.validate(info);
            if (chosen != null && !chosen.equals(bound.dimension.key)) throw new IllegalStateException("Conflicting Retina dimension selection for " + info.getName());
            return bound;
        }
        var mapping = mappings.get(info.getName());
        if (mapping == null) {
            mapping = new Mapping(info.getName(), info.getName(), chosen == null ? dimension(info.getEnvironment()) : chosen);
            mappings.put(mapping.world(), mapping);
            try { saveMappings(); } catch (IOException error) { throw new IllegalStateException("Cannot persist " + mapping, error); }
        } else if (chosen != null && !chosen.equals(mapping.dimension())) {
            throw new IllegalArgumentException("Generator selection conflicts with saved world mapping " + mapping);
        }
        Path root = container.resolve(mapping.root()).normalize();
        Owner owner = owners.get(root);
        boolean created = owner == null;
        try {
            if (created) { owner = open(root, info.getSeed()); owners.put(root, owner); }
            var context = owner.dimensions.get(mapping.dimension());
            if (context == null) {
                context = new DimensionContext(owner, mapping.dimension(), info, plugin);
                owner.dimensions.put(mapping.dimension(), context);
            } else context.validate(info);
            bound = new Binding(owner, context);
            bindings.put(info.getName(), bound);
            owner.references++;
            return bound;
        } catch (RuntimeException | Error failure) {
            if (created && owner != null) { owners.remove(root); owner.close(); }
            throw failure;
        }
    }

    static String dimension(World.Environment environment) {
        return switch (environment) {
            case NORMAL -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> throw new IllegalArgumentException("Map the custom Bukkit environment to an explicit saved dimension");
        };
    }

    private Owner open(Path root, long seed) {
        long started = System.nanoTime();
        NativeWorldDefinition definition = NativeWorldDefinition.tryOpen(root.resolve("retina.dat"));
        boolean created = definition == null;
        if (created) {
            String variant = plugin.getConfig().getString("catalog-variant", "bukkit-api");
            // The resource is read only for genuinely absent definitions. Upgrades/restarts skip it entirely.
            try (var input = plugin.getResource("catalogs/" + plugin.getDescription().getAPIVersion() + "/retina-catalog.dat")) {
                if (input == null) throw new IllegalStateException("This plugin JAR lacks its vanilla catalog");
                Path temporary = Files.createTempFile("retina-catalog-", ".dat");
                try {
                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                    definition = NativeCatalog.create(temporary, variant, seed, root);
                } finally {
                    try { Files.deleteIfExists(temporary); }
                    catch (IOException cleanup) {
                        temporary.toFile().deleteOnExit();
                        plugin.getLogger().warning("Could not delete temporary catalog " + temporary + ": " + cleanup.getMessage());
                    }
                }
            } catch (IOException error) { throw new IllegalStateException("Cannot materialize Retina definition in " + root, error); }
        }
        try {
            var owner = new Owner(root, definition);
            plugin.getLogger().info((created ? "Created and reopened " : "Opened ") + root.resolve("retina.dat")
                    + " seed=" + owner.seed + " in " + ((System.nanoTime() - started) / 1_000_000) + "ms");
            return owner;
        } catch (RuntimeException | Error error) { definition.close(); throw error; }
    }

    synchronized void unload(String name) {
        unloaded.add(name);
        var binding = bindings.remove(name);
        if (binding == null) return;
        var owner = binding.owner;
        owner.lock.writeLock().lock();
        try { binding.closed = true; owner.references--; }
        finally { owner.lock.writeLock().unlock(); }
        if (owner.references == 0) { owners.remove(owner.root); owner.close(); }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Retina plugin is stopped"); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (var owner : owners.values()) try { owner.close(); }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        owners.clear(); bindings.clear();
        if (failure != null) throw failure;
    }

    static final class Binding {
        final Owner owner;
        final DimensionContext dimension;
        private boolean closed;
        Binding(Owner owner, DimensionContext dimension) { this.owner = owner; this.dimension = dimension; }
        <T> T use(Supplier<T> job) {
            owner.lock.readLock().lock();
            try {
                if (closed || owner.closed) throw new IllegalStateException("Retina world is closed: " + owner.root);
                return job.get();
            } finally { owner.lock.readLock().unlock(); }
        }
    }

    static final class Owner implements AutoCloseable {
        final Path root;
        final NativeWorldDefinition definition;
        final JsonObject metadata;
        final long seed;
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        final Map<String, DimensionContext> dimensions = new HashMap<>();
        int references;
        boolean closed;
        Owner(Path root, NativeWorldDefinition definition) {
            this.root = root; this.definition = definition;
            metadata = JsonParser.parseString(definition.metadata()).getAsJsonObject();
            seed = metadata.get("seed").getAsLong(); // Never round a 64-bit seed through double.
        }
        @Override public void close() {
            lock.writeLock().lock();
            try {
                if (closed) return;
                closed = true;
                dimensions.values().forEach(DimensionContext::close);
                definition.close();
            } finally { lock.writeLock().unlock(); }
        }
    }
}
