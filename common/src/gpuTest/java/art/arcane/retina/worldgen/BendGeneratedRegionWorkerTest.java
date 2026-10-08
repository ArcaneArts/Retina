package art.arcane.retina.worldgen;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Real concurrent raw callers; generation, NBT, zlib and MCA remain in Bend. */
public final class BendGeneratedRegionWorkerTest {
    private static byte[] await(CompletableFuture<byte[]> request) throws Exception {
        return request.get(10, TimeUnit.MINUTES);
    }

    public static void main(String[] args) throws Exception {
        var binary = Path.of(args.length == 0 ? "build/bend/engine" : args[0]);
        var profile = Path.of(args.length < 2 ? "build/bend/generated-regions/ramp.rbp" : args[1]);
        var folder = Files.createTempDirectory("retina-bend-region-worker-");
        long seed = 0x8000000012345678L;
        try {
            for (var execution : BendWorker.Execution.values()) {
                long pid;
                try (var worker = new BendWorker(binary, execution, 2)) {
                    pid = worker.processId();
                    await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
                    await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
                    await(worker.prepareProfileClimate());await(worker.prepareProfileSurface());await(worker.prepareProfileChunks());
                    byte[] descriptor = await(worker.surfaceDensityDescriptor(-513, -1, 514, 514, seed));
                    await(worker.request(13, descriptor));await(worker.generateSurfaceColumns(-512, 0, 512, 512, seed));
                    await(worker.generateBlockColumns());
                    byte[] chunk = await(worker.encodeGeneratedChunk(-32, 0, seed, 5023, true));
                    var first = folder.resolve(execution + "-first.mca");
                    var cancelledPath = folder.resolve(execution + "-cancelled.mca");
                    var second = folder.resolve(execution + "-é 🌍.mca");
                    var a = worker.writeGeneratedRegion(-1, 0, seed, 5023, -1, first);
                    var cancelled = worker.writeGeneratedRegion(-1, 0, seed, 5023, -1, cancelledPath);
                    var b = worker.writeGeneratedRegion(-1, 0, seed, 5023, -1, second);
                    require(cancelled.cancel(false), "cancel queued region request");
                    byte[] ack = await(a);
                    require(Arrays.equals(ack, await(b)), "concurrent raw callers receive matching acknowledgements");
                    var counts = ByteBuffer.wrap(ack);
                    require(counts.getInt() == 1024 && counts.getInt() * 4096L == Files.size(first), "complete staged region acknowledgement");
                    require(Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second)), "parallel callers preserve snapshot bytes");
                    require(!Files.exists(cancelledPath), "queued cancellation does not write a file");
                    require(Arrays.equals(chunk, await(worker.encodeGeneratedChunk(-32, 0, seed, 5023, true))), "region writes preserve query snapshot");
                    if (execution == BendWorker.Execution.CPU) {
                        // An actual open failure, not a path-existence preflight.
                        try {
                            await(worker.writeGeneratedRegion(-1, 0, seed, 5023, -1, folder.resolve("missing/region.mca")));
                            throw new AssertionError("Missing destination parent accepted");
                        } catch (ExecutionException expected) {
                            require(expected.getCause() instanceof java.io.IOException, "IO failure reaches raw caller");
                        }
                        require(!Files.exists(folder.resolve("missing/region.mca")), "failed output not published");
                    }
                }
                var handle = ProcessHandle.of(pid);
                if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
                require(ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true), "worker terminates on close");
            }
            System.out.println("QA_EVT {\"event\":\"bend_generated_region_worker\",\"status\":\"pass\","
                    + "\"context\":{\"executions\":2,\"concurrent_writes_per_execution\":2,\"queued_cancellations\":2,\"actual_io_failure\":true}}");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
