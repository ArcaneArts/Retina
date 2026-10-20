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
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.integrations");
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
        CompletableFuture<Void> preparation;
        Run(ServerPlayer player) { this.player = player; }
    }

    private NetherIntegrationQa() {}

    static void chunkyStarted(ServerLevel level) {
        if (ENABLED && level.dimension().equals(Level.NETHER)) STARTED.incrementAndGet();
    }
    static void chunkyFinished(ServerLevel level, RegionAheadPump.Result result) {
        if (!ENABLED || !level.dimension().equals(Level.NETHER)) return;
        if (result.failure() != null) { failure = result.failure(); return; }
        (result.stopped() ? STOPPED : FINISHED).incrementAndGet();
        event("nether_chunky_pump", "\"stopped\":" + result.stopped() + ",\"published\":" + result.completed());
    }
    static void generated(ResourceKey<Level> dimension, ChunkPos origin) {
        if (!ENABLED || !dimension.equals(Level.NETHER)) return;
        try {
            if ((boolean) Class.forName("com.seibel.distanthorizons.api.DhApi").getMethod("isDhThread").invoke(null)) {
                DH_REGIONS.incrementAndGet();
                event("nether_dh_native_region", "\"x\":" + origin.getRegionX() + ",\"z\":" + origin.getRegionZ());
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Installed DH QA API", error); }
    }
    static void unload(ServerLevel level) {
        if (ENABLED && level.dimension().equals(Level.NETHER)) SESSIONS.remove(level.getServer());
    }
    static void tick(MinecraftServer server) {
        if (!ENABLED) return;
        try { run(server); }
        catch (Exception error) { throw new IllegalStateException("Nether installed integration QA", error); }
    }

    private static void run(MinecraftServer server) throws Exception {
        if (failure != null) throw new IllegalStateException("Nether Chunky QA pump failed", failure);
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
        var level = server.getLevel(Level.NETHER);
        var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var path = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-nether-integrations.json");
        switch (run.phase) {
            case 0 -> {
                if (!TerrainQa.completed(server.overworld())) return;
                AutomaticGenerationQa.check(server);
                boolean reopened = Files.exists(path);
                if (reopened) run.pass = JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("pass").getAsInt() + 1;
                AutomaticGenerationQa.checkSavedEdits(server, reopened);
                // Each connection requests a cold region, including after a full process restart.
                int coordinate = -7936 - run.pass * 4096;
                // Above the roof, distant terrain is visible to DH's request selection.
                run.destination = new BlockPos(coordinate, 160, coordinate);
                command(server, "chunky start minecraft:the_nether square " + coordinate + " " + coordinate + " 192");
                run.task = task();
                require(run.task != null, "real Chunky task started in Nether");
                run.phase = 1;
            }
            case 1 -> {
                if (STARTED.get() == run.starts || generator.metrics().snapshot().inFlight() == 0) return;
                command(server, "chunky pause minecraft:the_nether");
                event("nether_chunky_paused", "\"pass\":" + run.pass + ",\"count\":" + count(run.task));
                run.phase = 2;
            }
            case 2 -> {
                if (task() != null || STOPPED.get() == run.stops) return;
                command(server, "chunky continue minecraft:the_nether");
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
                // Observe real native work on a DH thread, not just its loaded mod or database.
                if (DH_REGIONS.get() <= run.dhBefore || !TerrainQa.completed(level)
                        || server.getTickCount() - run.arrived < 100) return;
                require(generator.metrics().snapshot().failures() == 0, "no hidden native failures during integrations");
                var saved = new JsonObject();
                saved.addProperty("pass", run.pass);
                saved.addProperty("chunky_chunks", count(run.task));
                saved.addProperty("dh_native_regions", DH_REGIONS.get() - run.dhBefore);
                Files.writeString(path, saved.toString());
                event("nether_installed_integrations", saved.toString().substring(1, saved.toString().length() - 1));
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
            if (call(world, "getKey").toString().equals("minecraft:the_nether")) return value;
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
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }
}
