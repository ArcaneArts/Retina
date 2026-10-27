package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in real installed Chunky/DH checks; optional APIs are used only in this disposable scenario. */
final class NetherIntegrationQa {
    private static final boolean END = Boolean.getBoolean("retina.qa.end.integrations");
    private static final boolean ENABLED = END || Boolean.getBoolean("retina.qa.nether.integrations");
    private static final ResourceKey<Level> DIMENSION = END ? Level.END : Level.NETHER;
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final AtomicInteger STARTED = new AtomicInteger(), STOPPED = new AtomicInteger(), FINISHED = new AtomicInteger();
    private static final AtomicLong DH_REGIONS = new AtomicLong();
    private static volatile Throwable failure;
    private static final BlockPos RETURN = new BlockPos(128, 100, 128);

    private static final class Session {
        Run run;
        int completed;
    }
    private static final class Run {
        final ServerPlayer player;
        final long began = System.nanoTime();
        final int starts = STARTED.get(), stops = STOPPED.get(), finishes = FINISHED.get();
        int phase, pass, arrived;
        boolean disabledSeen;
        long dhBefore;
        Object task;
        BlockPos destination;
        CompletableFuture<Void> preparation, foreground;
        long foregroundBegan, foregroundNanos;
        int backgroundAtForeground;
        Run(ServerPlayer player) { this.player = player; }
    }

    private NetherIntegrationQa() {}

    static void chunkyStarted(ServerLevel level) {
        if (ENABLED && level.dimension().equals(DIMENSION)) STARTED.incrementAndGet();
    }
    static void chunkyFinished(ServerLevel level, RegionAheadPump.Result result) {
        if (!ENABLED || !level.dimension().equals(DIMENSION)) return;
        if (result.failure() != null) { failure = result.failure(); return; }
        (result.stopped() ? STOPPED : FINISHED).incrementAndGet();
        event("nether_chunky_pump", "\"stopped\":" + result.stopped() + ",\"published\":" + result.completed());
    }
    static void generated(ResourceKey<Level> dimension, ChunkPos origin) {
        if (!ENABLED || !dimension.equals(DIMENSION)) return;
        try {
            if ((boolean) Class.forName("com.seibel.distanthorizons.api.DhApi").getMethod("isDhThread").invoke(null)) {
                DH_REGIONS.incrementAndGet();
                event("nether_dh_native_region", "\"x\":" + origin.getRegionX() + ",\"z\":" + origin.getRegionZ());
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Installed DH QA API", error); }
    }
    static void unload(ServerLevel level) {
        if (ENABLED && level.dimension().equals(DIMENSION)) SESSIONS.remove(level.getServer());
    }
    static void tick(MinecraftServer server) {
        if (!ENABLED) return;
        try { run(server); }
        catch (Exception error) { throw new IllegalStateException("Installed dimension integration QA", error); }
    }

    private static void run(MinecraftServer server) throws Exception {
        if (failure != null) throw new IllegalStateException("Installed Chunky QA pump failed", failure);
        var session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            if (session.run != null) {
                require(session.run.phase == 7, "player disconnected before integration checks finished");
                session.run = null;
            }
            if (server.isDedicatedServer() && session.completed >= 2) server.halt(false);
            return;
        }
        require(players.size() == 1, "one QA player");
        var player = players.getFirst();
        if (player.tickCount < 20) return;
        if (session.run == null) session.run = new Run(player);
        var run = session.run;
        require(run.player == player, "same connection throughout scenario");
        if (run.phase == 7) return;
        require(System.nanoTime() - run.began < 900_000_000_000L, "deadline in phase " + run.phase);
        var level = server.getLevel(DIMENSION);
        var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var path = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-" + (END ? "end" : "nether") + "-integrations.json");
        switch (run.phase) {
            case 0 -> {
                if (!END && !TerrainQa.completed(server.overworld())) return;
                AutomaticGenerationQa.check(server);
                boolean reopened = Files.exists(path);
                var saved = reopened ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : null;
                if (reopened) run.pass = saved.get("pass").getAsInt() + 1;
                AutomaticGenerationQa.checkSavedEdits(server, reopened);
                if (END && reopened) {
                    require(saved.get("dimension").getAsString().equals(DIMENSION.identifier().toString()), "saved integration dimension");
                    require(saved.get("dh_native_regions").getAsLong() > 0 && saved.get("dh_active_at_foreground_request").getAsInt() > 0, "saved real background and foreground evidence");
                    run.destination = new BlockPos(saved.get("destination_x").getAsInt(), 160, saved.get("destination_z").getAsInt());
                    run.preparation = PortalRegionPreparation.prepareArea(level, run.destination, 48);
                    run.phase = 6;
                    return;
                }
                // Initial End checks and each Nether connection request a cold selection.
                int coordinate = -7936 - run.pass * 4096;
                // Above the terrain, distant chunks are visible to DH request selection.
                run.destination = new BlockPos(coordinate, 160, coordinate);
                command(server, "chunky start " + DIMENSION.identifier() + " square " + coordinate + " " + coordinate + " 192");
                run.task = task();
                require(run.task != null, "real Chunky task started in selected dimension");
                run.phase = 1;
            }
            case 1 -> {
                if (STARTED.get() == run.starts || generator.metrics().snapshot().inFlight() == 0) return;
                command(server, "chunky pause " + DIMENSION.identifier());
                event("nether_chunky_paused", "\"pass\":" + run.pass + ",\"count\":" + count(run.task));
                run.phase = 2;
            }
            case 2 -> {
                if (task() != null || STOPPED.get() == run.stops) return;
                command(server, "chunky continue " + DIMENSION.identifier());
                run.task = task();
                require(run.task != null, "real paused Chunky task resumes");
                event("nether_chunky_resumed", "\"pass\":" + run.pass + ",\"count\":" + count(run.task));
                run.phase = 3;
            }
            case 3 -> {
                run.disabledSeen |= plan().equals("DISABLED");
                if (task() != null) return;
                var progress = call(run.task, "getProgress");
                // Chunky removes the task when dispatch ends; its outstanding
                // chunk futures can still be updating the completion count.
                if (!(boolean) call(progress, "isComplete")) return;
                require(count(run.task) > 0, "Chunky completed actual chunk loads");
                require(STARTED.get() >= run.starts + 2 && FINISHED.get() > run.finishes, "resumed native region pump completed");
                if (!run.disabledSeen || plan().equals("DISABLED")) return;
                event("nether_chunky_complete", "\"pass\":" + run.pass + ",\"chunks\":" + count(run.task)
                        + ",\"dh_disabled_seen\":true,\"dh_restored\":true");
                // Chunky's Minecraft finalization also loads neighboring regions. Travel
                // beyond that halo so DH has genuinely cold surrounding terrain to request.
                run.destination = run.destination.offset(2048, 0, 0);
                run.preparation = CompletableFuture.allOf(
                        PortalRegionPreparation.prepareArea(level, run.destination, 48),
                        PortalRegionPreparation.prepareArea(server.overworld(), RETURN, 48));
                run.dhBefore = DH_REGIONS.get();
                run.phase = 4;
            }
            case 4 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                player.setGameMode(GameType.SPECTATOR);
                position(player, level, run.destination);
                run.arrived = server.getTickCount();
                run.phase = 5;
            }
            case 5 -> {
                if (END && run.foreground == null) {
                    int active = PortalBackgroundQa.active();
                    if (active == 0) return;
                    run.backgroundAtForeground = active;
                    run.foregroundBegan = System.nanoTime();
                    run.foreground = PortalRegionPreparation.prepareArea(level, run.destination.offset(4096, 0, 4096), 0);
                }
                if (run.foreground != null) {
                    if (!run.foreground.isDone()) return;
                    run.foreground.join();
                    if (run.foregroundNanos == 0) run.foregroundNanos = System.nanoTime() - run.foregroundBegan;
                }
                // Observe real native work on a DH thread, not just its loaded mod or database.
                if (DH_REGIONS.get() <= run.dhBefore || !TerrainQa.completed(level)
                        || server.getTickCount() - run.arrived < 100) return;
                require(generator.metrics().snapshot().failures() == 0, "no hidden native failures during integrations");
                var saved = new JsonObject();
                saved.addProperty("pass", run.pass);
                saved.addProperty("dimension", DIMENSION.identifier().toString());
                saved.addProperty("destination_x", run.destination.getX());
                saved.addProperty("destination_z", run.destination.getZ());
                if (END) {
                    saved.addProperty("dh_active_at_foreground_request", run.backgroundAtForeground);
                    saved.addProperty("foreground_ms", run.foregroundNanos / 1e6);
                }
                saved.addProperty("chunky_chunks", count(run.task));
                saved.addProperty("dh_native_regions", DH_REGIONS.get() - run.dhBefore);
                Files.writeString(path, saved.toString());
                event("nether_installed_integrations", saved.toString().substring(1, saved.toString().length() - 1));
                position(player, server.overworld(), RETURN);
                run.phase = 7;
                session.completed++;
            }
            case 6 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                if (run.arrived == 0) {
                    player.setGameMode(GameType.SPECTATOR);
                    position(player, level, run.destination);
                    run.arrived = server.getTickCount();
                    return;
                }
                if (server.getTickCount() - run.arrived < 40) return;
                require(generator.metrics().snapshot().failures() == 0, "integration world reopens without generation failures");
                event("end_installed_integrations_reopen", "\"pass\":" + run.pass);
                position(player, server.overworld(), RETURN);
                run.phase = 7;
                session.completed++;
            }
            default -> throw new IllegalStateException("Unknown integration phase " + run.phase);
        }
    }

    private static Object task() throws Exception {
        var chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
        var tasks = (Map<?, ?>) call(chunky, "getGenerationTasks");
        for (var value : tasks.values()) {
            var world = call(call(value, "getSelection"), "world");
            if (call(world, "getKey").toString().equals(DIMENSION.identifier().toString())) return value;
        }
        return null;
    }
    private static String plan() throws Exception {
        var config = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("configs").get(null);
        return call(call(call(config, "worldGenerator"), "GeneratorPlan"), "getValue").toString();
    }
    private static long count(Object task) throws Exception { return (long) call(task, "getCount"); }
    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
    private static void position(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 25, true);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void event(String name, String context) {
        if (END && name.startsWith("nether_")) name = "end_" + name.substring(7);
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }
}
