package art.arcane.retina.worldgen;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded raw transport to one persistent stock-runtime Bend process.
 * No terrain, noise, palette, NBT or compression algorithm runs in this bridge.
 */
public final class BendWorker implements AutoCloseable {
    public enum Execution { CPU, GPU_REQUIRED }
    private static final int MAGIC = 0x52424e44;
    private static final int MAX_REQUEST = 16 * 1024 * 1024;
    private static final int MAX_RESPONSE = 17 * 1024 * 1024;
    private static final int QUEUE_LIMIT = 16;
    private static final int BYTE_BUDGET = 32 * 1024 * 1024;
    private final Process process;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ThreadPoolExecutor transport;
    private final Semaphore bytes = new Semaphore(BYTE_BUDGET);
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextId = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final StringBuilder diagnostics = new StringBuilder();
    private final Execution execution;

    public BendWorker(Path executable, Execution execution, int threads) throws IOException {
        if (threads < 1 || threads > 64) throw new IllegalArgumentException("Bend threads must be 1..64");
        this.execution = java.util.Objects.requireNonNull(execution);
        var command = List.of("nice", "-n", "10", executable.toAbsolutePath().toString(),
                execution == Execution.CPU ? "cpu" : "gpu", "--threads", Integer.toString(threads),
                "--gpu", execution == Execution.CPU ? "off" : "on");
        var builder = new ProcessBuilder(command);
        builder.environment().put("BEND_NO_TELEMETRY", "1");
        process = builder.start();
        input = new DataInputStream(process.getInputStream());
        output = new DataOutputStream(process.getOutputStream());
        transport = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_LIMIT), runnable ->
                Thread.ofPlatform().daemon().name("retina-bend-transport").unstarted(runnable));
        Thread.ofPlatform().daemon().name("retina-bend-diagnostics").start(this::drainDiagnostics);
        try {
            var hello = new DataInputStream(new java.io.ByteArrayInputStream(request(0, new byte[0])
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)));
            if (hello.readInt() != 1 || hello.readInt() != 2 || hello.readInt() != 0 || hello.readInt() != 36
                    || hello.readInt() != (execution == Execution.CPU ? 0 : 1) || hello.available() != 0)
                throw new IOException("Unexpected Bend worker protocol/version/execution handshake");
        } catch (Exception failure) {
            close();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Bend worker startup failed: " + diagnosticTail(), failure);
        }
    }

    public Execution execution() { return execution; }
    public long processId() { return process.pid(); }

    /** The caller retains this staged raw registry file until acknowledgement.
     * Cancellation may still drain an active read: in that case retain the file
     * until this worker closes. Bend owns the snapshot after acknowledgement;
     * no large payload enters the queue. Production staging ownership is shared
     * integration work, not provided by this raw transport convenience method.
     */
    public CompletableFuture<byte[]> loadProfile(Path stagedFile) {
        int[] points = stagedFile.toAbsolutePath().toString().codePoints().toArray();
        var payload = java.nio.ByteBuffer.allocate(4 + points.length * 4);
        payload.putInt(points.length);
        for (int point : points) payload.putInt(point);
        return request(5, payload.array());
    }

    /** Select a resident profile's climate stack (0..3) or ridge stack (4).
     * The bridge carries seed words and the index; Bend resolves and converts
     * registry parameters. A rejected selection preserves the prepared stack.
     */
    public CompletableFuture<byte[]> prepareProfileNoise(long seed, int channel) {
        var payload = java.nio.ByteBuffer.allocate(12);
        payload.putInt((int) seed).putInt((int) (seed >>> 32)).putInt(channel);
        return request(8, payload.array());
    }

    /** Bend resolves the resident target table and biome flags and builds its
     * reusable climate index. Acknowledgement contains target and biome counts.
     * Accepting a replacement profile invalidates this index until preparation.
     */
    public CompletableFuture<byte[]> prepareProfileClimate() {
        return request(9, new byte[0]);
    }

    /** Cancellation skips queued requests; in-flight responses are drained to
     * preserve framing. World shutdown closes the worker and terminates it.
     */
    public CompletableFuture<byte[]> request(int opcode, byte[] payload) {
        var result = new CompletableFuture<byte[]>();
        if (payload.length > MAX_REQUEST) {
            result.completeExceptionally(new IOException("Bend request exceeds 16 MiB"));
            return result;
        }
        if (closed.get() || !bytes.tryAcquire(payload.length)) {
            result.completeExceptionally(new IOException(closed.get() ? "Bend worker is closed" : "Bend byte budget is full"));
            return result;
        }
        var job = new Job(nextId.incrementAndGet(), opcode, payload.clone(), result);
        jobs.add(job);
        try {
            transport.execute(job);
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            job.fail(new IOException("Bend request queue is closed or full", failure));
            job.release();
        }
        return result;
    }

    private final class Job implements Runnable {
        final int id, opcode;
        final byte[] payload;
        final CompletableFuture<byte[]> result;
        final AtomicBoolean released = new AtomicBoolean();

        Job(int id, int opcode, byte[] payload, CompletableFuture<byte[]> result) {
            this.id = id; this.opcode = opcode; this.payload = payload; this.result = result;
        }

        @Override public void run() {
            try {
                if (result.isDone()) return;
                if (closed.get()) throw new IOException("Bend worker is closed");
                output.writeInt(MAGIC);
                output.writeInt(payload.length);
                output.writeInt(id);
                output.writeInt(opcode);
                output.write(payload);
                output.flush();
                int magic = input.readInt(), length = input.readInt(), receivedId = input.readInt();
                int receivedOpcode = input.readInt(), status = input.readInt();
                if (magic != MAGIC || length < 0 || length > MAX_RESPONSE || receivedId != id || receivedOpcode != opcode
                        || status < 0 || status > 1)
                    throw new IOException("Invalid Bend response frame for request " + id);
                byte[] body = input.readNBytes(length);
                if (body.length != length) throw new IOException("Bend worker ended within a response");
                if (status == 0) result.complete(body);
                else {
                    String detail = body.length == 4 ? Integer.toUnsignedString(java.nio.ByteBuffer.wrap(body).getInt())
                            : "invalid error payload";
                    fail(new IOException("Bend opcode " + opcode + " rejected request " + id + " (code " + detail + ")"));
                }
            } catch (IOException failure) {
                fail(new IOException("Bend transport failed: " + diagnosticTail(), failure));
                close();
            } finally {
                release();
            }
        }

        void fail(IOException failure) { result.completeExceptionally(failure); }
        void release() {
            if (released.compareAndSet(false, true)) {
                jobs.remove(this);
                bytes.release(payload.length);
            }
        }
    }

    private void drainDiagnostics() {
        try (var error = process.getErrorStream()) {
            byte[] buffer = new byte[1024];
            for (int n; (n = error.read(buffer)) >= 0;) synchronized (diagnostics) {
                diagnostics.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                if (diagnostics.length() > 8192) diagnostics.delete(0, diagnostics.length() - 8192);
            }
        } catch (IOException ignored) {
            // Closing/terminating the worker closes its diagnostic stream.
        }
    }

    private String diagnosticTail() {
        synchronized (diagnostics) { return diagnostics.toString(); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var failure = new IOException("Bend worker closed");
        jobs.forEach(job -> job.fail(failure));
        for (var abandoned : transport.shutdownNow()) if (abandoned instanceof Job job) job.release();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try { input.close(); } catch (IOException ignored) { }
        try { output.close(); } catch (IOException ignored) { }
    }
}
