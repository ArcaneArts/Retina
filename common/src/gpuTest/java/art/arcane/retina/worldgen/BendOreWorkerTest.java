package art.arcane.retina.worldgen;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Actual concurrent transport checks; all ore policy executes in Bend. */
public final class BendOreWorkerTest {
    public static void main(String[] args) throws Exception {
        Path binary = Path.of(args[0]), profile = Path.of(args[1]);
        byte[] infoRequest = ByteBuffer.allocate(4).putInt(0).array();
        byte[] batch = ByteBuffer.allocate(4 + 6 * 7 * 4).putInt(7)
                .putInt(0).putInt(0).putInt(-8).putInt(1).putInt(0).putInt(0)
                .putInt(0).putInt(0).putInt(-8).putInt(1).putInt(0).putInt(1)
                .putInt(0).putInt(0).putInt(-8).putInt(1).putInt(-1).putInt(1)
                .putInt(0).putInt(1).putInt(-8).putInt(1).putInt(-1).putInt(0)
                .putInt(0).putInt(0).putInt(1).putInt(1).putInt(0).putInt(0)
                .putInt(1).putInt(0).putInt(1).putInt(1).putInt(-1).putInt(1)
                .putInt(0).putInt(0).putInt(32).putInt(0).putInt(0).putInt(0).array();
        byte[] expected = ByteBuffer.allocate(28).putInt(2).putInt(0).putInt(2).putInt(0)
                .putInt(4).putInt(0).putInt(1).array();
        byte[] attemptRequest = ByteBuffer.allocate(24).putInt(1).putInt(1)
                .putInt(-1).putInt(2).putInt(0x12345678).putInt(0x80000000).array();
        byte[] geometryRequest = ByteBuffer.allocate(16).putInt(1).putInt(0).putInt(0).putInt(0).array();
        for (var execution : BendWorker.Execution.values()) {
            long pid;
            try (var worker = new BendWorker(binary, execution, 2)) {
                pid = worker.processId();
                rejected(worker.queryOreMetadata(infoRequest), 754);
                rejected(worker.sampleOreReplacements(batch), 754);
                rejected(worker.sampleOreAttempts(attemptRequest), 754);
                rejected(worker.sampleOreGeometry(geometryRequest), 754);
                await(worker.loadProfile(profile));await(worker.prepareProfileDensity());
                await(worker.prepareProfileMaterials());await(worker.prepareProfileBlocks());
                await(worker.prepareProfileGeology());
                byte[] info = await(worker.queryOreMetadata(infoRequest));
                var words = ByteBuffer.wrap(info);
                require(words.getInt() == 2 && words.getInt() == 3 && words.getInt() == 2
                        && words.getInt() == 6 && words.getInt() == 45, "loaded ore dimensions");
                int flagBase = words.getInt();
                require(words.getInt() == flagBase + 6 && !words.hasRemaining(), "packed flags and framing");
                require(Arrays.equals(expected, await(worker.sampleOreReplacements(batch))),
                        "ordered overlapping bands, exposure threshold and biome membership");
                byte[] attempts = await(worker.sampleOreAttempts(attemptRequest));
                var attemptWords = ByteBuffer.wrap(attempts);
                int attemptCount = attemptWords.getInt();
                require(attemptCount >= 4 && attemptCount <= 8 && attempts.length == 4 + 24 * attemptCount,
                        "registered attempt count and framing");
                for (int i = 0; i < attemptCount; i++) {
                    require(attemptWords.getInt() == i, "stable attempt indices");
                    int x = attemptWords.getInt(), y = attemptWords.getInt(), z = attemptWords.getInt();
                    require(x >= -16 && x < 0 && y >= -17 && y <= 5 && z >= 32 && z < 48,
                            "signed global coordinates and registered height range");
                    attemptWords.getInt(); attemptWords.getInt();
                }
                byte[] geometry = await(worker.sampleOreGeometry(geometryRequest));
                var geometryWords = ByteBuffer.wrap(geometry);
                require(geometryWords.getInt() == 0 && geometryWords.getInt() == 64 && geometry.length == 1032,
                        "registered regular vein size and framing");
                var calls = new ArrayList<CompletableFuture<byte[]>>();
                for (int i = 0; i < 8; i++) calls.add(worker.sampleOreReplacements(batch));
                for (var call : calls) require(Arrays.equals(expected, await(call)), "concurrent replacement callers");
                calls.clear();
                for (int i = 0; i < 8; i++) calls.add(worker.queryOreMetadata(infoRequest));
                for (var call : calls) require(Arrays.equals(info, await(call)), "concurrent metadata callers");
                calls.clear();
                for (int i = 0; i < 8; i++) calls.add(worker.sampleOreAttempts(attemptRequest));
                for (var call : calls) require(Arrays.equals(attempts, await(call)), "concurrent anchored attempt callers");
                calls.clear();
                for (int i = 0; i < 8; i++) calls.add(worker.sampleOreGeometry(geometryRequest));
                for (var call : calls) require(Arrays.equals(geometry, await(call)), "concurrent geometry callers");
                rejected(worker.queryOreMetadata(new byte[] {0}), 603);
                rejected(worker.sampleOreReplacements(new byte[] {0}), 603);
                rejected(worker.sampleOreAttempts(new byte[] {0}), 603);
                rejected(worker.sampleOreGeometry(new byte[] {0}), 603);
                rejected(worker.prepareProfileGeology().thenCompose(ignored -> worker.request(30, new byte[] {0})), 603);
                require(Arrays.equals(expected, await(worker.sampleOreReplacements(batch))), "rejected requests preserve ore inputs");
                await(worker.prepareProfileClimate());await(worker.prepareProfileSurface());
                require(Arrays.equals(info, await(worker.queryOreMetadata(infoRequest))), "surface preparation retains ore inputs");
                require(Arrays.equals(attempts, await(worker.sampleOreAttempts(attemptRequest)))
                        && Arrays.equals(geometry, await(worker.sampleOreGeometry(geometryRequest))), "surface preparation retains planning");
                await(worker.loadProfile(profile));
                rejected(worker.queryOreMetadata(infoRequest), 754);
                rejected(worker.sampleOreReplacements(batch), 754);
                rejected(worker.sampleOreAttempts(attemptRequest), 754);
                rejected(worker.sampleOreGeometry(geometryRequest), 754);
                await(worker.prepareProfileDensity());await(worker.prepareProfileMaterials());
                await(worker.prepareProfileBlocks());await(worker.prepareProfileGeology());
                require(Arrays.equals(info, await(worker.queryOreMetadata(infoRequest)))
                        && Arrays.equals(expected, await(worker.sampleOreReplacements(batch))), "reload is repeatable");
                require(Arrays.equals(attempts, await(worker.sampleOreAttempts(attemptRequest)))
                        && Arrays.equals(geometry, await(worker.sampleOreGeometry(geometryRequest))), "planning reload is repeatable");
                require(worker.processId() == pid, "one persistent process");
            }
            require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "closed worker process");
        }
        System.out.println("PASS: Bend ore CPU/GPU concurrent callers, policy, attempts, geometry, reload and shutdown");
    }

    private static byte[] await(CompletableFuture<byte[]> future) throws Exception {
        return future.get(300, TimeUnit.SECONDS);
    }

    private static void rejected(CompletableFuture<byte[]> future, int code) throws Exception {
        try { await(future); throw new AssertionError("request unexpectedly succeeded"); }
        catch (ExecutionException e) {
            require(e.getCause() instanceof java.io.IOException
                            && e.getCause().getMessage().contains("code " + code + ")"),
                    "expected Bend error " + code + ": " + e.getCause());
        }
    }

    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
