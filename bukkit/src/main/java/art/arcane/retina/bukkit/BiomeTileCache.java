package art.arcane.retina.bukkit;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.logging.Logger;

/** Exact quart biomes with bounded hot tiles and an optional derived disk cache. */
final class BiomeTileCache implements AutoCloseable {
    record Tile(int x, int y, int z) { }
    private final GenerationCache<Tile, int[]> cache;
    private final PersistentBiomeCache persisted;
    private final Function<int[], int[]> sample;

    BiomeTileCache(int retained, int concurrent, Path root, String dimension, int diskCapacity,
                   int paletteSize, Logger log, Function<int[], int[]> sample) {
        this(retained, concurrent, PersistentBiomeCache.open(root, dimension, diskCapacity, paletteSize, log), sample);
    }

    BiomeTileCache(int retained, int concurrent, PersistentBiomeCache persisted, Function<int[], int[]> sample) {
        cache = new GenerationCache<>(retained, concurrent);
        this.persisted = persisted; this.sample = sample;
    }

    int biome(int x, int y, int z) {
        var tile = new Tile(x >> 5, Math.floorDiv(y, 4), z >> 5);
        int[] answers = cache.get(tile, () -> {
            int[] saved = persisted.get(tile);
            if (saved != null) return saved;
            int[] points = new int[64 * 3];
            for (int i = 0; i < 64; i++) {
                points[i * 3] = tile.x() * 32 + (i & 7) * 4;
                points[i * 3 + 1] = tile.y() * 4;
                points[i * 3 + 2] = tile.z() * 32 + (i >>> 3) * 4;
            }
            int[] sampled = sample.apply(points);
            persisted.put(tile, sampled);
            return sampled;
        });
        return answers[((z & 31) >> 2) * 8 + ((x & 31) >> 2)];
    }

    GenerationCache.Statistics statistics() { return cache.statistics(); }
    PersistentBiomeCache.Statistics diskStatistics() { return persisted.statistics(); }
    void save() { persisted.save(); }
    @Override public void close() { cache.close(); persisted.close(); }
}
