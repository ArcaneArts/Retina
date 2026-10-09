package art.arcane.retina.worldgen;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

/** Actual worker transport and lifecycle checks; no placement logic in Java. */
public final class BendOrePlacementWorkerTest {
    private static final long SEED = 0x8000000187654321L;

    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args[0]), profile = Path.of(args[1]);
        for (var execution : BendWorker.Execution.values()) {
            long pid;
            try (var worker = new BendWorker(binary, execution, 2)) {
                pid = worker.processId();
                rejected(worker.applyOreCore(0, 0, 16, 16, SEED), 755);
                await(worker.loadProfile(profile));
                await(worker.prepareProfileDensity());
                await(worker.prepareProfileMaterials());
                await(worker.prepareProfileBlocks());
                await(worker.prepareProfileGeology());
                await(worker.prepareProfileClimate());
                await(worker.prepareProfileSurface());
                await(worker.prepareProfileChunks());
                base(worker, true);
                byte[] original = await(worker.encodeGeneratedChunk(0, 0, SEED, 5023, false));
                byte[] halo = await(worker.request(24, query(-1, -1)));
                byte[] ack = await(worker.applyOreCore(0, 0, 16, 16, SEED));
                var summary = ByteBuffer.wrap(ack);
                require(ack.length == 16 && summary.getInt() == 18 && summary.getInt() == 18
                        && summary.getInt() == 324 && summary.getInt() > 324, "resident ore summary");
                byte[] nbt = await(worker.encodeGeneratedChunk(0, 0, SEED, 5023, false));
                require(nbt.length > 100 && nbt[0] == 10 && !Arrays.equals(original, nbt), "ores reach actual saved NBT");
                require(Arrays.equals(halo, await(worker.request(24, query(-1, -1)))), "exposure halo stays unchanged");
                require(Arrays.equals(nbt, inflate(await(worker.encodeGeneratedChunk(0, 0, SEED, 5023, true)))),
                        "ore-bearing zlib round trip");
                var reads = new ArrayList<CompletableFuture<byte[]>>();
                for (int i = 0; i < 8; i++) reads.add(worker.encodeGeneratedChunk(0, 0, SEED, 5023, false));
                for (var read : reads) require(Arrays.equals(nbt, await(read)), "concurrent chunk readers");
                rejected(worker.request(50, new byte[] {0}), 603);
                rejected(worker.applyOreCore(1, 0, 16, 16, SEED), 756);
                rejected(worker.applyOreCore(0, 0, 16, 16, SEED ^ (1L << 32)), 756);
                require(Arrays.equals(nbt, await(worker.encodeGeneratedChunk(0, 0, SEED, 5023, false))),
                        "rejected ore requests preserve the snapshot");
                base(worker, false);
                rejected(worker.applyOreCore(0, 0, 16, 16, SEED), 756);
                // Queue the actual ordered build and application sequence together.
                // A future region scheduler must hold this sequence as one lease.
                byte[] descriptor = await(worker.surfaceDensityDescriptor(-7, -7, 30, 30, SEED));
                var density = worker.request(13, descriptor);
                var surface = worker.generateSurfaceColumns(-1, -1, 18, 18, SEED);
                var coast = worker.finalizeShorelineColumns();
                var materials = worker.generateBlockColumns();
                var ore = worker.applyOreCore(0, 0, 16, 16, SEED);
                var encoded = worker.encodeGeneratedChunk(0, 0, SEED, 5023, false);
                for (var stage : new CompletableFuture<?>[] {density, surface, coast, materials, ore})
                    stage.get(120, TimeUnit.SECONDS);
                require(Arrays.equals(nbt, await(encoded)), "ordered queued rebuild is repeatable");
                await(worker.loadProfile(profile));
                rejected(worker.applyOreCore(0, 0, 16, 16, SEED), 755);
                rejected(worker.encodeGeneratedChunk(0, 0, SEED, 5023, false), 716);
            }
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
            require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "ore worker terminates");
        }
        System.out.println("QA_EVT {\"event\":\"bend_ore_placement_worker\",\"status\":\"pass\","
                + "\"context\":{\"executions\":2,\"concurrent_readers\":8,\"queued_build\":true,"
                + "\"saved_nbt_zlib\":true,\"halo_preserved\":true,\"reload_shutdown\":true}}");
    }

    private static void base(BendWorker worker, boolean halo) throws Exception {
        byte[] descriptor = await(worker.surfaceDensityDescriptor(-7, -7, 30, 30, SEED));
        await(worker.request(13, descriptor));
        await(worker.generateSurfaceColumns(halo ? -1 : 0, halo ? -1 : 0, halo ? 18 : 16, halo ? 18 : 16, SEED));
        await(worker.finalizeShorelineColumns());
        await(worker.generateBlockColumns());
    }

    private static byte[] query(int x, int z) {
        return ByteBuffer.allocate(20).putInt(1).putInt(x).putInt(z)
                .putInt((int) SEED).putInt((int) (SEED >>> 32)).array();
    }

    private static byte[] inflate(byte[] bytes) throws Exception {
        var decoder = new Inflater();
        try {
            decoder.setInput(bytes);
            var result = new ByteArrayOutputStream();
            var buffer = new byte[8192];
            while (!decoder.finished()) {
                int count = decoder.inflate(buffer);
                require(count != 0 || decoder.finished(), "complete zlib input");
                result.write(buffer, 0, count);
            }
            require(decoder.getRemaining() == 0, "no trailing zlib data");
            return result.toByteArray();
        } finally { decoder.end(); }
    }

    private static byte[] await(CompletableFuture<byte[]> value) throws Exception {
        return value.get(120, TimeUnit.SECONDS);
    }

    private static void rejected(CompletableFuture<byte[]> value, int code) throws Exception {
        try { await(value); throw new AssertionError("expected rejection " + code); }
        catch (ExecutionException e) {
            require(e.getCause() instanceof java.io.IOException
                    && e.getCause().getMessage().contains("code " + code + ")"), "remote error code " + code);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
