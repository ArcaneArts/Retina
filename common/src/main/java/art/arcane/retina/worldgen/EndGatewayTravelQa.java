package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;

/** One reused-world scenario for cold pearl travel, reciprocal return, vanilla respawn and persistence. */
public final class EndGatewayTravelQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.end.gateway");
    private static final Map<MinecraftServer, Run> RUNS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Run {
        final long began = System.nanoTime();
        int phase, waitingTicks;
        long transferBegan;
        BlockPos entry, exit;
        Vec3 held;
        ThrownEnderpearl pearl;
        CompoundTag prior;
    }
    private EndGatewayTravelQa() { }
    public static boolean ready(MinecraftServer server) {
        if (!ENABLED || server == null) return true;
        var run = RUNS.get(server); return run != null && run.phase == 6;
    }
    static void tick(MinecraftServer server) {
        if (!ENABLED || server.getTickCount() < 40 || server.getPlayerList().getPlayers().isEmpty()) return;
        var run = RUNS.computeIfAbsent(server, ignored -> new Run());
        if (run.phase == 6) return;
        require(System.nanoTime() - run.began < 240_000_000_000L, "deadline phase=" + run.phase);
        var player = server.getPlayerList().getPlayers().getFirst(); var end = server.getLevel(Level.END);
        require(end != null && end.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "saved End runtime");
        var statePath = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-end-gateway.nbt");
        try {
            switch (run.phase) {
                case 0 -> {
                    run.prior = Files.exists(statePath) ? NbtIo.readCompressed(statePath, NbtAccounter.unlimitedHeap()) : null;
                    if (player.level() != end) {
                        require(player.level() == server.overworld(), "start in fixture Overworld or saved End");
                        var entry = new BlockPos(8, 80, 8);
                        require(server.overworld().getBlockState(entry).is(Blocks.END_PORTAL), "reuse existing entry portal");
                        position(player, entry);
                    }
                    run.phase = 1;
                }
                case 1 -> {
                    if (player.level() != end) return;
                    var fight = end.getDragonFight();
                    if (run.prior != null) {
                        var expected = net.minecraft.core.UUIDUtil.CODEC.parse(NbtOps.INSTANCE, run.prior.get("dragon_uuid")).getOrThrow();
                        if (end.getEntity(expected) == null) return;
                        require(expected.equals(fight.dragonUUID()), "respawned dragon UUID persists");
                        run.entry = readPos(run.prior, "entry"); run.exit = readPos(run.prior, "exit");
                        checkGateway(end, run.entry, run.exit); checkGateway(end, run.exit, run.entry);
                        event("end_gateway_respawn_reopened", "\"dragon_uuid\":\"" + expected + "\"");
                        run.phase = 6; return;
                    }
                    require(fight.hasPreviouslyKilledDragon(), "reuse completed first fight");
                    var remaining = fightData(fight).getListOrEmpty("gateways");
                    for (int index = 0; index < 20; index++) {
                        boolean present = false; for (var tag : remaining) if (tag.equals(IntTag.valueOf(index))) present = true;
                        if (!present) {
                            int x = Mth.floor(96 * Math.cos(2 * (-Math.PI + Math.PI / 20 * index)));
                            int z = Mth.floor(96 * Math.sin(2 * (-Math.PI + Math.PI / 20 * index)));
                            var at = new BlockPos(x, 75, z);
                            if (end.getBlockEntity(at) instanceof TheEndGatewayBlockEntity) { run.entry = at; break; }
                        }
                    }
                    require(run.entry != null, "actual progression gateway exists");
                    var gateway = (TheEndGatewayBlockEntity) end.getBlockEntity(run.entry);
                    if (gateway.isCoolingDown()) return;
                    player.getAbilities().flying = true; player.onUpdateAbilities();
                    position(player, run.entry.offset(3, 0, 0));
                    run.pearl = new ThrownEnderpearl(end, player, new ItemStack(Items.ENDER_PEARL));
                    run.pearl.setPos(run.entry.getX() + 1.5, run.entry.getY() + .5, run.entry.getZ() + .5);
                    run.pearl.setDeltaMovement(-1, 0, 0);
                    require(end.addFreshEntity(run.pearl), "actual owned pearl spawned");
                    event("end_gateway_pearl_started", "\"unknown_exit\":" + (((EndGatewayPreparationAccess) gateway).retina$exitPortal() == null));
                    run.transferBegan = System.nanoTime(); run.phase = 2;
                }
                case 2 -> {
                    require(run.pearl.getOwner() == player, "pearl owner remains vanilla player");
                    if (run.pearl.portalProcess instanceof GatewayPortalPreparationState state && state.retina$waitingForGateway()) {
                        if (run.held != null) require(run.pearl.position().equals(run.held), "pending pearl holds flight");
                        run.held = run.pearl.position(); run.waitingTicks++;
                    }
                    if (player.position().horizontalDistanceSqr() < 512 * 512) return;
                    require(run.pearl.isRemoved(), "destination impact consumed pearl");
                    require(player.isOnPortalCooldown(), "vanilla pearl carries portal cooldown to owner");
                    run.exit = ((EndGatewayPreparationAccess) end.getBlockEntity(run.entry)).retina$exitPortal();
                    require(run.exit != null, "source gateway exit persisted");
                    checkGateway(end, run.entry, run.exit); checkGateway(end, run.exit, run.entry);
                    event("end_gateway_pearl_arrived", "\"held_ticks\":" + run.waitingTicks + ",\"ms\":" + (System.nanoTime() - run.transferBegan) / 1e6);
                    run.pearl = null;
                    // Reset only the fixture player's normal cooldown to exercise a second transfer now.
                    position(player, run.exit); run.phase = 3;
                }
                case 3 -> {
                    if (player.position().horizontalDistanceSqr() > 192 * 192) return;
                    require(player.position().subtract(Vec3.atCenterOf(run.entry)).horizontalDistanceSqr() < 10 * 10, "reciprocal vanilla return destination");
                    event("end_gateway_reciprocal_return", "\"status_detail\":\"vanilla_destination\"");
                    var fight = end.getDragonFight(); var data = fightData(fight);
                    var podium = readPos(data, "exit_portal_location");
                    position(player, podium.offset(8, 2, 0));
                    for (var direction : Direction.Plane.HORIZONTAL) {
                        var base = podium.relative(direction, 3);
                        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.END_CRYSTAL));
                        var hit = new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false);
                        require(Items.END_CRYSTAL.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), "normal crystal item placement");
                    }
                    require(fightData(fight).contains("respawn_stage"), "vanilla four-crystal respawn began");
                    run.phase = 4;
                }
                case 4 -> {
                    var fight = end.getDragonFight(); var data = fightData(fight);
                    if (data.contains("respawn_stage") || end.getDragons().isEmpty()) return;
                    var dragon = end.getDragons().getFirst();
                    require(dragon.getUUID().equals(fight.dragonUUID()), "vanilla owns respawned dragon");
                    require(!data.getBooleanOr("dragon_killed", true), "respawn restored active fight");
                    require(data.getListOrEmpty("gateways").size() == 19, "respawn does not unlock extra gateways");
                    var saved = new CompoundTag(); saved.put("entry", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, run.entry).getOrThrow());
                    saved.put("exit", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, run.exit).getOrThrow());
                    saved.put("dragon_uuid", net.minecraft.core.UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, dragon.getUUID()).getOrThrow());
                    NbtIo.writeCompressed(saved, statePath);
                    event("end_vanilla_respawn_complete", "\"dragon_uuid\":\"" + dragon.getUUID() + "\"");
                    run.phase = 5;
                }
                case 5 -> {
                    // Let the normal client harness save/disconnect/reopen while the dragon is alive.
                    run.phase = 6;
                }
            }
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static CompoundTag fightData(EnderDragonFight fight) { return (CompoundTag) EnderDragonFight.CODEC.encodeStart(NbtOps.INSTANCE, fight).getOrThrow(); }
    private static BlockPos readPos(CompoundTag tag, String name) { return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get(name)).getOrThrow(); }
    private static void checkGateway(net.minecraft.server.level.ServerLevel level, BlockPos from, BlockPos to) {
        require(level.getBlockEntity(from) instanceof TheEndGatewayBlockEntity, "live gateway block entity");
        var metadata = (EndGatewayPreparationAccess) level.getBlockEntity(from);
        require(to.equals(metadata.retina$exitPortal()) && !metadata.retina$exactTeleport(), "reciprocal saved exit and exact flag");
    }
    private static void position(ServerPlayer player, BlockPos pos) {
        player.setPortalCooldown(0);
        require(player.teleportTo(player.level(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true), "same-dimension fixture positioning");
        player.setDeltaMovement(0, 0, 0);
    }
    private static void require(boolean condition, String label) { if (!condition) throw new IllegalStateException("End gateway QA: " + label); }
    private static void event(String name, String fields) { Retina.LOGGER.info("QA_EVT {\"event\":\"" + name + "\",\"status\":\"pass\",\"context\":{" + fields + "}}"); }
}
