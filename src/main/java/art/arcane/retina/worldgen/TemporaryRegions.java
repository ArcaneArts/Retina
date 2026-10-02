package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

/** Per-world, immutable MCA previews. Leases protect active reads from LRU eviction and shutdown. */
final class TemporaryRegions implements AutoCloseable {
    static final int MAX_REGIONS = 1024;
    private final TerrainRequest settings;
    private final String biome;
    private final GenerationMetrics metrics;
    private final BiomeTerrainProfile profile;
    private final Path folder;
    private final int capacity;
    private final int memoryCapacity;
    private final LinkedHashMap<Long, Entry> warm = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private int users;
    private boolean closed;
    private final int stone, water, bedrock, deepslate, ice, lava;
    private final int[] bands, biomeFlags, biomeFillers;
    private final Map<String,Integer> biomeIds = new HashMap<>();

    TemporaryRegions(TerrainRequest settings, String biome, BiomeTerrainProfile profile, GenerationMetrics metrics, int capacity) {
        this(settings, biome, profile, metrics, capacity, Math.min(capacity, 16));
    }

    TemporaryRegions(TerrainRequest settings, String biome, BiomeTerrainProfile profile, GenerationMetrics metrics, int capacity, int memoryCapacity) {
        this.settings = settings;
        this.biome = biome;
        this.profile = profile;
        this.metrics = metrics;
        this.capacity = capacity;
        this.memoryCapacity = memoryCapacity;
        if (capacity < 1 || memoryCapacity < 1) throw new IllegalArgumentException("Temporary region capacity must be positive");
        try { folder = Files.createTempDirectory("retina-preview-"); }
        catch (IOException error) { throw new UncheckedIOException(error); }
        var ids = profile == null ? null : JsonParser.parseString(profile.json()).getAsJsonObject();
        stone = ids == null ? 1 : ids.get("stone").getAsInt();
        water = ids == null ? 0 : ids.get("water").getAsInt();
        bedrock = ids == null ? 1 : ids.get("bedrock").getAsInt();
        deepslate = ids == null ? 1 : ids.get("deepslate").getAsInt();
        ice = ids == null ? 0 : ids.get("ice").getAsInt();
        lava = ids == null || !ids.has("lava") ? water : ids.get("lava").getAsInt();
        bands = ids == null || !ids.has("terrain_features") ? new int[0] : ids.getAsJsonObject("terrain_features").getAsJsonArray("bands").asList().stream().mapToInt(e -> e.getAsInt()).toArray();
        biomeFillers = ids == null ? new int[0] : ids.getAsJsonArray("biomes").asList().stream().mapToInt(e -> e.getAsJsonObject().get("filler").getAsInt()).toArray();
        biomeFlags = ids == null ? new int[0] : ids.getAsJsonArray("biomes").asList().stream().mapToInt(e -> e.getAsJsonObject().get("flags").getAsInt()).toArray();
        if (profile != null) for (int i = 0; i < profile.biomes().size(); i++) biomeIds.put(profile.biomes().get(i).unwrapKey().orElseThrow().identifier().toString(), i);
    }

    private final class Entry {
        final long key;
        final ChunkPos origin;
        final Path path;
        final CompletableFuture<NativeTerrain.RegionReport> generated = new CompletableFuture<>();
        final Path columnPath;
        NativeTerrain.Columns columns;
        RegionFileStorage storage;
        int pins;
        boolean retired;
        Entry(ChunkPos position) {
            key = ChunkPos.pack(position.getRegionX(), position.getRegionZ());
            origin = new ChunkPos(position.getRegionX() * 32, position.getRegionZ() * 32);
            path = folder.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".mca");
            columnPath = folder.resolve("r." + position.getRegionX() + "." + position.getRegionZ() + ".columns.z");
        }
        synchronized CompoundTag read(ChunkPos position) throws IOException {
            if (storage == null) storage = new RegionFileStorage(new RegionStorageInfo("retina-preview", Level.OVERWORLD, "chunk"), folder, false);
            var tag = storage.read(position);
            if (tag == null) throw new IOException("Missing chunk in completed temporary MCA: " + position);
            return tag;
        }
        synchronized void storeColumns(NativeTerrain.Columns columns) throws IOException {
            var deflater = new Deflater(Deflater.BEST_SPEED);
            try (var output = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(columnPath), deflater)))) {
                for (int i = 0; i < columns.heights().length; i++) {
                    output.writeInt(columns.heights()[i]); output.writeInt(columns.packed()[i]);
                }
            } finally { deflater.end(); }
            this.columns = columns;
        }
        synchronized NativeTerrain.Columns columns() throws IOException {
            if (columns == null) {
                int[] heights = new int[1024 * 256], packed = new int[1024 * 256];
                try (var input = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(columnPath))))) {
                    for (int i = 0; i < heights.length; i++) { heights[i] = input.readInt(); packed[i] = input.readInt(); }
                }
                columns = new NativeTerrain.Columns(heights, packed);
            }
            return columns;
        }
        synchronized void cool() {
            columns = null;
            try { if (storage != null) storage.close(); }
            catch (IOException error) { Retina.LOGGER.warn("Could not close temporary Retina region {}", path, error); }
            storage = null;
        }
        synchronized void dispose() {
            cool();
            try { Files.deleteIfExists(path); Files.deleteIfExists(columnPath); }
            catch (IOException error) { Retina.LOGGER.warn("Could not remove temporary Retina region {}", path, error); }
        }

    }

    private final class Lease implements AutoCloseable {
        final Entry entry;
        Lease(Entry entry) { this.entry = entry; }
        NativeTerrain.RegionReport data() { return entry.generated.join(); }
        @Override public void close() {
            synchronized (TemporaryRegions.this) {
                entry.pins--; users--;
                if (entry.pins == 0 && entry.generated.isCompletedExceptionally()) {
                    entries.remove(entry.key); warm.remove(entry.key); entry.retired = true;
                }
                if (entry.retired && entry.pins == 0) entry.dispose();
                trim(); trimWarm();
                removeClosedFolder();
            }
        }
    }

    private Lease acquire(ChunkPos position) {
        Entry entry;
        boolean generate;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Temporary Retina regions are closed");
            long key = ChunkPos.pack(position.getRegionX(), position.getRegionZ());
            entry = entries.get(key);
            generate = entry == null;
            if (generate) { entry = new Entry(position); entries.put(key, entry); }
            else { metrics.previewCacheHit(); if (entry.generated.isDone()) warm.put(key, entry); }
            entry.pins++; users++;
            trim(); trimWarm();
        }
        var lease = new Lease(entry);
        if (generate) {
            metrics.begin();
            long start = System.nanoTime();
            try {
                var r = new TerrainRequest(settings.seed(), entry.origin.x(), entry.origin.z(), settings.minY(), settings.height(),
                        settings.baseHeight(), settings.amplitude(), settings.frequency(), settings.profile());
                var data = NativeTerrain.instance().generateRegionColumns(r, entry.path,
                        SharedConstants.getCurrentVersion().dataVersion().version(), biome);
                entry.storeColumns(data.columns());
                long elapsed = System.nanoTime() - start;
                metrics.completedPreviewRegion(data.report().generated(), elapsed);
                entry.generated.complete(data.report());
                synchronized (this) { if (!entry.retired) warm.put(entry.key, entry); trimWarm(); }
                if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info(
                        "QA_EVT {\"event\":\"temporary_mca_region\",\"status\":\"pass\",\"context\":{\"x\":{},\"z\":{},\"chunks\":{},\"ms\":{},\"bytes\":{}}}",
                        position.getRegionX(), position.getRegionZ(), data.report().generated(), elapsed / 1e6, data.report().bytes());
            } catch (Throwable error) {
                metrics.failed(); entry.generated.completeExceptionally(error);
                lease.close();
                if (error instanceof IOException io) throw new UncheckedIOException(io);
                if (error instanceof RuntimeException runtime) throw runtime;
                if (error instanceof Error fatal) throw fatal;
                throw new IllegalStateException(error);
            }
        }
        return lease;
    }

    synchronized Path folder() { return folder; }
    synchronized int size() { return entries.size(); }

    private void trim() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (entries.size() > capacity && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.pins != 0) continue;
            iterator.remove(); warm.remove(entry.key); entry.retired = true; entry.dispose();
        }
    }

    private void trimWarm() {
        Iterator<Entry> iterator = warm.values().iterator();
        while (warm.size() > memoryCapacity && iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.pins != 0) continue;
            iterator.remove(); entry.cool();
        }
    }

    NativeTerrain.Columns columns(ChunkPos position) {
        try (var lease = acquire(position)) {
            lease.data();
            var columns = lease.entry.columns();
            int start = (Math.floorMod(position.z(), 32) * 32 + Math.floorMod(position.x(), 32)) * 256;
            return new NativeTerrain.Columns(Arrays.copyOfRange(columns.heights(), start, start + 256),
                    Arrays.copyOfRange(columns.packed(), start, start + 256));
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    CompoundTag read(ChunkPos position) {
        try (var lease = acquire(position)) {
            lease.data();
            return lease.entry.read(position);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    byte[] biomes(ChunkPos position) {
        var tag = read(position);
        var output = new byte[settings.height() * 4];
        for (var section : tag.getListOrEmpty("sections")) {
            var entry = (CompoundTag) section;
            int start = (entry.getByte("Y").orElseThrow() * 16 - settings.minY()) * 4;
            if (start < 0 || start + 64 > output.length) continue;
            var data = entry.getCompoundOrEmpty("biomes");
            var palette = data.getListOrEmpty("palette");
            int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
            var packed = palette.size() == 1 ? null : new net.minecraft.util.SimpleBitStorage(bits, 64, data.getLongArray("data").orElseThrow());
            for (int i = 0; i < 64; i++) output[start + i] = biomeIds.get(palette.getString(packed == null ? 0 : packed.get(i)).orElseThrow()).byteValue();
        }
        return output;
    }

    BlockState[] baseColumn(NativeTerrain.Columns columns, int index) {
        int ground = columns.heights()[index], packed = columns.packed()[index];
        var states = new BlockState[settings.height()];
        boolean lake = (packed & (1 << 29)) != 0;
        int depth = (packed >>> 24) & 7;
        int waterline = lake ? ground + depth : profile == null ? settings.minY() : profile.seaLevel();
        var materials = profile == null ? new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()} : profile.materials();
        for (int layer = 0; layer < states.length; layer++) {
            int y = settings.minY() + layer;
            int id;
            if (profile == null) id = y < ground ? 1 : 0;
            else if (layer < 1 + (packed >>> 30)) id = bedrock;
            else if (y >= ground) id = y < waterline ? (lake && (packed & (1 << 28)) != 0 ? lava : !lake && (packed & (1 << 28)) != 0 && y == waterline - 1 ? ice : water) : 0;
            else if (y == ground - 1) id = (packed >>> 8) & 255;
            else if (!lake && (biomeFlags[packed & 255] & 8) != 0 && bands.length > 0 && y >= profile.seaLevel() - 16) id = bands[Math.floorMod(y + (byte) ((packed >>> 16) & 255), bands.length)];
            else if (y >= ground - 1 - (lake ? 2 : depth)) id = !lake && (biomeFlags[packed & 255] & 8) != 0 && bands.length > 0 ? biomeFillers[packed & 255] : (packed >>> 16) & 255;
            else id = y < 0 ? deepslate : stone;
            states[layer] = materials[id];
        }
        return states;
    }

    /** Called exclusively on Minecraft's region I/O queue, after closing its destination handle. */
    NativeTerrain.RegionReport publishIfPresent(ChunkPos position, Path destination) {
        Lease lease;
        synchronized (this) {
            if (closed) return null;
            var entry = entries.get(ChunkPos.pack(position.getRegionX(), position.getRegionZ()));
            if (entry == null) return null;
            entry.pins++; users++; lease = new Lease(entry);
        }
        try (lease) {
            lease.data();
            var r = new TerrainRequest(settings.seed(), position.x(), position.z(), settings.minY(), settings.height(),
                    settings.baseHeight(), settings.amplitude(), settings.frequency(), settings.profile());
            return NativeTerrain.instance().publishCachedRegion(r, destination, lease.entry.path);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var entry : entries.values()) {
            entry.retired = true;
            if (entry.pins == 0) entry.dispose();
        }
        entries.clear(); warm.clear();
        removeClosedFolder();
    }
    private void removeClosedFolder() {
        if (closed && users == 0) try { Files.deleteIfExists(folder); }
        catch (IOException error) { Retina.LOGGER.warn("Could not remove Retina preview folder {}", folder, error); }
    }
}
