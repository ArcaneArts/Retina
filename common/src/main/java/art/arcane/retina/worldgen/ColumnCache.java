package art.arcane.retina.worldgen;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import static java.lang.foreign.ValueLayout.*;
import java.util.zip.InflaterInputStream;

/** Existing .columns.z format: zlib containing big-endian height/packed/material triples. */
final class ColumnCache {
    static final int REGION_COLUMNS = 1024 * 256;
    private static final int BATCH_COLUMNS = 4096;
    private static final int ZIP_BUFFER = 64 * 1024;

    record WriteStats(long elapsedNanos, long encodeNanos, long compressNanos, long writeNanos, long bytes) { }

    static WriteStats writeNative(Path path, MemorySegment columns, long count,
                                  MethodHandle write, MethodHandle lastError) throws Throwable {
        long start = System.nanoTime();
        try (var arena = Arena.ofConfined()) {
            byte[] name = path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var report = arena.allocate(32, Long.BYTES);
            int status = (int) write.invokeExact(columns, count, arena.allocateFrom(JAVA_BYTE, name), (long) name.length, report);
            if (status != 0) {
                var error = arena.allocate(4096);
                long length = (long) lastError.invokeExact(error, 4096L);
                throw new IOException(new String(error.asSlice(0, length).toArray(JAVA_BYTE), StandardCharsets.UTF_8));
            }
            return new WriteStats(System.nanoTime() - start, report.get(JAVA_LONG, 0), report.get(JAVA_LONG, 8),
                    report.get(JAVA_LONG, 16), report.get(JAVA_LONG, 24));
        }
    }

    static void read(Path path, int[] heights, int[] packed, int[] materials) throws IOException {
        var bytes = new byte[BATCH_COLUMNS * 12];
        var buffer = ByteBuffer.wrap(bytes);
        var inflater = new java.util.zip.Inflater();
        try (var input = new InflaterInputStream(Files.newInputStream(path), inflater, ZIP_BUFFER)) {
            for (int first = 0; first < heights.length; first += BATCH_COLUMNS) {
                int end = Math.min(first + BATCH_COLUMNS, heights.length);
                int length = (end - first) * 12;
                if (input.readNBytes(bytes, 0, length) != length) throw new EOFException("Truncated column cache: " + path);
                buffer.clear();
                for (int i = first; i < end; i++) {
                    heights[i] = buffer.getInt(); packed[i] = buffer.getInt(); materials[i] = buffer.getInt();
                }
            }
            if (input.read() != -1) throw new IOException("Excess column cache data: " + path);
        } finally { inflater.end(); }
    }
}
