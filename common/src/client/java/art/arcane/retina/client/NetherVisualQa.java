package art.arcane.retina.client;

import art.arcane.retina.Retina;
import art.arcane.retina.worldgen.PortalRegionPreparation;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Opt-in framebuffer captures from a disposable copy of a saved Nether world. */
public final class NetherVisualQa {
    private static final String SPEC = System.getProperty("retina.qa.client.visual", "");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private record Scene(String name, double x, double y, double z, float yaw, float pitch) {}
    private record Tour(String world, List<Scene> scenes) {}
    private static Tour tour;
    private static int phase, index;
    private static long started, settled;
    private static String runId;
    private static CompletableFuture<JsonObject> arrival;
    private static CompletableFuture<Void> capture;
    private static JsonObject metadata;
    private static Path screenshot;

    private NetherVisualQa() {}

    public static void tick(Minecraft minecraft) {
        if (SPEC.isBlank() || phase == 5) return;
        try {
            if (started == 0) {
                started = System.nanoTime();
                runId = Long.toString(System.currentTimeMillis());
                if (Boolean.getBoolean("retina.qa.client.gameplay"))
                    throw new IllegalArgumentException("Visual QA runs separately from client gameplay QA");
                tour = JSON.fromJson(Files.readString(Path.of(SPEC)), Tour.class);
            }
            if (System.nanoTime() - started > 1_800_000_000_000L)
                throw new IllegalStateException("Nether visual capture timed out in phase " + phase + ", scene " + index);
            switch (phase) {
                case 0 -> {
                    if (!(minecraft.gui.screen() instanceof TitleScreen) || minecraft.gui.overlay() != null) return;
                    minecraft.options.pauseOnLostFocus = false;
                    minecraft.options.gamma().set(0.5);
                    if (!minecraft.gui.hud.isHidden()) minecraft.gui.hud.toggle();
                    phase = 1;
                    minecraft.createWorldOpenFlows().openWorld(tour.world(),
                            () -> { throw new IllegalStateException("Visual QA world open cancelled"); });
                }
                case 1 -> {
                    if (minecraft.gui.screen() instanceof BackupConfirmScreen backup) {
                        backup.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                                .filter(button -> button.getMessage().equals(BackupConfirmScreen.BACKUP_AND_JOIN))
                                .findFirst().orElseThrow().onPress(null);
                        return;
                    }
                    if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
                    beginScene(minecraft);
                }
                case 2 -> {
                    if (!arrival.isDone()) return;
                    metadata = arrival.join();
                    var scene = tour.scenes().get(index);
                    if (minecraft.player == null || minecraft.level == null
                            || !minecraft.level.dimension().equals(Level.NETHER)
                            || minecraft.player.distanceToSqr(scene.x(), scene.y(), scene.z()) > 0.25
                            || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) {
                        settled = 0;
                        return;
                    }
                    // A drained renderer can still be waiting for chunk packets after teleport.
                    // Inspect actual client storage before using the game's screenshot conditions.
                    var center = minecraft.player.chunkPosition();
                    int radius = Math.max(1, minecraft.options.renderDistance().get() - 1);
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            if (dx * dx + dz * dz <= radius * radius
                                    && !minecraft.level.getChunkSource().hasChunk(center.x() + dx, center.z() + dz)) {
                                settled = 0;
                                return;
                            }
                        }
                    }
                    // The game's own automatic screenshots use these renderer conditions.
                    // Require a stable view afterward so chunk fade and camera interpolation finish.
                    if (minecraft.levelExtractor.countRenderedSections() <= 10
                            || !minecraft.levelRenderer.hasRenderedAllSections()) {
                        settled = 0;
                        return;
                    }
                    if (settled == 0) settled = System.nanoTime();
                    if (System.nanoTime() - settled < 5_000_000_000L) return;
                    String name = runId + "-" + index + "-" + scene.name().replaceAll("[^a-zA-Z0-9_-]", "_") + ".png";
                    screenshot = minecraft.gameDirectory.toPath().resolve("screenshots").resolve(name);
                    metadata.addProperty("screenshot", name);
                    metadata.addProperty("gamma", minecraft.options.gamma().get());
                    metadata.addProperty("render_distance", minecraft.options.renderDistance().get());
                    metadata.addProperty("rendered_sections", minecraft.levelExtractor.countRenderedSections());
                    metadata.addProperty("loaded_chunks", minecraft.level.getChunkSource().getLoadedChunksCount());
                    metadata.addProperty("width", minecraft.gameRenderer.mainRenderTarget().width);
                    metadata.addProperty("height", minecraft.gameRenderer.mainRenderTarget().height);
                    capture = new CompletableFuture<>();
                    Screenshot.grab(minecraft.gameDirectory, name, minecraft.gameRenderer.mainRenderTarget(), 1,
                            message -> capture.complete(null));
                    phase = 3;
                }
                case 3 -> {
                    if (!capture.isDone()) return;
                    capture.join();
                    if (Files.size(screenshot) == 0) throw new IllegalStateException("Empty screenshot: " + screenshot);
                    Files.writeString(screenshot.resolveSibling(screenshot.getFileName() + ".json"), JSON.toJson(metadata));
                    Retina.LOGGER.info("QA_EVT {\"event\":\"nether_visual_capture\",\"status\":\"pass\",\"context\":{}}", metadata);
                    index++;
                    if (index < tour.scenes().size()) beginScene(minecraft);
                    else {
                        minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
                        minecraft.disconnect(new TitleScreen(), false);
                        phase = 4;
                    }
                }
                case 4 -> {
                    if (!(minecraft.gui.screen() instanceof TitleScreen) || minecraft.getSingleplayerServer() != null) return;
                    Retina.LOGGER.info("QA_EVT {\"event\":\"nether_visual_tour_saved\",\"status\":\"pass\",\"context\":{\"captures\":{}}}", index);
                    phase = 5;
                    minecraft.stop();
                }
                default -> throw new IllegalStateException("Unknown visual QA phase " + phase);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Nether visual QA failed", error);
        }
    }

    private static void beginScene(Minecraft minecraft) {
        var scene = tour.scenes().get(index);
        var server = minecraft.getSingleplayerServer();
        var playerId = minecraft.player.getUUID();
        arrival = CompletableFuture.supplyAsync(() -> {
            var level = server.getLevel(Level.NETHER);
            if (level == null || !(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator))
                throw new IllegalStateException("Visual QA requires an automatically adapted Retina Nether");
            return PortalRegionPreparation.prepareArea(level, BlockPos.containing(scene.x(), scene.y(), scene.z()), 160);
        }, server).thenCompose(future -> future).thenApplyAsync(ignored -> {
            var level = server.getLevel(Level.NETHER);
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null) throw new IllegalStateException("Visual QA player disconnected");
            player.setGameMode(GameType.SPECTATOR);
            player.removeAllEffects();
            player.teleportTo(level, scene.x(), scene.y(), scene.z(), Set.of(), scene.yaw(), scene.pitch(), true);
            var at = BlockPos.containing(scene.x(), scene.y() + player.getEyeHeight(), scene.z());
            var data = JSON.toJsonTree(scene).getAsJsonObject();
            data.addProperty("dimension", level.dimension().identifier().toString());
            data.addProperty("seed", level.getSeed());
            data.addProperty("biome_at_camera", level.getBiome(at).unwrapKey().orElseThrow().identifier().toString());
            data.addProperty("block_at_camera", level.getBlockState(at).toString());
            data.addProperty("block_light_at_camera", level.getBrightness(LightLayer.BLOCK, at));
            data.addProperty("sky_light_at_camera", level.getBrightness(LightLayer.SKY, at));
            return data;
        }, server);
        settled = 0;
        phase = 2;
    }
}
