package art.arcane.retina.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/** FFM implementation of the shared world-definition operations. */
final class FfmDefinitionTransport implements DefinitionTransport {
    static final FfmDefinitionTransport INSTANCE = new FfmDefinitionTransport();

    private FfmDefinitionTransport() { }

    private static final class Calls {
        private static final Linker LINKER = Linker.nativeLinker();
        private static MethodHandle call(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(NativeLibrary.symbols().findOrThrow(name), descriptor);
        }
        static final MethodHandle OPEN = call("retina_open_world_definition", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle METADATA = call("retina_world_definition_metadata", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        static final MethodHandle BIND = call("retina_bind_world_dimension", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        static final MethodHandle BIND_LEVEL = call("retina_bind_world_dimension_compression_level",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle BIND_EXECUTION = call("retina_bind_world_dimension_execution", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle REQUEST = call("retina_world_dimension_request", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle CHUNK = call("retina_world_dimension_chunk", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle BIOMES = call("retina_sample_biomes_u16", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG));
        static final MethodHandle POINTS = call("retina_query_points", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        static final MethodHandle STRUCTURE_STARTS = call("retina_query_structure_starts",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle PROFILE = call("retina_world_definition_profile_json", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        static final MethodHandle CLOSE = call("retina_close_world_definition", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final MethodHandle FREE = call("retina_free_bytes", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        static final MethodHandle ERROR = call("retina_last_error", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
    }

    private static final class StructureRingCall {
        static final MethodHandle QUERY = Calls.call("retina_query_structure_rings",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    }

    private static final class CatalogCall {
        static final MethodHandle CREATE = Linker.nativeLinker().downcallHandle(
                NativeLibrary.symbols().findOrThrow("retina_create_world_definition"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
    }

    private static final class RegionCall {
        static final MethodHandle GENERATE = Calls.call("retina_world_dimension_region",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT,
                        ADDRESS, JAVA_LONG, ADDRESS));
    }

    private static final class PublicationCall {
        static final MethodHandle PUBLISH = Calls.call("retina_world_dimension_publish_region",
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT,
                        ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
    }

    @Override
    public long open(Path path) {
        try (var arena = Arena.ofConfined()) {
            var bytes = path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_LONG);
            check((int) Calls.OPEN.invokeExact(arena.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length, output));
            return output.get(JAVA_LONG, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public long create(Path catalog, String variant, long seed, Path worldRoot) {
        try (var arena = Arena.ofConfined()) {
            byte[] source = catalog.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            byte[] choice = variant.getBytes(StandardCharsets.UTF_8);
            byte[] destination = worldRoot.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_LONG);
            check((int) CatalogCall.CREATE.invokeExact(
                    arena.allocateFrom(JAVA_BYTE, source), (long) source.length,
                    arena.allocateFrom(JAVA_BYTE, choice), (long) choice.length, seed,
                    arena.allocateFrom(JAVA_BYTE, destination), (long) destination.length, output));
            return output.get(JAVA_LONG, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public String metadata(long handle) {
        try {
            return readBytes((pointer, length) -> (int) Calls.METADATA.invokeExact(handle, pointer, length));
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int bind(long handle, String key, int compression) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_INT);
            check((int) Calls.BIND.invokeExact(handle, arena.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length,
                    compression, output));
            return output.get(JAVA_INT, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int bind(long handle, String key, int compression, int compressionLevel) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_INT);
            check((int) Calls.BIND_LEVEL.invokeExact(handle, arena.allocateFrom(JAVA_BYTE, bytes),
                    (long) bytes.length, compression, compressionLevel, output));
            return output.get(JAVA_INT, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int bind(long handle, String key, int compression, NativeWorldDefinition.ProgramExecution execution) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_INT);
            check((int) Calls.BIND_EXECUTION.invokeExact(handle, arena.allocateFrom(JAVA_BYTE, bytes),
                    (long) bytes.length, compression, execution.ordinal(), output));
            return output.get(JAVA_INT, 0);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public TerrainRequest request(long handle, String key, int x, int z) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(40, Long.BYTES);
            check((int) Calls.REQUEST.invokeExact(handle, arena.allocateFrom(JAVA_BYTE, bytes), (long) bytes.length,
                    x, z, output));
            return new TerrainRequest(output.get(JAVA_LONG, 0), output.get(JAVA_INT, 8), output.get(JAVA_INT, 12),
                    output.get(JAVA_INT, 16), output.get(JAVA_INT, 20), output.get(JAVA_FLOAT, 24),
                    output.get(JAVA_FLOAT, 28), output.get(JAVA_FLOAT, 32), output.get(JAVA_INT, 36));
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public NativeWorldDefinition.RegionResult generateRegion(long handle, String key, int x, int z, Path destination) {
        try (var arena = Arena.ofConfined()) {
            byte[] dimension = key.getBytes(StandardCharsets.UTF_8);
            byte[] path = destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var report = arena.allocate(40, Long.BYTES);
            check((int) RegionCall.GENERATE.invokeExact(handle,
                    arena.allocateFrom(JAVA_BYTE, dimension), (long) dimension.length, x, z,
                    arena.allocateFrom(JAVA_BYTE, path), (long) path.length, report));
            return regionResult(report);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public NativeWorldDefinition.RegionResult publishRegion(long handle, String key, int x, int z, Path preview, Path destination) {
        try (var arena = Arena.ofConfined()) {
            byte[] dimension = key.getBytes(StandardCharsets.UTF_8);
            byte[] source = preview.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            byte[] path = destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var report = arena.allocate(40, Long.BYTES);
            check((int) PublicationCall.PUBLISH.invokeExact(handle,
                    arena.allocateFrom(JAVA_BYTE, dimension), (long) dimension.length, x, z,
                    arena.allocateFrom(JAVA_BYTE, source), (long) source.length,
                    arena.allocateFrom(JAVA_BYTE, path), (long) path.length, report));
            return regionResult(report);
        } catch (Throwable error) { throw failure(error); }
    }

    private static NativeWorldDefinition.RegionResult regionResult(MemorySegment report) {
        return new NativeWorldDefinition.RegionResult(Integer.toUnsignedLong(report.get(JAVA_INT, 0)),
                Integer.toUnsignedLong(report.get(JAVA_INT, 4)), report.get(JAVA_LONG, 32));
    }

    @Override
    public byte[] chunk(long handle, String key, int x, int z) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var input = arena.allocateFrom(JAVA_BYTE, bytes);
            return readBuffer((pointer, length) ->
                    (int) Calls.CHUNK.invokeExact(handle, input, (long) bytes.length, x, z, pointer, length));
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public short[] biomes(TerrainRequest request) {
        try (var arena = Arena.ofConfined()) {
            var encoded = encode(arena, request);
            long count = request.height() * 4L;
            var output = arena.allocate(count * Short.BYTES, Short.BYTES);
            check((int) Calls.BIOMES.invokeExact(encoded, output, count));
            return output.toArray(JAVA_SHORT);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int[] biomePoints(TerrainRequest request, int[] xyz) {
        try (var arena = Arena.ofConfined()) {
            long count = xyz.length / 3;
            var output = arena.allocate(count * Integer.BYTES, Integer.BYTES);
            check((int) Calls.POINTS.invokeExact(encode(arena, request), arena.allocateFrom(JAVA_INT, xyz),
                    count, 0, output));
            return output.toArray(JAVA_INT);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int[] structureStarts(TerrainRequest request, int[] candidates) {
        try (var arena = Arena.ofConfined()) {
            long count = candidates.length / 3;
            var output = arena.allocate(count * Integer.BYTES, Integer.BYTES);
            check((int) Calls.STRUCTURE_STARTS.invokeExact(encode(arena, request),
                    arena.allocateFrom(JAVA_INT, candidates), count, output));
            return output.toArray(JAVA_INT);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public int[] structureRingPositions(TerrainRequest request, int set, int count) {
        try (var arena = Arena.ofConfined()) {
            var output = count == 0 ? MemorySegment.NULL : arena.allocate(JAVA_INT, 2L * count);
            check((int) StructureRingCall.QUERY.invokeExact(encode(arena, request), set, count, output));
            return count == 0 ? new int[0] : output.toArray(JAVA_INT);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public String diagnosticProfile(long handle, String key) {
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var input = arena.allocateFrom(JAVA_BYTE, bytes);
            return readBytes((pointer, length) ->
                    (int) Calls.PROFILE.invokeExact(handle, input, (long) bytes.length, pointer, length));
        } catch (Throwable error) { throw failure(error); }
    }

    @Override
    public void close(long handle) {
        try { check((int) Calls.CLOSE.invokeExact(handle)); }
        catch (Throwable error) { throw failure(error); }
    }

    private static MemorySegment encode(Arena arena, TerrainRequest request) {
        var encoded = arena.allocate(40, Long.BYTES);
        encoded.set(JAVA_LONG, 0, request.seed());
        encoded.set(JAVA_INT, 8, request.chunkX()); encoded.set(JAVA_INT, 12, request.chunkZ());
        encoded.set(JAVA_INT, 16, request.minY()); encoded.set(JAVA_INT, 20, request.height());
        encoded.set(JAVA_FLOAT, 24, request.baseHeight()); encoded.set(JAVA_FLOAT, 28, request.amplitude());
        encoded.set(JAVA_FLOAT, 32, request.frequency()); encoded.set(JAVA_INT, 36, request.profile());
        return encoded;
    }

    @FunctionalInterface private interface BytesCall {
        int read(MemorySegment pointer, MemorySegment length) throws Throwable;
    }

    private static String readBytes(BytesCall call) throws Throwable {
        return new String(readBuffer(call),StandardCharsets.UTF_8);
    }

    private static byte[] readBuffer(BytesCall call) throws Throwable {
        try (var arena = Arena.ofConfined()) {
            var pointer = arena.allocate(ADDRESS); var length = arena.allocate(JAVA_LONG);
            check(call.read(pointer,length));
            var data = pointer.get(ADDRESS,0); long count = length.get(JAVA_LONG,0);
            try { return data.reinterpret(count).toArray(JAVA_BYTE); }
            finally { Calls.FREE.invokeExact(data,count); }
        }
    }

    private static void check(int status) throws Throwable {
        if (status == 0) return;
        try (var arena = Arena.ofConfined()) {
            var message = arena.allocate(4096);
            long size = (long) Calls.ERROR.invokeExact(message,4096L);
            throw new IllegalStateException("Retina definition call failed: "
                    + new String(message.asSlice(0,size).toArray(JAVA_BYTE),StandardCharsets.UTF_8));
        }
    }

    private static RuntimeException failure(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new IllegalStateException("Native definition call failed",error);
    }
}
