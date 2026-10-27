package art.arcane.retina.worldgen;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/** JDK-only definition bridge. Rust owns parsing and saved generation settings. */
public final class NativeWorldDefinition implements AutoCloseable {
    public enum ProgramExecution { AUTO, INTERPRETER, SPECIALIZED }
    private long handle;
    private final String metadata;

    private NativeWorldDefinition(long handle, String metadata) {
        this.handle = handle; this.metadata = metadata;
    }

    private static final class Calls {
        private static final Linker LINKER = Linker.nativeLinker();
        private static MethodHandle call(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(NativeLibrary.symbols().findOrThrow(name), descriptor);
        }
        static final MethodHandle OPEN = call("retina_open_world_definition", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        static final MethodHandle METADATA = call("retina_world_definition_metadata", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        static final MethodHandle BIND = call("retina_bind_world_dimension", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        static final MethodHandle BIND_EXECUTION = call("retina_bind_world_dimension_execution", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle REQUEST = call("retina_world_dimension_request", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle CHUNK = call("retina_world_dimension_chunk", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle PROFILE = call("retina_world_definition_profile_json", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        static final MethodHandle CLOSE = call("retina_close_world_definition", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final MethodHandle FREE = call("retina_free_bytes", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        static final MethodHandle ERROR = call("retina_last_error", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
    }

    /** Returns null only when Rust's actual file open reports absence. No GPU is started. */
    public static NativeWorldDefinition tryOpen(Path path) {
        long handle = 0;
        try (var arena = Arena.ofConfined()) {
            var bytes = path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_LONG);
            check((int) Calls.OPEN.invokeExact(arena.allocateFrom(JAVA_BYTE,bytes), (long) bytes.length, output));
            handle = output.get(JAVA_LONG,0);
            if (handle == 0) return null;
            long opened = handle;
            String metadata = readBytes((pointer,length) -> (int) Calls.METADATA.invokeExact(opened,pointer,length));
            return new NativeWorldDefinition(handle,metadata);
        } catch (Throwable error) {
            if (handle != 0) try { check((int) Calls.CLOSE.invokeExact(handle)); }
            catch (Throwable close) { error.addSuppressed(close); }
            throw failure(error);
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
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_INT);
            check((int) Calls.BIND.invokeExact(handle, arena.allocateFrom(JAVA_BYTE,bytes), (long) bytes.length, compression, output));
            return output.get(JAVA_INT,0);
        } catch (Throwable error) { throw failure(error); }
    }

    /** Dispatch preference is fixed for this binding without changing the saved definition. */
    public synchronized int bindDimension(String key,int compression,ProgramExecution execution) {
        ensureOpen();
        java.util.Objects.requireNonNull(execution,"program execution");
        try(var arena=Arena.ofConfined()) {
            var bytes=key.getBytes(StandardCharsets.UTF_8);var output=arena.allocate(JAVA_INT);
            check((int)Calls.BIND_EXECUTION.invokeExact(handle,arena.allocateFrom(JAVA_BYTE,bytes),(long)bytes.length,
                    compression,execution.ordinal(),output));
            return output.get(JAVA_INT,0);
        } catch(Throwable error) {throw failure(error);}
    }

    /** Seed and all geometry fields are populated by Rust from the saved definition. */
    public synchronized TerrainRequest request(String key, int x, int z) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(40,Long.BYTES);
            check((int) Calls.REQUEST.invokeExact(handle, arena.allocateFrom(JAVA_BYTE,bytes), (long) bytes.length, x, z, output));
            return new TerrainRequest(output.get(JAVA_LONG,0),output.get(JAVA_INT,8),output.get(JAVA_INT,12),
                    output.get(JAVA_INT,16),output.get(JAVA_INT,20),output.get(JAVA_FLOAT,24),
                    output.get(JAVA_FLOAT,28),output.get(JAVA_FLOAT,32),output.get(JAVA_INT,36));
        } catch (Throwable error) { throw failure(error); }
    }

    /** Generate storage-shaped uncompressed chunk NBT using the loaded world. */
    public byte[] chunk(String key,int x,int z) {
        long opened;
        synchronized (this) { ensureOpen(); opened=handle; }
        // Native lookup acquires a dimension lease for the entire call. Do not
        // serialize independent generation jobs on this Java object's monitor.
        try (var arena=Arena.ofConfined()) {
            var bytes=key.getBytes(StandardCharsets.UTF_8);
            var input=arena.allocateFrom(JAVA_BYTE,bytes);
            return readBuffer((pointer,length) -> (int) Calls.CHUNK.invokeExact(opened,input,(long)bytes.length,x,z,pointer,length));
        } catch (Throwable error) { throw failure(error); }
    }

    /** Explicit QA/diagnostic output, decoded from the same loaded file without rereading it. */
    public synchronized String diagnosticProfile(String key) {
        ensureOpen();
        try (var arena = Arena.ofConfined()) {
            var bytes = key.getBytes(StandardCharsets.UTF_8);
            var input = arena.allocateFrom(JAVA_BYTE,bytes);
            return readBytes((pointer,length) -> (int) Calls.PROFILE.invokeExact(handle,input,(long) bytes.length,pointer,length));
        } catch (Throwable error) { throw failure(error); }
    }

    /** Stop admitting generation and drain outstanding work before closing the world. */
    @Override public synchronized void close() {
        if (handle == 0) return;
        try { check((int) Calls.CLOSE.invokeExact(handle)); handle = 0; }
        catch (Throwable error) { throw failure(error); }
    }

    private void ensureOpen() {
        if (handle == 0) throw new IllegalStateException("World definition is closed");
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
