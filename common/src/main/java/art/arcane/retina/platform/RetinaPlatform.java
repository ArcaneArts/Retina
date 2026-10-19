package art.arcane.retina.platform;

import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;
import java.nio.file.Path;

/** Loader hooks used by shared server initialization and telemetry. */
public interface RetinaPlatform {
    Path configDirectory();

    void registerWorldgen();

    void registerTerrainStats();

    void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload);

    boolean canSendTerrainStats(ServerPlayer player);

    void sendTerrainStats(ServerPlayer player, TerrainStatsPayload payload);
}
