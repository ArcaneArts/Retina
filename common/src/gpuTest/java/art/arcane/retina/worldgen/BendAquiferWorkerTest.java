package art.arcane.retina.worldgen;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Actual transport/lifecycle checks; numeric algorithms stay in Bend. */
public final class BendAquiferWorkerTest {
    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args[0]), profile = Path.of(args[1]);
        byte[] query = ByteBuffer.allocate(24).putInt(1).putInt(16).putInt(-12).putInt(8)
                .putInt(0x87654321).putInt(0x80000001).array();
        for (var execution : BendWorker.Execution.values()) {
            long pid;
            try (var worker = new BendWorker(binary, execution, 2)) {
                pid = worker.processId();
                rejected(worker.prepareProfileAquifer(), 743);
                rejected(worker.sampleAquiferFields(query), 744);
                await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
                await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
                await(worker.prepareProfileGeology());
                byte[] metadata = await(worker.prepareProfileAquifer());
                require(metadata.length == 28 && ByteBuffer.wrap(metadata).getInt() == 1, "registered aquifer enabled");
                byte[] first = await(worker.sampleAquiferFields(query));
                var values = ByteBuffer.wrap(first);
                for (float expected : new float[] {.3125f, .5f, -1.5f, .75f, .09375f, 8f}) {
                    require(values.getFloat() == expected, "registered analytic aquifer field");
                }
                var reads = new ArrayList<CompletableFuture<byte[]>>();
                for (int i = 0; i < 8; i++) reads.add(worker.sampleAquiferFields(query));
                for (var read : reads) require(Arrays.equals(first, await(read)), "concurrent aquifer callers");
                rejected(worker.sampleAquiferFields(new byte[] {0}), 603);
                rejected(worker.request(37, new byte[] {0}), 603);
                require(Arrays.equals(first, await(worker.sampleAquiferFields(query))), "malformed commands preserve fields");
                await(worker.prepareProfileChunks());
                require(Arrays.equals(first, await(worker.sampleAquiferFields(query))), "catalog preparation retains aquifer");
                await(worker.prepareProfileGeology());rejected(worker.sampleAquiferFields(query), 744);
                await(worker.prepareProfileAquifer());
                require(Arrays.equals(first, await(worker.sampleAquiferFields(query))), "geology re-preparation requires explicit aquifer preparation");
                await(worker.loadProfile(profile));rejected(worker.sampleAquiferFields(query), 744);
                rejected(worker.prepareProfileAquifer(), 743);
                if (args.length > 2) {
                    await(worker.loadProfile(Path.of(args[2])));
                    await(worker.prepareProfileDensity());await(worker.prepareProfileMaterials());
                    await(worker.prepareProfileBlocks());await(worker.prepareProfileChunks());
                    await(worker.prepareProfileClimate());await(worker.prepareProfileSurface());
                    await(worker.prepareProfileGeology());await(worker.prepareProfileAquifer());
                    rejected(worker.generateAquiferLattice(), 745);
                    long seed = 0x8000000187654321L;
                    byte[] descriptor = await(worker.surfaceDensityDescriptor(-1, -1, 18, 18, seed));
                    await(worker.request(13, descriptor));
                    await(worker.generateSurfaceColumns(0, 0, 16, 16, seed));
                    await(worker.generateBlockColumns());await(worker.generateCaveLattice());
                    rejected(worker.carveColumns(), 742);
                    byte[] counts = await(worker.generateAquiferLattice());
                    require(counts.length == 8 && ByteBuffer.wrap(counts).getInt() > 0, "resident aquifer centers");
                    byte[] point = ByteBuffer.allocate(24).putInt(1).putInt(8).putInt(-12).putInt(8)
                            .putInt((int) seed).putInt((int) (seed >>> 32)).array();
                    byte[] substance = await(worker.queryAquiferSubstance(point));
                    require(Arrays.equals(substance, ByteBuffer.allocate(8).putInt(1).putInt(1).array()), "registered cavity water");
                    reads.clear();
                    for (int i = 0; i < 8; i++) reads.add(worker.queryAquiferSubstance(point));
                    for (var read : reads) require(Arrays.equals(substance, await(read)), "concurrent resident substance callers");
                    rejected(worker.queryAquiferSubstance(new byte[] {0}), 603);
                    await(worker.carveColumns());
                    byte[] chunk = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
                    require(chunk.length > 100 && chunk[0] == 10, "carved fluid chunk NBT");
                    await(worker.generateAquiferLattice());
                    require(Arrays.equals(chunk, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "cache rebuilding preserves saved terrain");
                    await(worker.prepareProfileAquifer());rejected(worker.queryAquiferSubstance(point), 747);
                    require(Arrays.equals(chunk, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "aquifer preparation preserves saved terrain");
                    await(worker.generateAquiferLattice());
                    await(worker.generateSurfaceColumns(0, 0, 16, 16, seed));
                    rejected(worker.queryAquiferSubstance(point), 747);
                    require((await(worker.sampleAquiferFields(point))).length == 24, "surface rebuilding retains typed inputs");
                }
            }
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
            require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "aquifer worker terminates");
        }
        System.out.println("QA_EVT {\"event\":\"bend_aquifer_worker\",\"status\":\"pass\","
                + "\"context\":{\"executions\":2,\"concurrent_queries_per_execution\":8,\"fields\":6,"
                + "\"cache_lifecycle\":true,\"resident_placement\":" + (args.length > 2) + "}}");
    }

    private static byte[] await(CompletableFuture<byte[]> value) throws Exception {
        return value.get(60, TimeUnit.SECONDS);
    }

    private static void rejected(CompletableFuture<byte[]> value, int code) throws Exception {
        try { await(value);throw new AssertionError("expected rejection " + code); }
        catch (ExecutionException e) {
            require(e.getCause() instanceof java.io.IOException
                    && e.getCause().getMessage().contains("code " + code + ")"), "remote error code " + code);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
