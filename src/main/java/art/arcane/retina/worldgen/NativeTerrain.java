package art.arcane.retina.worldgen;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Bulk C ABI calls. Rust borrows these buffers only until the downcall returns. */
public final class NativeTerrain {
    private final MethodHandle timingSnapshot;
    private final MethodHandle gpuDiagnostics;
    private final MethodHandle generate;
    private final MethodHandle structureData;
    private final MethodHandle structureStarts;
    private final MethodHandle freeBytes;
    private final MethodHandle sample;
    private final MethodHandle profiledRegion;
    private final MethodHandle publishRegionCache;
    private final MethodHandle registerProfile;
    private final MethodHandle sampleColumns;
    private final MethodHandle sampleBiomes;
    private final MethodHandle decorationCounts;
    private final MethodHandle decorationFeature;
    private final MethodHandle decorationPlacement;
    private final MethodHandle column;
    private final MethodHandle lastError;
    private final String backend;

    private NativeTerrain() {
        var symbols = SymbolLookup.libraryLookup(extractLibrary(), Arena.global());
        var linker = Linker.nativeLinker();
        var initialize = linker.downcallHandle(symbols.findOrThrow("retina_initialize"), FunctionDescriptor.of(JAVA_INT));
        timingSnapshot = linker.downcallHandle(symbols.findOrThrow("retina_timing_snapshot"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        gpuDiagnostics = linker.downcallHandle(symbols.findOrThrow("retina_gpu_program_snapshot"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        generate = linker.downcallHandle(symbols.findOrThrow("retina_generate_chunk_columns_u16"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        structureData = linker.downcallHandle(symbols.findOrThrow("retina_chunk_structure_data"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        structureStarts = linker.downcallHandle(symbols.findOrThrow("retina_chunk_structure_starts"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        freeBytes = linker.downcallHandle(symbols.findOrThrow("retina_free_bytes"), FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        sample = linker.downcallHandle(symbols.findOrThrow("retina_sample_heights"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        profiledRegion = linker.downcallHandle(symbols.findOrThrow("retina_generate_region_profiled"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        publishRegionCache = linker.downcallHandle(symbols.findOrThrow("retina_publish_region_cache"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
        registerProfile = linker.downcallHandle(symbols.findOrThrow("retina_register_profile"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        sampleColumns = linker.downcallHandle(symbols.findOrThrow("retina_sample_columns"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        sampleBiomes = linker.downcallHandle(symbols.findOrThrow("retina_sample_biomes_u16"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        decorationCounts = linker.downcallHandle(symbols.findOrThrow("retina_sample_decoration_counts"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        decorationFeature = linker.downcallHandle(symbols.findOrThrow("retina_sample_decoration_feature"),
                FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS,JAVA_LONG,ADDRESS));
        decorationPlacement = linker.downcallHandle(symbols.findOrThrow("retina_sample_decoration_placement"),
                FunctionDescriptor.of(JAVA_INT,ADDRESS,JAVA_INT,ADDRESS,JAVA_LONG,ADDRESS,JAVA_LONG,ADDRESS));
        column = linker.downcallHandle(symbols.findOrThrow("retina_generate_column_u16"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        lastError = linker.downcallHandle(symbols.findOrThrow("retina_last_error"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        var backendCall = linker.downcallHandle(symbols.findOrThrow("retina_backend"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        try {
            check((int) initialize.invokeExact());
            backend = message(backendCall);
        } catch (Throwable error) {
            throw failure(error);
        }
        System.getLogger("retina").log(System.Logger.Level.INFO, "GPU terrain backend: " + backend);
    }

    private static class Holder {
        private static final NativeTerrain INSTANCE = new NativeTerrain();
    }

    public static NativeTerrain instance() {
        return Holder.INSTANCE;
    }

    public String backend() {
        return backend;
    }
    public NativeGpuDiagnostics gpuDiagnostics(int profile) {
        try(var arena=Arena.ofConfined()) {
            var out=arena.allocate(64,Long.BYTES);
            check((int)gpuDiagnostics.invokeExact(profile,out));
            if(out.get(JAVA_INT,0)!=1)throw new IllegalStateException("Unsupported GPU diagnostic version");
            return new NativeGpuDiagnostics(out.get(JAVA_INT,4),out.get(JAVA_LONG,8),out.get(JAVA_LONG,16),out.get(JAVA_INT,24),out.get(JAVA_INT,28),out.get(JAVA_INT,32),out.get(JAVA_INT,36),out.get(JAVA_LONG,40),out.get(JAVA_LONG,48),out.get(JAVA_LONG,56));
        }catch(Throwable error){throw failure(error);}
    }

    public NativeTimings timings(int profile) {
        try (var arena = Arena.ofConfined()) {
            var output = arena.allocate(NativeTimings.BYTES, Long.BYTES);
            check((int) timingSnapshot.invokeExact(profile, output));
            if (output.get(JAVA_INT,0) != 5) throw new IllegalStateException("Unsupported native timing ABI");
            return decodeTimings(output);
        } catch(Throwable error) { throw failure(error); }
    }

    private static NativeTimings decodeTimings(MemorySegment output) {
        long[] stages=new long[NativeTimings.STAGES];
        for(int i=0;i<stages.length;i++)stages[i]=output.get(JAVA_LONG,32L+i*8L);
        return new NativeTimings(output.get(JAVA_INT,4),output.get(JAVA_LONG,8),output.get(JAVA_LONG,16),output.get(JAVA_LONG,24),stages);
    }

    private static RegionReport decodeRegionReport(MemorySegment report) {
        return new RegionReport(report.get(JAVA_INT,0),report.get(JAVA_INT,4),report.get(JAVA_LONG,8),
                report.get(JAVA_LONG,16),report.get(JAVA_LONG,24),report.get(JAVA_LONG,32),decodeTimings(report.asSlice(40,NativeTimings.BYTES)));
    }

    public net.minecraft.nbt.CompoundTag structureData(TerrainRequest request) { return readStructureData(structureData,request); }
    public net.minecraft.nbt.CompoundTag structureStarts(TerrainRequest request) { return readStructureData(structureStarts,request); }
    private net.minecraft.nbt.CompoundTag readStructureData(MethodHandle handle, TerrainRequest request) {
        try (var arena = Arena.ofConfined()) {
            var pointer = arena.allocate(ADDRESS); var length = arena.allocate(JAVA_LONG);
            check((int) handle.invokeExact(encode(arena, request), pointer, length));
            var data = pointer.get(ADDRESS, 0); long count = length.get(JAVA_LONG, 0);
            try {
                var bytes = data.reinterpret(count).toArray(JAVA_BYTE);
                return net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            } finally { freeBytes.invokeExact(data, count); }
        } catch (Throwable error) { throw failure(error); }
    }

    public ChunkData generate(TerrainRequest request) {
        var arena = Arena.ofConfined();
        try {
            var blocks = arena.allocate(request.blockCount() * Short.BYTES, Short.BYTES);
            var columns = arena.allocate(256L * 12, Integer.BYTES);
            check((int) generate.invokeExact(encode(arena, request), blocks, request.blockCount(), columns));
            return new ChunkData(arena, blocks, decodeColumns(columns));
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public int[] sampleHeights(TerrainRequest request) {
        try (var arena = Arena.ofConfined()) {
            var heights = arena.allocate(256L * Integer.BYTES, Integer.BYTES);
            check((int) sample.invokeExact(encode(arena, request), heights));
            return heights.toArray(JAVA_INT);
        } catch (Throwable error) {
            throw failure(error);
        }
    }

    public int registerProfile(String json) {
        try (var arena = Arena.ofConfined()) {
            var bytes = json.getBytes(StandardCharsets.UTF_8);
            var id = arena.allocate(JAVA_INT);
            check((int) registerProfile.invokeExact(arena.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length, id));
            return id.get(JAVA_INT, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    public Columns sampleColumns(TerrainRequest request) {
        try (var arena = Arena.ofConfined()) {
            var columns = arena.allocate(256L * 12, Integer.BYTES);
            check((int) sampleColumns.invokeExact(encode(arena, request), columns));
            return decodeColumns(columns);
        } catch (Throwable error) { throw failure(error); }
    }

    /** Minecraft's x,z,y quart order: 4*4*(height/4) 16-bit registry indices. */
    public short[] sampleBiomes(TerrainRequest request) {
        try (var arena = Arena.ofConfined()) {
            long size = request.height() * 4L;
            var output = arena.allocate(size * (long)Short.BYTES,Short.BYTES);
            check((int) sampleBiomes.invokeExact(encode(arena, request), output, size));
            return output.toArray(JAVA_SHORT);
        } catch (Throwable error) { throw failure(error); }
    }

    public short[] column(TerrainRequest request, int index) {
        try (var arena = Arena.ofConfined()) {
            var blocks = arena.allocate(request.height() * (long) Short.BYTES, Short.BYTES);
            check((int) column.invokeExact(encode(arena, request), index, blocks));
            return blocks.toArray(JAVA_SHORT);
        } catch (Throwable error) { throw failure(error); }
    }

    /** Diagnostic sparse [x,z,recipe,modifier] tuples, using the production GPU sampler. */
    int[] decorationCounts(TerrainRequest request, int[] points) {
        if (points.length % 4 != 0) throw new IllegalArgumentException("Expected four integers per feature point");
        if (points.length == 0) return new int[0];
        try (var arena = Arena.ofConfined()) {
            long count = points.length / 4;
            var output = arena.allocate(count * Integer.BYTES, Integer.BYTES);
            check((int) decorationCounts.invokeExact(encode(arena, request), arena.allocateFrom(JAVA_INT, points), count, output));
            return output.toArray(JAVA_INT);
        } catch (Throwable error) { throw failure(error); }
    }

    int[] decorationFeature(TerrainRequest request,int recipe,int[] position,long seed) {
        return decorationSample(decorationFeature,request,recipe,position,seed);
    }
    int[] decorationPlacement(TerrainRequest request,int recipe,int[] position,long seed) {
        return decorationSample(decorationPlacement,request,recipe,position,seed);
    }
    private int[] decorationSample(MethodHandle handle,TerrainRequest request,int recipe,int[] position,long seed) {
        if(position.length!=3)throw new IllegalArgumentException("Expected an x,y,z feature position");
        try(var arena=Arena.ofConfined()) {
            long capacity=Math.max(4096,request.height()*4L);
            var out=arena.allocate(capacity*16,Integer.BYTES);var length=arena.allocate(JAVA_LONG);
            check((int)handle.invokeExact(encode(arena,request),recipe,arena.allocateFrom(JAVA_INT,position),seed,out,capacity,length));
            return out.asSlice(0,length.get(JAVA_LONG,0)*16).toArray(JAVA_INT);
        }catch(Throwable error){throw failure(error);}
    }

    private static Columns decodeColumns(MemorySegment data) {
        int count = Math.toIntExact(data.byteSize() / 12);
        var heights = new int[count];
        var packed = new int[count];
        var materials = new int[count];
        for (int i = 0; i < count; i++) {
            heights[i] = data.get(JAVA_INT, i * 12L);
            packed[i] = data.get(JAVA_INT, i * 12L + 4);
            materials[i] = data.get(JAVA_INT, i * 12L + 8);
        }
        return new Columns(heights, packed, materials);
    }

    public record Columns(int[] heights, int[] packed, int[] materials) {
        public Columns(int[] heights, int[] packed) {this(heights,packed,new int[heights.length]);}
        public int biome(int index) { return packed[index] & 65535; }
    }

    /** Caller must own the destination's Minecraft I/O queue, with its region handle closed. */
    public RegionReport generateRegion(TerrainRequest request, Path destination, int dataVersion, String biome) {
        try (var arena = Arena.ofConfined()) {
            var pathBytes = destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var biomeBytes = biome.getBytes(StandardCharsets.UTF_8);
            var path = arena.allocateFrom(JAVA_BYTE, pathBytes);
            var biomeName = arena.allocateFrom(JAVA_BYTE, biomeBytes);
            var report = arena.allocate(40 + NativeTimings.BYTES, Long.BYTES);
            check((int) profiledRegion.invokeExact(encode(arena, request), path, (long) pathBytes.length,
                    dataVersion, biomeName, (long) biomeBytes.length, report, MemorySegment.NULL));
            return decodeRegionReport(report);
        } catch (Throwable error) {
            throw failure(error);
        }
    }

    /** Caller owns a temporary destination. Returns the same GPU columns used by the MCA writer. */
    public RegionData generateRegionColumns(TerrainRequest request, Path destination, int dataVersion, String biome) {
        try (var arena = Arena.ofConfined()) {
            var pathBytes = destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var biomeBytes = biome.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(1024L * 256 * 12, Integer.BYTES);
            var report = arena.allocate(40 + NativeTimings.BYTES, Long.BYTES);
            check((int) profiledRegion.invokeExact(encode(arena, request), arena.allocateFrom(JAVA_BYTE, pathBytes), (long) pathBytes.length,
                    dataVersion, arena.allocateFrom(JAVA_BYTE, biomeBytes), (long) biomeBytes.length, report, output));
            return new RegionData(decodeRegionReport(report), decodeColumns(output));
        } catch (Throwable error) { throw failure(error); }
    }

    public record RegionData(RegionReport report, Columns columns) { }

    /** Copy cached compressed records into missing save slots while preserving existing chunks. */
    public RegionReport publishCachedRegion(TerrainRequest request, Path destination, Path cached) {
        try (var arena = Arena.ofConfined()) {
            var pathBytes = destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var cachedBytes = cached.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var report = arena.allocate(40, Long.BYTES);
            check((int) publishRegionCache.invokeExact(encode(arena, request), arena.allocateFrom(JAVA_BYTE, pathBytes), (long) pathBytes.length,
                    arena.allocateFrom(JAVA_BYTE, cachedBytes), (long) cachedBytes.length, report));
            return new RegionReport(report.get(JAVA_INT, 0), report.get(JAVA_INT, 4),
                    report.get(JAVA_LONG, 8), report.get(JAVA_LONG, 16), report.get(JAVA_LONG, 24), report.get(JAVA_LONG, 32));
        } catch (Throwable error) { throw failure(error); }
    }

    public record RegionReport(int generated, int preserved, long gpuNanos, long assemblyNanos,
                               long writeNanos, long bytes, NativeTimings stages) {
        public RegionReport(int generated, int preserved, long gpuNanos, long assemblyNanos, long writeNanos, long bytes) {
            this(generated,preserved,gpuNanos,assemblyNanos,writeNanos,bytes,NativeTimings.EMPTY);
        }
    }

    private static MemorySegment encode(Arena arena, TerrainRequest request) {
        var data = arena.allocate(40, Long.BYTES);
        data.set(JAVA_LONG, 0, request.seed());
        data.set(JAVA_INT, 8, request.chunkX());
        data.set(JAVA_INT, 12, request.chunkZ());
        data.set(JAVA_INT, 16, request.minY());
        data.set(JAVA_INT, 20, request.height());
        data.set(JAVA_FLOAT, 24, request.baseHeight());
        data.set(JAVA_FLOAT, 28, request.amplitude());
        data.set(JAVA_FLOAT, 32, request.frequency());
        data.set(JAVA_INT, 36, request.profile());
        return data;
    }

    private void check(int status) throws Throwable {
        if (status != 0) {
            throw new IllegalStateException("Retina GPU generation failed: " + message(lastError));
        }
    }

    private static String message(MethodHandle call) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var bytes = arena.allocate(4096);
            long length = (long) call.invokeExact(bytes, 4096L);
            return new String(bytes.asSlice(0, length).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }

    private static Path extractLibrary() {
        String override = System.getProperty("retina.native.path");
        if (override != null) {
            return Path.of(override).toAbsolutePath();
        }
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "macos" : osName.contains("win") ? "windows" : "linux";
        String archName = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String arch = switch (archName) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> archName;
        };
        String name = System.mapLibraryName("retina_worldgen");
        String resource = "/natives/" + os + "-" + arch + "/" + name;
        try (var stream = NativeTerrain.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Native library is not packaged for " + os + "-" + arch + ": " + resource);
            }
            Path directory = Files.createTempDirectory("retina-native-");
            Path library = directory.resolve(name);
            Files.copy(stream, library);
            directory.toFile().deleteOnExit();
            library.toFile().deleteOnExit();
            return library;
        } catch (IOException error) {
            throw new IllegalStateException("Could not load Retina's native terrain library", error);
        }
    }

    private static RuntimeException failure(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new IllegalStateException("Native GPU call failed", error);
    }

    public record ChunkData(Arena arena, MemorySegment blocks, Columns columns) implements AutoCloseable {
        public int[] heights() { return columns.heights(); }

        @Override
        public void close() {
            arena.close();
        }
    }
}
