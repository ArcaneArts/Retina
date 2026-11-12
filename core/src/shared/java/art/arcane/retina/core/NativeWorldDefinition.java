package art.arcane.retina.core;

import java.nio.file.Path;
import java.util.Objects;

/** Owns a loaded definition. Rust supplies the authoritative settings. */
public final class NativeWorldDefinition implements AutoCloseable {
    public enum ProgramExecution { AUTO, INTERPRETER, SPECIALIZED }

    public record RegionResult(long generated, long preserved, long bytes) { }

    private static final DefinitionTransport TRANSPORT = NativeLibrary.definitionTransport();

    private long handle;
    private final String metadata;

    private NativeWorldDefinition(long handle, String metadata) {
        this.handle = handle;
        this.metadata = metadata;
    }

    /** Returns null only when Rust's actual file open reports absence. No GPU is started. */
    public static NativeWorldDefinition tryOpen(Path path) {
        long handle = TRANSPORT.open(path);
        return handle == 0 ? null : takeOwnership(handle);
    }

    /** Adopt a native owner, releasing it if adapter metadata cannot be loaded. */
    static NativeWorldDefinition takeOwnership(long handle) {
        if (handle == 0) throw new IllegalStateException("Missing created world definition handle");
        try {
            return new NativeWorldDefinition(handle, TRANSPORT.metadata(handle));
        } catch (RuntimeException | Error error) {
            try { TRANSPORT.close(handle); }
            catch (RuntimeException | Error close) { error.addSuppressed(close); }
            throw error;
        }
    }

    public static NativeWorldDefinition open(Path path) {
        var definition = tryOpen(path);
        if (definition == null) throw new IllegalStateException("Missing Retina world definition: " + path);
        return definition;
    }

    /** Compact adapter output from the Rust-parsed file, not a generation input. */
    public String metadata() { return metadata; }

    /** First binding initializes the shared native engine. Codec IDs: 2=zlib, 4=LZ4Block. */
    public synchronized int bindDimension(String key, int compression) {
        return TRANSPORT.bind(openHandle(), key, compression);
    }

    /** Dispatch preference is fixed for this binding without changing the saved definition. */
    public synchronized int bindDimension(String key, int compression, ProgramExecution execution) {
        long opened = openHandle();
        return TRANSPORT.bind(opened, key, compression, Objects.requireNonNull(execution, "program execution"));
    }

    /** Seed and all geometry fields are populated by Rust from the saved definition. */
    public synchronized TerrainRequest request(String key, int x, int z) {
        return TRANSPORT.request(openHandle(), key, x, z);
    }

    /** Region coordinates; Rust owns the saved settings and preserves existing records.
     * The caller must exclusively own destination file I/O and drain calls before close. */
    public RegionResult generateRegion(String key, int x, int z, Path destination) {
        return TRANSPORT.generateRegion(openHandle(), key, x, z, destination);
    }

    /** Publish a prepared region into missing slots while exclusively owning destination I/O. */
    public RegionResult publishRegion(String key, int x, int z, Path preview, Path destination) {
        return TRANSPORT.publishRegion(openHandle(), key, x, z, preview, destination);
    }

    /** Generate storage-shaped uncompressed chunk NBT using the loaded world. */
    public byte[] chunk(String key, int x, int z) {
        // Native lookup leases the world for the call. Independent generation
        // jobs must not run while holding this Java object's monitor.
        return TRANSPORT.chunk(openHandle(), key, x, z);
    }

    /** Saved local biome IDs in x,z,y quart order, without assembling terrain.
     * The host must drain admitted calls before closing its world owner. */
    public short[] biomes(String key, int x, int z) {
        return TRANSPORT.biomes(request(key, x, z));
    }

    /** Exact XYZ biome probes, quart-normalized by the engine, without building terrain.
     * The host must drain admitted calls before closing its world owner. */
    public int[] biomePoints(String key, int[] xyz) {
        if (xyz.length % 3 != 0 || xyz.length / 3 > 1024) throw new IllegalArgumentException("Invalid biome point batch");
        if (xyz.length == 0) return new int[0];
        return TRANSPORT.biomePoints(request(key, 0, 0), xyz);
    }

    /** Chunk X, chunk Z and saved set index triples. Returns saved definition indexes or -1. */
    public int[] structureStarts(String key, int[] candidates) {
        if (candidates.length % 3 != 0 || candidates.length / 3 > 64) {
            throw new IllegalArgumentException("Invalid structure start batch");
        }
        if (candidates.length == 0) return new int[0];
        return TRANSPORT.structureStarts(request(key, 0, 0), candidates);
    }

    /** Saved concentric-ring chunk X/Z pairs in placement order. */
    public int[] structureRingPositions(String key, int set, int count) {
        if (set < 0 || count < 0 || count > 4095) throw new IllegalArgumentException("Invalid structure ring query");
        return TRANSPORT.structureRingPositions(request(key, 0, 0), set, count);
    }

    /** Explicit QA/diagnostic output, decoded from the same loaded file without rereading it. */
    public synchronized String diagnosticProfile(String key) {
        return TRANSPORT.diagnosticProfile(openHandle(), key);
    }

    /** Stop admitting generation and drain outstanding work before closing the world. */
    @Override
    public synchronized void close() {
        if (handle == 0) return;
        TRANSPORT.close(handle);
        handle = 0;
    }

    private synchronized long openHandle() {
        if (handle == 0) throw new IllegalStateException("World definition is closed");
        return handle;
    }
}
