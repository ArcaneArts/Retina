package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Opt-in disposable-world scenario: real portal collision, vanilla fight/death, credits and reopen. */
public final class EndTravelQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.end.travel");
    private static final Map<MinecraftServer, Run> RUNS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final BlockPos ENTRY = new BlockPos(8, 80, 8);
    private static final class Run {
        final long began = System.nanoTime();
        int phase, ticks;
        long entered;
        boolean reopened;
        CompletableFuture<Void> pending;
    }
    private EndTravelQa() { }

    public static boolean ready(MinecraftServer server) {
        if (!ENABLED || server == null) return true;
        var run = RUNS.get(server);
        return run != null && run.phase == 5;
    }

    static void tick(MinecraftServer server) {
        if (!ENABLED || server.getTickCount() < 40 || server.getPlayerList().getPlayers().isEmpty()) return;
        var run = RUNS.computeIfAbsent(server, ignored -> new Run());
        if (run.phase == 5) return;
        require(System.nanoTime() - run.began < 240_000_000_000L, "deadline phase=" + run.phase);
        var player = server.getPlayerList().getPlayers().getFirst();
        var end = server.getLevel(Level.END);
        require(end != null && end.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "development End activated from saved file");
        switch (run.phase) {
            case 0 -> {
                require(player.level() == server.overworld(), "entry begins in Overworld");
                run.reopened = end.getDragonFight().hasPreviouslyKilledDragon();
                if (!run.reopened) {
                    var root = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(Level.END, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).resolve("region");
                    for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++)
                        require(!Files.exists(root.resolve("r." + x + "." + z + ".mca")), "all four arena regions start cold");
                }
                run.pending = PortalRegionPreparation.prepareArea(server.overworld(), ENTRY, 32);
                run.phase = 1;
            }
            case 1 -> {
                if (!run.pending.isDone()) return;
                run.pending.join();
                // Fixture supplies a portal; actual collision and PortalProcessor must do the transfer.
                for (var pos : BlockPos.betweenClosed(ENTRY.offset(-1, -1, -1), ENTRY.offset(1, 3, 1)))
                    server.overworld().setBlockAndUpdate(pos, (pos.getY() < ENTRY.getY() ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState());
                server.overworld().setBlockAndUpdate(ENTRY, Blocks.END_PORTAL.defaultBlockState());
                enter(player, ENTRY);
                run.entered = System.nanoTime(); run.phase = 2;
            }
            case 2 -> {
                if (player.level() != end) return;
                require(Math.abs(player.getX() - 100.5) < 2 && Math.abs(player.getZ() - .5) < 2, "vanilla arrival coordinates");
                for (int x = 98; x <= 102; x++) for (int z = -2; z <= 2; z++)
                    require(end.getBlockState(new BlockPos(x, 48, z)).is(Blocks.OBSIDIAN), "arrival platform regenerated");
                for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++)
                    require(Files.exists(net.minecraft.world.level.dimension.DimensionType.getStorageFolder(Level.END, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))
                            .resolve("region/r." + x + "." + z + ".mca")), "all arena regions published before arrival");
                event("end_normal_entry", "\"reopened\":" + run.reopened + ",\"cold_arrival_ms\":" + (System.nanoTime() - run.entered) / 1e6);
                run.phase = 3;
            }
            case 3 -> {
                var fight = end.getDragonFight();
                if (!run.reopened && !fight.hasPreviouslyKilledDragon()) {
                    if (end.getDragons().isEmpty()) return;
                    var dragon = end.getDragons().getFirst();
                    require(dragon.getUUID().equals(fight.dragonUUID()), "vanilla fight owns the dragon");
                    if (run.ticks++ == 0) {
                        var crystals = end.getEntitiesOfClass(EndCrystal.class, new AABB(-128, 0, -128, 128, 256, 128));
                        require(crystals.size() == 10, "ten initial spike crystals loaded");
                        int guarded = 0;
                        for (var spike : EndSpikeFeature.getSpikesForLevel(end)) {
                            require(end.getBlockState(new BlockPos(spike.getCenterX(), spike.getHeight(), spike.getCenterZ())).is(Blocks.BEDROCK), "seeded spike top");
                            if (spike.isGuarded()) {
                                guarded++;
                                require(end.getBlockState(new BlockPos(spike.getCenterX() - 2, spike.getHeight() + 1, spike.getCenterZ())).is(Blocks.IRON_BARS), "guarded crystal cage");
                            }
                        }
                        require(guarded == 2, "two guarded spikes");
                        for (var crystal : crystals) require(crystal.hurtServer(end, end.damageSources().playerAttack(player), 10), "normal crystal damage");
                        event("end_initial_arena", "\"crystals\":10,\"cages\":2");
                    }
                    // Exercise vanilla damage, dying flight and death animation. Never set fight flags.
                    if (run.ticks % 20 == 1) dragon.hurtServer(end, end.damageSources().playerAttack(player), 10000);
                    return;
                }
                var data = (CompoundTag) EnderDragonFight.CODEC.encodeStart(NbtOps.INSTANCE, fight).getOrThrow();
                require(data.getBooleanOr("dragon_killed", false), "vanilla dragon death progression");
                require(data.getListOrEmpty("gateways").size() == 19, "one progression gateway unlocked");
                boolean egg = false;
                for (int y = end.getMinY(); y < end.getMaxY(); y++) egg |= end.getBlockState(new BlockPos(0, y, 0)).is(Blocks.DRAGON_EGG);
                require(egg, "first dragon egg persists");
                BlockPos exit = null;
                for (int z = -1; z <= 0; z++) for (int x = -1; x <= 0; x++)
                    for (var blockEntity : end.getChunk(x, z).getBlockEntities().values())
                        if (blockEntity.getBlockState().is(Blocks.END_PORTAL)) exit = blockEntity.getBlockPos();
                require(exit != null, "vanilla activated exit podium");
                event("end_dragon_progression", "\"reopened\":" + run.reopened + ",\"remaining_gateways\":19");
                // Missing bed in a cold region: vanilla must inspect it and then choose the shared
                // spawn. In particular the first credits exit must prepare before removing the player.
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(net.minecraft.world.level.storage.LevelData.RespawnData.of(
                        Level.OVERWORLD, new BlockPos(-1024, 80, 1024), 0, 0), false), false);
                enter(player, exit);
                run.phase = 4;
            }
            case 4 -> {
                if (player.level() != server.overworld() || player.isRemoved()) return;
                require(player.seenCredits, "first-exit credits completed through client response");
                event("end_normal_return", "\"reopened\":" + run.reopened + ",\"seen_credits\":true");
                run.phase = 5;
            }
        }
    }

    private static void enter(ServerPlayer player, BlockPos pos) {
        player.setPortalCooldown(0);
        require(player.teleportTo(player.level(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true), "same-dimension portal positioning");
        player.setDeltaMovement(0, 0, 0);
    }
    private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("End travel QA: " + label); }
    private static void event(String name, String fields) { Retina.LOGGER.info("QA_EVT {\"event\":\"" + name + "\",\"status\":\"pass\",\"context\":{" + fields + "}}"); }
}
