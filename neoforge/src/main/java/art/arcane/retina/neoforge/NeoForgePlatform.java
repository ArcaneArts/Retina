package art.arcane.retina.neoforge;

import art.arcane.retina.Retina;
import art.arcane.retina.platform.RetinaPlatform;
import art.arcane.retina.worldgen.RetinaBiomeSource;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.RetinaStructurePiece;
import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.function.Consumer;

public final class NeoForgePlatform implements RetinaPlatform {
    private final IEventBus modBus;

    public NeoForgePlatform(IEventBus modBus) {
        this.modBus = modBus;
    }

    @Override
    public void registerWorldgen() {
        modBus.addListener((RegisterEvent event) -> {
            event.register(Registries.CHUNK_GENERATOR, Retina.id("gpu"), () -> RetinaChunkGenerator.CODEC);
            event.register(Registries.BIOME_SOURCE, Retina.id("voronoi"), () -> RetinaBiomeSource.CODEC);
            event.register(Registries.STRUCTURE_PIECE, Retina.id("template"), () -> RetinaStructurePiece.TYPE);
        });
    }

    @Override
    public void registerTerrainStats() {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional()
                .playToClient(TerrainStatsPayload.TYPE, TerrainStatsPayload.CODEC));
    }

    @Override
    public void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> endTick.accept(event.getServer()));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) unload.accept(level);
        });
    }

    @Override
    public boolean canSendTerrainStats(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, TerrainStatsPayload.TYPE.id());
    }

    @Override
    public void sendTerrainStats(ServerPlayer player, TerrainStatsPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
