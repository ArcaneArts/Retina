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

    /** Bend projects and validates the resident numeric climate, surface and
     * final-density programs. The four acknowledgement words are program,
     * noise, spline-point and interpolation-field counts. A replacement profile
     * invalidates the prepared model. This method transports no generated data.
     */
    public CompletableFuture<byte[]> prepareProfileDensity() {
        return request(11, new byte[0]);
    }

    /** Build one resident density lattice. Bend aligns the inclusive bounds to
     * the global lattice, validates its capacity and generates the samples.
     * Only dimensions/count return; values stay in the worker for opcode 14.
     * Preparing/reloading a profile invalidates this cache.
     */
    public CompletableFuture<byte[]> prepareDensityLattice(int program, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ, int horizontalStep, int verticalStep, long seed) {
        var payload = java.nio.ByteBuffer.allocate(44).putInt(program)
                .putInt(minX).putInt(minY).putInt(minZ).putInt(maxX).putInt(maxY).putInt(maxZ)
                .putInt(horizontalStep).putInt(verticalStep).putInt((int) seed).putInt((int) (seed >>> 32));
        return request(13, payload.array());
    }

    /** Resolve world limits and terrain cell spacing from the loaded registry.
     * Numeric preparation must already exist. The six acknowledgement words
     * are min Y, height, sea level, horizontal/vertical cells and height mode.
     */
    public CompletableFuture<byte[]> prepareProfileSurface() {
        return request(15, new byte[0]);
    }

    /** Ask Bend for the raw opcode-13 descriptor needed by this surface tile.
     * Submit that descriptor, then generateSurfaceColumns. Coordination must
     * serialize this build sequence against other cache replacements; this
     * transport helper does not provide a region scheduler.
     */
    public CompletableFuture<byte[]> surfaceDensityDescriptor(int x, int z, int width, int depth, long seed) {
        return request(18, surfaceTilePayload(x, z, width, depth, seed));
    }

    /** GPU surface scan, spatial climate evaluation and registered biome lookup.
     * A matching density lattice and prepared climate/surface profile are
     * required. The acknowledgement is width, depth and column count; computed
     * columns remain resident for bounded raw opcode-17 query batches.
     */
    public CompletableFuture<byte[]> generateSurfaceColumns(int x, int z, int width, int depth, long seed) {
        return request(16, surfaceTilePayload(x, z, width, depth, seed));
    }

    /** Finalize resident surface heights against exact solid voxels and select
     * registered coastal alternatives near actual land/water transitions.
     * Request the density descriptor with six extra X/Z columns on each side
     * when the climate registry contains shore targets. No terrain readback to Java is
     * needed. The acknowledgement is width, depth and column count; success
     * invalidates derived block columns while preserving the chunk catalog.
     */
    public CompletableFuture<byte[]> finalizeShorelineColumns() {
        return request(29, new byte[0]);
    }

    private static byte[] surfaceTilePayload(int x, int z, int width, int depth, long seed) {
        return java.nio.ByteBuffer.allocate(24).putInt(x).putInt(z).putInt(width).putInt(depth)
                .putInt((int) seed).putInt((int) (seed >>> 32)).array();
    }

    /** Bend projects all per-biome material DAGs, terracotta bands, sea level
     * and layer mode from the loaded registry. Numeric preparation is required.
     * The acknowledgement is program count, band count, sea level and layer flag.
     * Raw opcode-20 batches supply coordinates/seeds and eight context values;
     * material selection and numerical evaluation remain in Bend.
     */
    public CompletableFuture<byte[]> prepareProfileMaterials() {
        return request(19, new byte[0]);
    }

    /** Resolve the base palette and three registered material-context noises.
     * Numeric/material preparation is required. The six acknowledgement words
     * are stone, water, palette count, depth/secondary/band noise IDs.
     */
    public CompletableFuture<byte[]> prepareProfileBlocks() {
        return request(21, new byte[0]);
    }

    /** Derive material context and compact vertical block runs in Bend on the
     * selected CPU/GPU backend. The current surface tile needs a matching
     * density lattice covering one extra X/Z column on every side for slopes.
     * Only width, depth, column count and total run count return to Java.
     * Raw opcodes 23/24 query bounded voxels/runs without regenerating them.
     */
    public CompletableFuture<byte[]> generateBlockColumns() {
        return request(22, new byte[0]);
    }

    /** Project registered block names/properties, biome IDs and the six actual
     * heightmap predicates in Bend. Requires the prepared block model. The two
     * acknowledgement words are material and biome counts; no NBT is built here.
     */
    public CompletableFuture<byte[]> prepareProfileChunks() {
        return request(25, new byte[0]);
    }

    /** Encode a chunk fully covered by the resident generated block tile.
     * Bend owns block/biome packing, heightmaps, NBT and optional zlib. Seed,
     * chunk coordinates and the running game's DataVersion are raw metadata.
     * This component currently emits FEATURES status without precomputed light;
     * final generation and region scheduling are separate integration work.
     */
    public CompletableFuture<byte[]> encodeGeneratedChunk(int chunkX, int chunkZ, long seed,
            int dataVersion, boolean compressed) {
        var payload = java.nio.ByteBuffer.allocate(20).putInt(chunkX).putInt(chunkZ)
                .putInt((int) seed).putInt((int) (seed >>> 32)).putInt(dataVersion);
        return request(compressed ? 27 : 26, payload.array());
    }

    /** Write all 1024 generated chunks from one covered resident tile. Bend owns
     * chunk serialization, compression, sector allocation and the file bytes.
     * The caller MUST supply a private staged file, never an existing region,
     * and retain its ownership until this request completes or the worker exits.
     * Only successful acknowledgement (chunk count, sector count) permits later
     * publication. Cancellation can still drain an active write; deletion must
     * wait for worker shutdown. File IO errors terminate this component worker.
     */
    public CompletableFuture<byte[]> writeGeneratedRegion(int regionX, int regionZ, long seed,
            int dataVersion, int timestamp, Path stagedFile) {
        int[] points = stagedFile.toAbsolutePath().toString().codePoints().toArray();
        var payload = java.nio.ByteBuffer.allocate(28 + points.length * 4)
                .putInt(regionX).putInt(regionZ).putInt((int) seed).putInt((int) (seed >>> 32))
                .putInt(dataVersion).putInt(timestamp).putInt(points.length);
        for (int point : points) payload.putInt(point);
        return request(28, payload.array());
    }

    /** Project registered cave noise, per-biome carvers, carveable materials and
     * lava/world-height metadata in Bend. Requires numeric/material/block models.
     * Eight raw acknowledgement words: noise, biome, carver and material counts,
     * lava ID, lava level, minimum Y and height. Successful preparation invalidates
     * generated block columns and cave fields. Generate fields and carve columns
     * explicitly after regenerating the base block snapshot.
     */
    public CompletableFuture<byte[]> prepareProfileGeology() {
        return request(30, new byte[0]);
    }

    /** Sample all six registered cave channels using the selected Bend CPU/GPU
     * backend. Raw batches contain count followed by x/y/z/seed-low/seed-high
     * words per query; each result contains six F32 words. No Java noise work.
     */
    public CompletableFuture<byte[]> sampleCaveNoise(byte[] batch) {
        return request(31, batch);
    }

    /** Read bounded projected biome-carver records for diagnostics. The raw
     * payload is count followed by biome IDs; Bend retains all typed records.
     */
    public CompletableFuture<byte[]> queryBiomeCarvers(byte[] batch) {
        return request(32, batch);
    }

    /** Build globally aligned cave density/tunnel/ravine fields over the
     * resident surface tile, entirely in Bend. Returns eight layout words.
     */
    public CompletableFuture<byte[]> generateCaveLattice() {
        return request(33, new byte[0]);
    }

    /** Query the resident four-channel cave field using raw five-word
     * x/y/z/seed queries. Results are presence plus four F32 words.
     */
    public CompletableFuture<byte[]> queryCaveFields(byte[] batch) {
        return request(34, batch);
    }

    /** Query the resident discrete four-block cave-biome volume. Raw five-word
     * queries match queryCaveFields; replies are presence and biome ID. A present
     * -1 ID inherits the actual column's finalized surface biome, preserving shores.
     * IDs and field values stay resident during carving and NBT/MCA encoding.
     */
    public CompletableFuture<byte[]> queryCaveBiomes(byte[] batch) {
        return request(36, batch);
    }

    /** Carve the resident generated columns using cached cave fields and
     * registered materials/carvers on the selected Bend CPU/GPU backend.
     */
    public CompletableFuture<byte[]> carveColumns() {
        return request(35, new byte[0]);
    }

    /** Prepare the registered aquifer graph range and surface-search settings
     * in Bend after geology preparation. Seven acknowledgement words: enabled,
     * program, surface bottom/step/mode, world minimum and height. Missing aquifer
     * metadata means disabled; invalid metadata fails without replacing state.
     */
    public CompletableFuture<byte[]> prepareProfileAquifer() {
        return request(37, new byte[0]);
    }

    /** Query flooding, erosion, spread, lava, barrier and preliminary surface
     * height on the selected Bend CPU/GPU backend. Raw five-word XYZ/seed queries
     * return six F32 values each. Java performs no graph or surface search work.
     */
    public CompletableFuture<byte[]> sampleAquiferFields(byte[] batch) {
        return request(38, batch);
    }

    /** Cache registered preliminary surfaces, randomized fluid centers and
     * interpolated barrier fields in Bend on the selected CPU/GPU backend.
     * Requires generated block columns, chunk catalog and aquifer preparation.
     * Replies contain center and barrier-vertex counts. Disabled profiles
     * return zero counts and use global fluid levels when carving.
     */
    public CompletableFuture<byte[]> generateAquiferLattice() {
        return request(39, new byte[0]);
    }

    /** Query cached cavity substance decisions without altering block data.
     * Raw five-word XYZ/seed queries return presence and class: air (0),
     * registered default fluid (1), lava (2) or solid pressure barrier (3).
     * The cavity's density comes from the resident cave field. This does not
     * classify whether the point itself is a cavity; carving decides that.
     */
    public CompletableFuture<byte[]> queryAquiferSubstance(byte[] batch) {
        return request(40, batch);
    }

    /** Read registered lake budgets/barriers for raw biome-ID batches. Each
     * result contains total/lava F32 chances, two material IDs and biome flags.
     * Geology preparation projects these settings in Bend.
     */
    public CompletableFuture<byte[]> queryLakeSettings(byte[] batch) {
        return request(41, batch);
    }

    /** Sample globally seeded lake candidates from raw X/Z/seed batches.
     * Bend owns climate selection, positions, radii, eligibility and materials.
     * This does not carve basins or modify resident terrain.
     */
    public CompletableFuture<byte[]> sampleLakeCandidates(byte[] batch) {
        return request(42, batch);
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
