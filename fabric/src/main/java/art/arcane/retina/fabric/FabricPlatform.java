package art.arcane.retina.fabric;

import art.arcane.retina.Retina;
import art.arcane.retina.platform.RetinaPlatform;
import art.arcane.retina.worldgen.RetinaBiomeSource;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.RetinaStructurePiece;
import art.arcane.retina.worldgen.TerrainStatsPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

public final class FabricPlatform implements RetinaPlatform {
    @Override
    public void registerWorldgen() {
        RetinaStructurePiece.register();
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Retina.id("gpu"), RetinaChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Retina.id("voronoi"), RetinaBiomeSource.CODEC);
        // The runtime QA pack supplies this feature; ordinary worlds are unaffected.
        if (Boolean.getBoolean("retina.qa.biomeModification")) {
            net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(
                    net.fabricmc.fabric.api.biome.v1.BiomeSelectors.includeByKey(net.minecraft.world.level.biome.Biomes.PLAINS),
                    net.minecraft.world.level.levelgen.GenerationStep.Decoration.VEGETAL_DECORATION,
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE, Retina.id("qa_extra_grass")));
        }
    }

    @Override
    public void registerTerrainStats() {
        PayloadTypeRegistry.clientboundPlay().register(TerrainStatsPayload.TYPE, TerrainStatsPayload.CODEC);
    }

    @Override
    public void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload) {
        ServerTickEvents.END_SERVER_TICK.register(endTick::accept);
        ServerLevelEvents.UNLOAD.register((server, level) -> unload.accept(level));
    }

    @Override
    public boolean canSendTerrainStats(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, TerrainStatsPayload.TYPE);
    }

    @Override
    public void sendTerrainStats(ServerPlayer player, TerrainStatsPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }
}
