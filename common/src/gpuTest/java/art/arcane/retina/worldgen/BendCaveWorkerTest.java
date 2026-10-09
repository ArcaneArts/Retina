package art.arcane.retina.worldgen;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;

/** Actual Java transport callers exercise the resident Bend cave commands. */
public final class BendCaveWorkerTest {
    private static byte[] await(CompletableFuture<byte[]> request) throws Exception {
        return request.get(5, TimeUnit.MINUTES);
    }

    private static void rejected(CompletableFuture<byte[]> request, int code) throws Exception {
        try {
            await(request);
            throw new AssertionError("Bend accepted invalid cave state/payload");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof java.io.IOException
                    && expected.getCause().getMessage().contains("code " + code + ")"), "Bend error reaches caller");
        }
    }

    public static void main(String[] args) throws Exception {
        var binary = Path.of(args[0]);var profile = Path.of(args[1]);
        long seed = 0x8000000187654321L;
        byte[] query = ByteBuffer.allocate(24).putInt(1).putInt(12).putInt(-8).putInt(12)
                .putInt((int) seed).putInt((int) (seed >>> 32)).array();
        for (var execution : BendWorker.Execution.values()) {
            long pid;
            try (var worker = new BendWorker(binary, execution, 2)) {
                pid = worker.processId();
                rejected(worker.generateCaveLattice(), 739);
                rejected(worker.queryCaveFields(query), 740);
                rejected(worker.queryCaveBiomes(query), 740);
                rejected(worker.carveColumns(), 741);
                await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
                await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
                await(worker.prepareProfileChunks());await(worker.prepareProfileClimate());
                await(worker.prepareProfileSurface());await(worker.prepareProfileGeology());
                await(worker.request(13, await(worker.surfaceDensityDescriptor(-1, -1, 26, 26, seed))));
                await(worker.generateSurfaceColumns(0, 0, 24, 24, seed));await(worker.generateBlockColumns());
                byte[] before = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
                var layout = ByteBuffer.wrap(await(worker.generateCaveLattice()));
                require(layout.getInt() == 0 && layout.getInt() == -32 && layout.getInt() == 0
                        && layout.getInt() == 7 && layout.getInt() == 17 && layout.getInt() == 7
                        && layout.getInt() == 833 && layout.getInt() == 4 && !layout.hasRemaining(), "cave layout");
                byte[] fields = await(worker.queryCaveFields(query));
                var values = ByteBuffer.wrap(fields);
                require(values.getInt() == 1 && values.getFloat() == -1.0f, "analytic chamber field");
                var reads = new ArrayList<CompletableFuture<byte[]>>();
                for (int i = 0; i < 8; i++) reads.add(worker.queryCaveFields(query));
                for (var read : reads) require(Arrays.equals(fields, await(read)), "concurrent cave callers");
                rejected(worker.request(35, new byte[] {0}), 603);
                require(Arrays.equals(before, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "rejected carving preserves blocks");
                var ack = ByteBuffer.wrap(await(worker.carveColumns()));
                require(ack.getInt() == 24 && ack.getInt() == 24 && ack.getInt() == 576 && ack.getInt() > 0, "carved columns");
                var voxel = ByteBuffer.wrap(await(worker.request(23, query)));
                require(voxel.getInt() == 1 && voxel.getInt() == 0, "chamber is air");
                byte[] after = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
                require(!Arrays.equals(before, after), "NBT consumes carved snapshot");
                try (var input = new InflaterInputStream(new ByteArrayInputStream(
                        await(worker.encodeGeneratedChunk(0, 0, seed, 5023, true))))) {
                    require(Arrays.equals(after, input.readAllBytes()), "Bend zlib decodes carved NBT");
                }
                await(worker.carveColumns());
                require(Arrays.equals(after, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "repeated carving");
                await(worker.generateBlockColumns());rejected(worker.queryCaveFields(query), 740);
                require(Arrays.equals(before, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "base regeneration restores columns");
                await(worker.generateCaveLattice());require(Arrays.equals(fields, await(worker.queryCaveFields(query))), "field regeneration");
            }
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
            require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "cave worker terminates");
            if (args.length > 2) checkBiomes(binary, Path.of(args[2]), execution, seed);
        }
        System.out.println("QA_EVT {\"event\":\"bend_cave_worker\",\"status\":\"pass\","
                + "\"context\":{\"executions\":2,\"concurrent_queries_per_execution\":8,\"carved_nbt_zlib\":true,\"cache_invalidation\":true}}");
    }

    private static void checkBiomes(Path binary, Path profile, BendWorker.Execution execution, long seed) throws Exception {
        try (var worker = new BendWorker(binary, execution, 2)) {
            await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
            await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
            await(worker.prepareProfileChunks());await(worker.prepareProfileClimate());
            await(worker.prepareProfileSurface());await(worker.prepareProfileGeology());
            await(worker.request(13, await(worker.surfaceDensityDescriptor(-1, -1, 26, 26, seed))));
            await(worker.generateSurfaceColumns(0, 0, 24, 24, seed));await(worker.generateBlockColumns());
            byte[] before = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
            await(worker.generateCaveLattice());
            int[] ys = {-28, -20, -12, 0, 12};int[] expected = {3, 2, 1, -1, -1};
            var payload = ByteBuffer.allocate(4 + ys.length * 20).putInt(ys.length);
            for (int y : ys) payload.putInt(12).putInt(y).putInt(12).putInt((int) seed).putInt((int) (seed >>> 32));
            byte[] ids = await(worker.queryCaveBiomes(payload.array()));
            var data = ByteBuffer.wrap(ids);
            for (int id : expected) require(data.getInt() == 1 && data.getInt() == id, "registered vertical biome strata");
            var reads = new ArrayList<CompletableFuture<byte[]>>();
            for (int i = 0; i < 8; i++) reads.add(worker.queryCaveBiomes(payload.array()));
            for (var read : reads) require(Arrays.equals(ids, await(read)), "concurrent biome callers");
            rejected(worker.queryCaveBiomes(new byte[] {0}), 603);
            require(Arrays.equals(before, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "biome queries preserve base snapshot");
            await(worker.carveColumns());
            byte[] after = await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false));
            require(!Arrays.equals(before, after), "NBT consumes underground biome volume");
            await(worker.generateCaveLattice());
            require(Arrays.equals(after, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "field replacement preserves carved biome snapshot");
            await(worker.prepareProfileChunks());
            require(Arrays.equals(after, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "catalog preparation preserves carved biomes");
            await(worker.generateBlockColumns());rejected(worker.queryCaveBiomes(payload.array()), 740);
            require(Arrays.equals(before, await(worker.encodeGeneratedChunk(0, 0, seed, 5023, false))), "base regeneration removes underground snapshot");
        }
        System.out.println("QA_EVT {\"event\":\"bend_cave_biome_worker\",\"status\":\"pass\","
                + "\"context\":{\"execution\":\"" + execution + "\",\"concurrent_queries\":8,\"vertical_strata\":4,\"snapshot_retention\":true}}");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
