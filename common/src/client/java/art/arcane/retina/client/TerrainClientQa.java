package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.TerrainRegistryQa;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Opt-in real-client gameplay, payload and save/reopen checks in an isolated run directory. */
public final class TerrainClientQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.client.gameplay");
    private static final String MODE = System.getProperty("retina.qa.client.mode", "mca");
    private static final String WORLD = "retina-qa-" + MODE;
    private static final String SERVER = System.getProperty("retina.qa.client.server", "");
    private static final long HOLD_NANOS = Math.max(0, Integer.getInteger("retina.qa.client.holdSeconds", 0)) * 1_000_000_000L;
    private static final String COMMANDS = System.getProperty("retina.qa.client.commands", "");
    private static final BlockPos EDIT = new BlockPos(-17, 120, -17);
    private static int phase;
    private static long started, packets, disconnects, packetsBeforeReopen, disconnectsBeforeLeave;
    private static CompletableFuture<Void> serverCheck;
    private static CompletableFuture<Void> commandsCheck;
    private static long readySince;

    private TerrainClientQa() { }

    static void received() { if (ENABLED) packets++; }
    static void disconnected() { if (ENABLED) disconnects++; }

    public static void tick(Minecraft minecraft) {
        if (!ENABLED || phase == 6) return;
        if (started == 0) started = System.nanoTime();
        if (System.nanoTime() - started > 300_000_000_000L) throw new IllegalStateException("Retina client gameplay QA timed out in phase " + phase);
        switch (phase) {
            case 0 -> {
                if (!(minecraft.gui.screen() instanceof TitleScreen) || minecraft.gui.overlay() != null) return;
                phase = 1;
                minecraft.options.pauseOnLostFocus = false;
                if (!SERVER.isBlank()) {
                    connect(minecraft);
                    return;
                }
                String datapack = System.getProperty("retina.qa.client.datapack", "");
                var configuration = datapack.isBlank() ? WorldDataConfiguration.DEFAULT
                        : new WorldDataConfiguration(new DataPackConfig(List.of("vanilla", "file/" + datapack), List.of()),
                                WorldDataConfiguration.DEFAULT.enabledFeatures());
                var settings = new LevelSettings("Retina QA " + MODE, GameType.CREATIVE,
                        LevelSettings.DifficultySettings.DEFAULT, true, configuration);
                minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(123456789L, true, false),
                        registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Retina.id(MODE.equals("chunk") ? "gpu_chunk" : "gpu")))
                                .value().createWorldDimensions(), new TitleScreen());
            }
            case 1 -> {
                if (!ready(minecraft) || packets == 0) return;
                if (readySince == 0) {
                    readySince = System.nanoTime();
                    if (!COMMANDS.isBlank()) {
                        var server = minecraft.getSingleplayerServer();
                        require(server != null, "QA commands require an integrated server");
                        commandsCheck = CompletableFuture.runAsync(() -> {
                            for (String command : COMMANDS.split("\\|")) {
                                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                            }
                        }, server);
                    }
                }
                if (commandsCheck != null) {
                    if (!commandsCheck.isDone()) return;
                    commandsCheck.join();
                }
                if (System.nanoTime() - readySince < HOLD_NANOS) return;
                checkDisplay(minecraft);
                disconnectsBeforeLeave = disconnects;
                serverCheck = checkServer(minecraft, false);
                phase = 2;
            }
            case 2 -> {
                if (!serverCheck.isDone()) return;
                serverCheck.join();
                packetsBeforeReopen = packets;
                disconnect(minecraft);
                phase = 3;
            }
            case 3 -> {
                if (!(minecraft.gui.screen() instanceof TitleScreen) || minecraft.gui.overlay() != null) return;
                // TCP disconnect callbacks may be dispatched on the next client tick.
                if (disconnects <= disconnectsBeforeLeave || TerrainDebugEntry.remoteStats().active()) return;
                event("minecraft_client_disconnect_reset", "\"disconnects\":" + disconnects);
                phase = 4;
                serverCheck = null;
                readySince = 0;
                if (!SERVER.isBlank()) connect(minecraft);
                else minecraft.createWorldOpenFlows().openWorld(WORLD, () -> { throw new IllegalStateException("QA world reopen cancelled"); });
            }
            case 4 -> {
                if (SERVER.isBlank() && minecraft.gui.screen() instanceof BackupConfirmScreen backup) {
                    // Custom datapack registries mark the QA world experimental. Take the normal
                    // backup-and-join path when reopening this freshly created disposable world.
                    var join = backup.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(button -> button.getMessage().equals(BackupConfirmScreen.BACKUP_AND_JOIN)).findFirst().orElseThrow();
                    event("minecraft_client_experimental_backup", "\"world\":\"" + WORLD + "\"");
                    join.onPress(null);
                    return;
                }
                if (!ready(minecraft) || packets <= packetsBeforeReopen) return;
                if (readySince == 0) readySince = System.nanoTime();
                if (System.nanoTime() - readySince < HOLD_NANOS) return;
                if (serverCheck == null) {
                    checkDisplay(minecraft);
                    disconnectsBeforeLeave = disconnects;
                    serverCheck = checkServer(minecraft, true);
                    return;
                }
                if (!serverCheck.isDone()) return;
                serverCheck.join();
                disconnect(minecraft);
                phase = 5;
            }
            case 5 -> {
                if (disconnects <= disconnectsBeforeLeave || TerrainDebugEntry.remoteStats().active()) return;
                event("minecraft_client_gameplay_reopen", "\"mode\":\"" + MODE + "\",\"dedicated\":" + !SERVER.isBlank() + ",\"packets\":" + packets + ",\"disconnects\":" + disconnects);
                phase = 6;
                minecraft.stop();
            }
            default -> throw new IllegalStateException("Unknown gameplay QA phase " + phase);
        }
    }

    private static boolean ready(Minecraft minecraft) {
        var payload = TerrainDebugEntry.remoteStats();
        return minecraft.level != null && minecraft.player != null && (!SERVER.isBlank() || minecraft.getSingleplayerServer() != null)
                && payload.active() && payload.stats().stages().chunks() > 0;
    }

    private static void connect(Minecraft minecraft) {
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(SERVER),
                new ServerData("Retina QA", SERVER, ServerData.Type.OTHER), false, null);
    }

    private static void disconnect(Minecraft minecraft) {
        // The pause menu closes the network channel before tearing down the level.
        minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        minecraft.disconnect(new TitleScreen(), false);
    }

    private static CompletableFuture<Void> checkServer(Minecraft minecraft, boolean reopened) {
        if (!SERVER.isBlank()) return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<Void>();
        minecraft.getSingleplayerServer().execute(() -> {
            try {
                var level = minecraft.getSingleplayerServer().overworld();
                require(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "integrated world retains Retina generator");
                var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
                require(generator.mode().equals(MODE), "integrated world retains generation mode");
                TerrainRegistryQa.check(generator);
                if (reopened) require(level.getBlockState(EDIT).is(Blocks.DIAMOND_BLOCK), "saved negative-coordinate chunk preserves edit");
                else {
                    level.setBlock(EDIT, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    require(level.getBlockState(EDIT).is(Blocks.DIAMOND_BLOCK), "QA edit reaches the loaded chunk");
                }
                event(reopened ? "minecraft_client_saved_edit" : "minecraft_client_negative_edit", "\"x\":-17,\"y\":120,\"z\":-17");
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private static void checkDisplay(Minecraft minecraft) {
        var payload = TerrainDebugEntry.remoteStats();
        require(payload.mode().equals(MODE) && !payload.backend().isBlank(), "real payload retains mode and native backend");
        var lines = new ArrayList<String>();
        var displayer = new DebugScreenDisplayer() {
            public void addPriorityLine(String line) { lines.add(line); }
            public void addLine(String line) { lines.add(line); }
            public void addToGroup(Identifier group, Collection<String> text) { lines.addAll(text); }
            public void addToGroup(Identifier group, String text) { lines.add(text); }
        };
        DebugScreenEntries.getEntry(Retina.id("generation")).display(displayer, minecraft.level,
                minecraft.level.getChunk(minecraft.player.chunkPosition().x(), minecraft.player.chunkPosition().z()), null);
        require(lines.stream().anyMatch(line -> line.contains("Retina")) && lines.stream().anyMatch(line -> line.contains(payload.backend())), "loaded F3 entry displays received telemetry");
        event("minecraft_client_received_telemetry", "\"mode\":\"" + MODE + "\",\"packets\":" + packets + ",\"lines\":" + lines.size());
    }

    private static void event(String name, String context) {
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
