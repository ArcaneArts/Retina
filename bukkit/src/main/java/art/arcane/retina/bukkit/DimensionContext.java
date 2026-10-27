package art.arcane.retina.bukkit;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.WorldInfo;
import java.util.*;

/** Immutable saved palette bindings and bounded native result reuse. */
final class DimensionContext implements AutoCloseable {
    final WorldRepository.Owner owner;
    final String key;
    final int minimum, height, nativeMinimum, nativeHeight, spawnHeight;
    final List<Biome> biomes;
    final MetadataDelivery metadata;
    private final Map<String, BlockData> blocks;
    private final GenerationCache<Long, ChunkSnapshot> chunks;
    private final GenerationCache<Long, short[]> biomeGrids;
    private final GenerationCache<Long, Integer> firstBiomeHeights;
    private final GenerationCache<BiomeTile, int[]> biomeTiles;
    private record BiomeTile(int x, int y, int z) { }

    DimensionContext(WorldRepository.Owner owner, String key, WorldInfo info, RetinaPlugin plugin) {
        this.owner = owner; this.key = key;
        var dimensions = owner.metadata.getAsJsonObject("dimensions");
        if (!dimensions.has(key)) throw new IllegalStateException("Saved definition has no dimension " + key);
        var dimension = dimensions.getAsJsonObject(key);
        if (dimension.has("unsupported_reason")) throw new IllegalStateException("Unsupported saved dimension " + key + ": " + dimension.get("unsupported_reason").getAsString());
        var parameters = dimension.getAsJsonObject("parameters");
        nativeMinimum = parameters.get("min_y").getAsInt(); nativeHeight = parameters.get("height").getAsInt();
        var storage = parameters.has("storage") && !parameters.get("storage").isJsonNull() ? parameters.getAsJsonObject("storage") : parameters;
        minimum = storage.get("min_y").getAsInt(); height = storage.get("height").getAsInt();
        spawnHeight = parameters.get("spawn_height").getAsInt();
        validate(info);
        metadata = new MetadataDelivery(plugin.getLogger(), plugin.getConfig().getBoolean("allow-metadata-loss", false));
        var structures = dimension.getAsJsonObject("profile_metadata").getAsJsonObject("structures");
        if (!structures.getAsJsonArray("definitions").isEmpty())
            metadata.unavailable("structure starts/references and locate metadata in " + key);
        if (owner.metadata.get("bonus_chest").getAsBoolean()) metadata.unavailable("bonus-chest world setup");
        var shared = owner.metadata.getAsJsonObject("shared_types");
        var materials = shared.getAsJsonArray("materials");
        var resolved = new HashMap<String, BlockData>();
        for (var index : dimension.getAsJsonArray("material_palette")) {
            String canonical = state(materials.get(index.getAsInt()).getAsJsonObject());
            try { resolved.put(canonical, Bukkit.createBlockData(canonical)); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("Bukkit cannot resolve saved block state " + canonical + " in " + key, error); }
        }
        blocks = Map.copyOf(resolved);
        var names = shared.getAsJsonArray("biomes");
        var resolvedBiomes = new ArrayList<Biome>();
        for (var index : dimension.getAsJsonArray("biome_palette")) {
            String canonical = names.get(index.getAsInt()).getAsString();
            var id = NamespacedKey.fromString(canonical);
            Biome biome = id == null ? null : Registry.BIOME.get(id);
            if (biome == null || biome == Biome.CUSTOM) throw new IllegalStateException("Bukkit cannot resolve saved biome " + canonical + " in " + key);
            resolvedBiomes.add(biome);
        }
        biomes = List.copyOf(resolvedBiomes);
        int concurrent = plugin.getConfig().getInt("concurrent-loaders", 4);
        chunks = new GenerationCache<>(plugin.getConfig().getInt("chunk-cache", 64), concurrent);
        biomeGrids = new GenerationCache<>(plugin.getConfig().getInt("biome-cache", 256), concurrent);
        firstBiomeHeights = new GenerationCache<>(plugin.getConfig().getInt("biome-cache", 256), concurrent);
        biomeTiles = new GenerationCache<>(plugin.getConfig().getInt("biome-cache", 256), concurrent);
        owner.definition.bindDimension(key, 2);
        plugin.getLogger().info("Bound " + info.getName() + " -> " + owner.root + " / " + key
                + " blocks=" + blocks.size() + " biomes=" + biomes.size() + " bounds=" + minimum + ".." + (minimum + height));
    }

    void validate(WorldInfo info) {
        if (info.getSeed() != owner.seed) throw new IllegalStateException("Bukkit world " + info.getName() + " seed " + info.getSeed()
                + " differs from saved Retina seed " + owner.seed + "; use the saved seed when creating the host world");
        if (info.getMinHeight() != minimum || info.getMaxHeight() != minimum + height)
            throw new IllegalStateException("Bukkit world " + info.getName() + " bounds " + info.getMinHeight() + ".." + info.getMaxHeight()
                    + " differ from saved " + key + " storage " + minimum + ".." + (minimum + height));
    }

    private static String state(JsonObject material) {
        String id = material.get("id").getAsString();
        var properties = new TreeMap<String, String>();
        material.getAsJsonObject("properties").entrySet().forEach(e -> properties.put(e.getKey(), e.getValue().getAsString()));
        if (properties.isEmpty()) return id;
        var text = new StringJoiner(",", id + "[", "]"); properties.forEach((k, v) -> text.add(k + "=" + v));
        return text.toString();
    }

    BlockData block(String canonical) {
        var state = blocks.get(canonical);
        if (state == null) throw new IllegalStateException("Generated state is absent from saved palette: " + canonical);
        return state;
    }

    ChunkSnapshot chunk(int x, int z) {
        return chunks.get(position(x, z), () -> {
            var snapshot = new ChunkSnapshot(owner.definition.chunk(key, x, z), minimum, height);
            metadata.validate(snapshot.root);
            return snapshot;
        });
    }

    Biome biome(int x, int y, int z) {
        // A one-entry saved palette has no other possible canonical result,
        // including fitted storage outside the native generation range.
        if (biomes.size() == 1) return biomes.getFirst();
        // Out-of-native-range storage uses the engine's fitted chunk data, not a guessed clamp.
        if (y < nativeMinimum || y >= nativeMinimum + nativeHeight) {
            String canonical = chunk(x >> 4, z >> 4).biome(x, y, z);
            var id = NamespacedKey.fromString(canonical);
            return biomes.stream().filter(b -> b.getKey().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Fitted chunk biome is absent from saved palette: " + canonical));
        }
        long chunk = position(x >> 4, z >> 4);
        short[] grid = biomeGrids.getIfPresent(chunk);
        int quart = Math.floorDiv(y, 4);
        int first = firstBiomeHeights.get(chunk, () -> quart);
        if (grid == null && first == quart) {
            // Server structure-ring searches can probe thousands of distant
            // chunks at one height even when structure generation is disabled.
            // Use the existing sparse query path until a full 3D grid is useful.
            var tile = new BiomeTile(x >> 5, quart, z >> 5);
            int[] sampled = biomeTiles.get(tile, () -> {
                int[] points = new int[64 * 3];
                for (int i = 0; i < 64; i++) {
                    points[i * 3] = tile.x() * 32 + (i & 7) * 4;
                    points[i * 3 + 1] = tile.y() * 4;
                    points[i * 3 + 2] = tile.z() * 32 + (i >>> 3) * 4;
                }
                return owner.definition.biomePoints(key, points);
            });
            return biomes.get(sampled[((z & 31) >> 2) * 8 + ((x & 31) >> 2)]);
        }
        if (grid == null) grid = biomeGrids.get(chunk, () -> owner.definition.biomes(key, x >> 4, z >> 4));
        int index = ((Math.floorDiv(y - nativeMinimum, 4) * 4 + ((z & 15) >> 2)) * 4) + ((x & 15) >> 2);
        return biomes.get(Short.toUnsignedInt(grid[index]));
    }

    private static long position(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    @Override public void close() { chunks.close(); biomeGrids.close(); firstBiomeHeights.close(); biomeTiles.close(); }
}
