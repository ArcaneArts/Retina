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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.function.Consumer;

public final class FabricPlatform implements RetinaPlatform {
    @Override public java.util.Set<String> vanillaDataPacks() {
        if (Boolean.getBoolean("retina.qa.biomeModification")) return java.util.Set.of();
        var roots = java.util.Set.of("minecraft", "java", "fabricloader", "fabric-api", "mixinextras", "retina");
        var packs = new java.util.HashSet<String>(); packs.add("vanilla"); packs.add("fabric");
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            var root = mod;
            while (root.getContainingMod().isPresent()) root = root.getContainingMod().orElseThrow();
            // Loom may expose the official API modules as top-level containers.
            var metadata = root.getMetadata();
            boolean apiModule = metadata.getId().startsWith("fabric-") && metadata.getContact().get("sources")
                    .filter("https://github.com/FabricMC/fabric"::equals).isPresent();
            if (!roots.contains(metadata.getId()) && !apiModule) {
                Retina.debug("Vanilla catalog uses live registries for additional mod {}", root.getMetadata().getId());
                return java.util.Set.of();
            }
            packs.add(mod.getMetadata().getId());
        }
        return java.util.Set.copyOf(packs);
    }

    @Override
    public java.nio.file.Path configDirectory() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }

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
    public void registerServerHooks(Consumer<MinecraftServer> endTick, Consumer<ServerLevel> unload,
                                    Consumer<MinecraftServer> stopped) {
        ServerTickEvents.END_SERVER_TICK.register(endTick::accept);
        ServerLevelEvents.UNLOAD.register((server, level) -> unload.accept(level));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(stopped::accept);
    }

    @Override
    public boolean finalizeStructureMob(Mob mob, ServerLevelAccessor level) {
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), EntitySpawnReason.STRUCTURE, null);
        return true;
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
