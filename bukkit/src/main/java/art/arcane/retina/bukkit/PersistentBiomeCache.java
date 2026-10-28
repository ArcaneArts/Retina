package art.arcane.retina.bukkit;

import art.arcane.retina.core.NativeLibrary;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Disposable, bounded exact biome answers; never part of the world definition. */
final class PersistentBiomeCache implements AutoCloseable {
    private static final int MAGIC = 0x52424331; // RBC1: 8x8 quart tiles, unsigned-short local IDs.
    private static final int MAX_TILES = 16384;
    private final LinkedHashMap<BiomeTileCache.Tile, int[]> tiles = new LinkedHashMap<>(16, .75F, true);
    private final Path path;
    private final byte[] definition, engine;
    private final String dimension;
    private final int capacity, paletteSize;
    private final Logger log;
    private long hits, misses;
    private int restored;
    private boolean dirty;
    record Statistics(long hits, long misses, int restored, int entries) { }

    static PersistentBiomeCache open(Path root, String dimension, int capacity, int paletteSize, Logger log) {
        try {
            if (capacity <= 0) return new PersistentBiomeCache(null, dimension, new byte[32], new byte[32], 0, paletteSize, log);
            Path path = root.resolve("retina-cache/biomes-" + HexFormat.of().formatHex(digest(dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".dat");
            return new PersistentBiomeCache(path, dimension, digest(root.resolve("retina.dat")), NativeLibrary.imageDigest(), capacity, paletteSize, log);
        } catch (IOException error) {
            log.warning("Cannot open optional Retina biome cache; using native queries: " + error);
            return new PersistentBiomeCache(null, dimension, new byte[32], new byte[32], 0, paletteSize, log);
        }
    }

    PersistentBiomeCache(Path path, String dimension, byte[] definition, byte[] engine, int capacity, int paletteSize, Logger log) {
        this.path = path; this.dimension = dimension; this.definition = definition.clone(); this.engine = engine.clone();
        this.capacity = Math.max(0, Math.min(capacity, MAX_TILES)); this.paletteSize = paletteSize; this.log = log;
        if (path != null && this.capacity > 0) read();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] digest(byte[] bytes) { return sha256().digest(bytes); }
    private static byte[] digest(Path path) throws IOException {
        var digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
        return digest.digest();
    }

    private void read() {
        // Publish nothing until the entire bounded payload and gzip checksum have passed.
        var loaded = new LinkedHashMap<BiomeTileCache.Tile, int[]>();
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || !Arrays.equals(input.readNBytes(32), definition)
                    || !Arrays.equals(input.readNBytes(32), engine) || !input.readUTF().equals(dimension)) return;
            int count = input.readInt();
            if (count < 0 || count > MAX_TILES) throw new IOException("Invalid biome tile count " + count);
            for (int i = 0; i < count; i++) {
                var tile = new BiomeTileCache.Tile(input.readInt(), input.readInt(), input.readInt());
                int[] biomes = new int[64];
                for (int j = 0; j < biomes.length; j++) {
                    biomes[j] = input.readUnsignedShort();
                    if (biomes[j] >= paletteSize) throw new IOException("Biome ID outside saved palette");
                }
                if (loaded.put(tile, biomes) != null) throw new IOException("Duplicate biome tile");
            }
            if (input.read() != -1) throw new IOException("Trailing biome cache data");
            loaded.forEach(this::retain);
            restored = tiles.size();
        } catch (NoSuchFileException absent) { /* Cold world; query the native engine. */ }
        catch (IOException error) { log.warning("Ignoring optional Retina biome cache " + path + ": " + error); }
    }

    synchronized int[] get(BiomeTileCache.Tile tile) {
        int[] result = tiles.get(tile);
        if (result == null) misses++; else hits++;
        return result;
    }
    synchronized void put(BiomeTileCache.Tile tile, int[] biomes) {
        if (capacity == 0) return;
        retain(tile, biomes); dirty = true;
    }
    private void retain(BiomeTileCache.Tile tile, int[] biomes) {
        tiles.put(tile, biomes);
        while (tiles.size() > capacity) tiles.pollFirstEntry();
    }
    synchronized Statistics statistics() { return new Statistics(hits, misses, restored, tiles.size()); }

    @Override public synchronized void close() {
        save(); tiles.clear(); dirty = false;
    }

    synchronized void save() {
        if (!dirty || path == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), "biomes-", ".tmp");
            try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)), 8192))) {
                output.writeInt(MAGIC); output.write(definition); output.write(engine); output.writeUTF(dimension); output.writeInt(tiles.size());
                for (var entry : tiles.entrySet()) {
                    var tile = entry.getKey(); output.writeInt(tile.x()); output.writeInt(tile.y()); output.writeInt(tile.z());
                    for (int biome : entry.getValue()) output.writeShort(biome);
                }
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } catch (IOException error) { log.warning("Cannot save optional Retina biome cache " + path + ": " + error); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException error) { log.warning("Cannot remove temporary Retina biome cache " + temporary + ": " + error); }
        }
    }
}
