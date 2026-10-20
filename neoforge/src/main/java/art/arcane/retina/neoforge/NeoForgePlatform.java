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
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
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
    public java.nio.file.Path configDirectory() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
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
    public void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload,
                                    Consumer<MinecraftServer> stopped) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> endTick.accept(event.getServer()));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) unload.accept(level);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> stopped.accept(event.getServer()));
        if (Boolean.getBoolean("retina.qa.nether.structures")) {
            NeoForge.EVENT_BUS.addListener((FinalizeSpawnEvent event) -> {
                var mob = event.getEntity();
                if (event.getSpawnType() != EntitySpawnReason.STRUCTURE
                        || !mob.level().dimension().equals(Level.NETHER)) return;
                if (mob.isAddedToLevel() || !(event.getLevel() instanceof WorldGenRegion))
                    throw new IllegalStateException("Structure initialization must use the generation region before registration");
                Retina.LOGGER.info("QA_EVT {\"event\":\"nether_structure_spawn_hook\",\"status\":\"pass\",\"context\":{\"uuid\":\"{}\"}}",
                        mob.getUUID());
            });
        }
    }

    @Override
    public boolean finalizeStructureMob(Mob mob, ServerLevelAccessor level) {
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(mob.blockPosition()),
                EntitySpawnReason.STRUCTURE, null);
        return !mob.isSpawnCancelled();
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
