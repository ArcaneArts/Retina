package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real world shutdown with native work still running, followed by save/reopen. */
public final class NetherUnloadQa {
    private static final boolean END_ENABLED = Boolean.getBoolean("retina.qa.end.unload");
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.unload") || END_ENABLED;
    private static final Map<MinecraftServer, Run> RUNS = new ConcurrentHashMap<>();
    private static volatile CompletableFuture<Void> lastCompletion = CompletableFuture.completedFuture(null);

    private static final class Dimension {
        final ServerLevel level;
        final RetinaChunkGenerator generator;
        final Path previewFolder;
        final long failures;
        final List<CompletableFuture<?>> requests = new ArrayList<>();
        boolean unloaded;
        long closeStarted;
        long previewsAtClose;

        Dimension(ServerLevel level) {
            this.level = level;
            generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
            previewFolder = generator.previewCache().folder();
            failures = generator.metrics().snapshot().failures();
        }
    }

    private static final class Run {
        final List<Dimension> dimensions = new ArrayList<>();
        final CompletableFuture<Void> ready = new CompletableFuture<>(), completed = new CompletableFuture<>();
        final AtomicInteger activeAtStorageClose = new AtomicInteger(), activeAtUnload = new AtomicInteger(),
                cleaned = new AtomicInteger();
        final long began = System.nanoTime();
        int pass;
    }

    private NetherUnloadQa() {}


    /** The client waits for this only after disconnecting, before reopening its disposable save. */
    public static CompletableFuture<Void> completion() { return lastCompletion; }

    static void generationFailed(Throwable error) {
        if (ENABLED) Retina.LOGGER.error("QA_EVT {\"event\":\"{}_unload_native_failure\",\"status\":\"fail\"}", END_ENABLED ? "end" : "nether", error);
    }

    static void serverDrained() {
        if (ENABLED) event("nether_unload_server_drained", "\"complete\":true");
    }

    static void storageClosing(RetinaChunkGenerator generator) {
        if (!ENABLED) return;
        for (var run : RUNS.values()) for (var dimension : run.dimensions) if (dimension.generator == generator) {
            int active = generator.metrics().snapshot().inFlight();
            run.activeAtStorageClose.addAndGet(active);
            event("nether_unload_storage_close", "\"dimension\":\"" + dimension.level.dimension().identifier()
                    + "\",\"native_in_flight\":" + active);
        }
    }

    public static CompletableFuture<Void> prepareShutdown(MinecraftServer server) {
        if (!ENABLED) return CompletableFuture.completedFuture(null);
        var run = new Run();
        require(RUNS.putIfAbsent(server, run) == null, "one shutdown scenario per server");
        lastCompletion = run.completed;
        try {
            var path = server.getWorldPath(LevelResource.ROOT).resolve(END_ENABLED ? "retina-qa-end-unload.json" : "retina-qa-nether-unload.json");
            if (Files.exists(path)) {
                var prior = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                run.pass = prior.get("pass").getAsInt() + 1;
                for (var folder : prior.getAsJsonArray("preview_folders"))
                    require(Files.notExists(Path.of(folder.getAsString())), "previous world's preview directory was removed");
            }
            AutomaticGenerationQa.check(server);
            AutomaticGenerationQa.checkSavedEdits(server, run.pass > 0);
            var folders = new JsonArray();
            for (var key : END_ENABLED ? List.of(Level.END) : List.of(Level.OVERWORLD, Level.NETHER)) {
                var dimension = new Dimension(server.getLevel(key));
                run.dimensions.add(dimension);
                folders.add(dimension.previewFolder.toString());
            }
            var saved = new JsonObject();
            saved.addProperty("pass", run.pass);
            saved.add("preview_folders", folders);
            Files.writeString(path, saved.toString());
            // Native preparation uses the same global budget for both dimensions.
            // Nothing holds or replaces the actual generator: shutdown races real work.
            // End regions finish quickly; retain enough real work to overlap the initial save.
            int regions = RegionGenerationBudget.SHARED.admissionCapacity() * (END_ENABLED ? 8 : 1) + 2;
            for (int i = 0; i < regions; i++) for (var dimension : run.dimensions) {
                int region = 32 + run.pass * 64 + i;
                var at = new ChunkPos(region * 32, -region * 32);
                var worker = (IOWorker) dimension.level.getChunkSource().chunkMap.chunkScanner();
                dimension.requests.add(((RegionIoBridge) worker).retina$generateAhead(at));
                if (i == 0) {
                    dimension.requests.add(worker.loadAsync(at));
                    require(worker.loadAsync(at).cancel(false), "duplicate read observer cancels");
                }
            }
            event("nether_unload_queued", "\"pass\":" + run.pass + ",\"regions_per_dimension\":" + regions);
        } catch (Throwable error) {
            run.ready.completeExceptionally(error);
            run.completed.completeExceptionally(error);
        }
        return run.ready;
    }

    static void tick(MinecraftServer server) {
        if (!ENABLED) return;
        if (server.isDedicatedServer() && server.getTickCount() >= 20 && !RUNS.containsKey(server))
            prepareShutdown(server).thenRun(() -> server.halt(false));
        var run = RUNS.get(server);
        if (run == null) return;
        if (run.ready.isCompletedExceptionally()) run.ready.join();
        if (run.ready.isDone()) return;
        require(System.nanoTime() - run.began < TimeUnit.MINUTES.toNanos(3), "native work starts before deadline");
        int active = run.dimensions.stream().mapToInt(d -> d.generator.metrics().snapshot().inFlight()).sum();
        if (active == 0) return;
        require(run.dimensions.stream().allMatch(d -> d.requests.stream().anyMatch(f -> !f.isDone())),
                "both dimensions still have pending requests");
        event("nether_unload_in_flight", "\"pass\":" + run.pass + ",\"native_in_flight\":" + active);
        run.ready.complete(null);
    }

    /** Record the actual loader unload callback before Retina closes its previews. */
    static void beforeUnload(ServerLevel level) {
        if (!ENABLED) return;
        var run = RUNS.get(level.getServer());
        if (run == null) return;
        for (var dimension : run.dimensions) if (dimension.level == level) {
            require(!dimension.unloaded, "one unload callback per dimension");
            dimension.unloaded = true;
            dimension.closeStarted = System.nanoTime();
            dimension.previewsAtClose = dimension.generator.metrics().snapshot().previewRegions();
            int active = dimension.generator.metrics().snapshot().inFlight();
            run.activeAtUnload.addAndGet(active);
            event("nether_unload_callback", "\"dimension\":\"" + level.dimension().identifier()
                    + "\",\"native_in_flight\":" + active + ",\"pending\":"
                    + dimension.requests.stream().filter(f -> !f.isDone()).count()
                    + ",\"queued_admissions\":" + RegionGenerationBudget.SHARED.queuedRequests());
            // This observer uses no server tasks and keeps the test JVM alive until
            // late native completion and lease cleanup have actually been inspected.
            Thread.ofPlatform().daemon(false).name("retina-unload-qa-" + level.dimension().identifier()).start(() -> {
                try {
                    int stopped = 0;
                    for (var request : dimension.requests) {
                        Throwable failure = request.handle((value, error) -> error).get(3, TimeUnit.MINUTES);
                        while (failure instanceof java.util.concurrent.CompletionException) failure = failure.getCause();
                        if (failure != null) {
                            require(failure instanceof java.util.concurrent.CancellationException
                                    || (failure instanceof IllegalStateException
                                    && (failure.getMessage().contains("closed") || failure.getMessage().contains("closing"))),
                                    "pending request fails only with an explicit close result: " + failure);
                            stopped++;
                        }
                    }
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
                    while (Files.exists(dimension.previewFolder)) {
                        require(System.nanoTime() < deadline, "all late leases release and remove the preview directory");
                        Thread.sleep(20);
                    }
                    var metrics = dimension.generator.metrics().snapshot();
                    require(metrics.inFlight() == 0, "no remaining native generation");
                    require(metrics.failures() == dimension.failures, "late native completion has no hidden failures");
                    event("nether_unload_cleaned", "\"dimension\":\"" + level.dimension().identifier()
                            + "\",\"requests\":" + dimension.requests.size() + ",\"closed_results\":" + stopped
                            + ",\"generation_failures\":" + (metrics.failures() - dimension.failures)
                            + ",\"close_ms\":" + (System.nanoTime() - dimension.closeStarted) / 1e6
                            + ",\"completed_after_close\":" + (metrics.previewRegions() - dimension.previewsAtClose)
                            + ",\"qa_property\":" + Boolean.getBoolean("retina.qa"));
                    if (run.cleaned.incrementAndGet() == run.dimensions.size()) {
                        // Loader callbacks and storage closure occur in different orders. Work
                        // may legitimately drain between the first close boundary and the second.
                        require(run.activeAtStorageClose.get() > 0 || run.activeAtUnload.get() > 0,
                                "generation was active at a world close boundary");
                        event("nether_unload_complete", "\"pass\":" + run.pass
                                + ",\"active_at_storage_close_sum\":" + run.activeAtStorageClose.get()
                                + ",\"active_at_unload_sum\":" + run.activeAtUnload.get());
                        run.completed.complete(null);
                        RUNS.remove(level.getServer(), run);
                    }
                } catch (Throwable error) {
                    Retina.LOGGER.error("QA_EVT {\"event\":\"{}_unload_complete\",\"status\":\"fail\"}", END_ENABLED ? "end" : "nether", error);
                    run.completed.completeExceptionally(error);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException((END_ENABLED ? "End" : "Nether") + " unload QA: " + message);
    }
    private static void event(String name, String context) {
        if (END_ENABLED) { name = name.replace("nether_", "end_"); context = context.replace("native_in_flight", "generation_in_flight"); }
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }
}
