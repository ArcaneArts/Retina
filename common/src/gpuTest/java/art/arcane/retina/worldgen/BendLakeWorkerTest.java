package art.arcane.retina.worldgen;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Real concurrent transport checks; placement algorithms execute in Bend. */
public final class BendLakeWorkerTest {
    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args[0]), profile = Path.of(args[1]);
        long seed = 0x8000000187654321L;
        byte[] ids = ByteBuffer.allocate(12).putInt(2).putInt(0).putInt(1).array();
        byte[] query = query(0, 0, seed);
        for (var execution : BendWorker.Execution.values()) {
            long pid;
            try (var worker = new BendWorker(binary, execution, 2)) {
                pid = worker.processId();
                rejected(worker.queryLakeSettings(ids), 748);
                rejected(worker.sampleLakeCandidates(query), 749);
                await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
                await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
                await(worker.prepareProfileGeology());
                byte[] settings = await(worker.queryLakeSettings(ids));
                var metadata = ByteBuffer.wrap(settings);
                for (int biome = 0; biome < 2; biome++) {
                    require(metadata.getFloat() == 1f && metadata.getFloat() == biome,
                            "loaded water/lava placement budgets");
                    require(metadata.getInt() == 1 && metadata.getInt() == 4 && metadata.getInt() == 0,
                            "loaded barrier materials and biome flags");
                }
                require(!metadata.hasRemaining(), "exact metadata framing");
                rejected(worker.sampleLakeCandidates(query), 749);
                await(worker.prepareProfileClimate());
                byte[] first = await(worker.sampleLakeCandidates(query));
                var row = ByteBuffer.wrap(first);
                require(first.length == 56 && row.getInt() == 1, "valid candidate framing");
                require(row.getInt() == 0 && row.getInt() == 0, "globally aligned cell");
                double x = (double) row.getFloat() + row.getFloat();
                double z = (double) row.getFloat() + row.getFloat();
                require(x >= 32 && x <= 96 && z >= 32 && z <= 96, "interior candidate position");
                float radius = row.getFloat();
                require(radius >= 6 && radius <= 7.75 && radius * 4 == (int) (radius * 4),
                        "quarter-radius lava lakes");
                require(row.getInt() == 1 && row.getInt() == 1 && row.getInt() == 1
                        && row.getInt() == 1 && row.getInt() == 0, "selected biome and lava barrier");
                require(row.getFloat() >= 0 && !row.hasRemaining(), "candidate chance and framing");
                require(Arrays.equals(first, await(worker.sampleLakeCandidates(query(127, 127, seed)))),
                        "all positions in a global cell reuse the candidate");
                require(!Arrays.equals(first, await(worker.sampleLakeCandidates(query(0, 0, seed ^ (1L << 32))))),
                        "full world seed influences placement");
                var reads = new ArrayList<CompletableFuture<byte[]>>();
                for (int i = 0; i < 8; i++) reads.add(worker.sampleLakeCandidates(query));
                for (var read : reads) require(Arrays.equals(first, await(read)), "concurrent candidate callers");
                reads.clear();
                for (int i = 0; i < 8; i++) reads.add(worker.queryLakeSettings(ids));
                for (var read : reads) require(Arrays.equals(settings, await(read)), "concurrent metadata callers");
                rejected(worker.sampleLakeCandidates(new byte[] {0}), 603);
                rejected(worker.queryLakeSettings(ByteBuffer.allocate(8).putInt(1).putInt(2).array()), 603);
                require(Arrays.equals(first, await(worker.sampleLakeCandidates(query))), "rejected commands preserve state");
                await(worker.prepareProfileSurface());await(worker.prepareProfileChunks());
                byte[] descriptor = await(worker.surfaceDensityDescriptor(-1, -1, 18, 18, seed));
                await(worker.request(13, descriptor));
                await(worker.generateSurfaceColumns(0, 0, 16, 16, seed));
                await(worker.generateBlockColumns());
                byte[] chunk = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
                require(chunk.length > 100 && chunk[0] == 10, "actual generated NBT");
                require(Arrays.equals(first, await(worker.sampleLakeCandidates(query))), "terrain rebuilding retains lake inputs");
                await(worker.queryLakeSettings(ids));
                require(Arrays.equals(chunk, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))),
                        "read-only planning preserves saved terrain");
                await(worker.prepareProfileGeology());
                require(Arrays.equals(first, await(worker.sampleLakeCandidates(query))), "geology re-preparation is repeatable");
                await(worker.loadProfile(profile));
                rejected(worker.queryLakeSettings(ids), 748);
                rejected(worker.sampleLakeCandidates(query), 749);
            }
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
            require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "lake worker terminates");
        }
        System.out.println("QA_EVT {\"event\":\"bend_lake_worker\",\"status\":\"pass\","
                + "\"context\":{\"executions\":2,\"concurrent_queries_per_execution\":8,"
                + "\"full_seed\":true,\"saved_terrain_preserved\":true,\"reload_shutdown\":true}}");
    }

    private static byte[] query(int x, int z, long seed) {
        return ByteBuffer.allocate(20).putInt(1).putInt(x).putInt(z)
                .putInt((int) seed).putInt((int) (seed >>> 32)).array();
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
