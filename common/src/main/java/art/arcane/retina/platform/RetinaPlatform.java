package art.arcane.retina.platform;

import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.function.Consumer;
import java.nio.file.Path;

/** Loader hooks used by shared world generation and telemetry. */
public interface RetinaPlatform {
    Path configDirectory();

    void registerWorldgen();

    void registerTerrainStats();

    /** stopped runs after level I/O closes and before loader teardown. */
    void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload,
                             Consumer<MinecraftServer> stopped);

    /** Initialize a native template mob and honor loader-specific spawn cancellation. */
    boolean finalizeStructureMob(Mob mob, ServerLevelAccessor level);

    boolean canSendTerrainStats(ServerPlayer player);

    void sendTerrainStats(ServerPlayer player, TerrainStatsPayload payload);
}
