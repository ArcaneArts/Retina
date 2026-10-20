package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.portal.PortalShape;

import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Opt-in real-player portal, storage and lighting checks in disposable QA worlds. */
public final class NetherLifecycleQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.lifecycle");
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final BlockPos OVERWORLD_PORTAL = new BlockPos(128, 80, 128);
    private static final BlockPos NETHER_PORTAL = new BlockPos(16, 70, 16);
    private static final BlockPos COLD_PORTAL = new BlockPos(24576, 100, 24576);
    private static final BlockPos SAVED_MARKER = OVERWORLD_PORTAL.offset(-2, -1, 4);
    private static final ChunkPos STORAGE_PROBE = new ChunkPos(80, 80);
    private static final List<Block> EMITTERS = List.of(Blocks.LAVA, Blocks.GLOWSTONE, Blocks.SHROOMLIGHT);

    private NetherLifecycleQa() {}

    private static final class Session {
        Run run;
        int completed;
    }

    private static final class Run {
        final ServerPlayer player;
        final long began = System.nanoTime();
        int phase, since;
        boolean reopened;
        List<Read> reads;
        CompletableFuture<Void> preparation;
        long generatedBefore;
        long portalBegan, lastPortalTick, maximumPortalTickGap;
        int portalTicks;
        long dhCompletedBefore, dhStartedBefore;
        int dhActiveAtStart;
        BlockPos createdPortal;
        Run(ServerPlayer player) { this.player = player; }
    }

    private record Read(CompletableFuture<Optional<CompoundTag>> result, boolean observerCancelled) {}

    static void unload(ServerLevel level) {
        if (ENABLED && level.dimension().equals(Level.NETHER)) SESSIONS.remove(level.getServer());
    }

    static void tick(MinecraftServer server) {
        if (!ENABLED) return;
        var session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            if (session.run != null) {
                require(session.run.phase == 10, "player disconnected before lifecycle checks completed");
                session.run = null;
            }
            if (server.isDedicatedServer() && session.completed >= 2) server.halt(false);
            return;
        }
        require(players.size() == 1, "lifecycle fixture requires one QA player");
        var player = players.getFirst();
        if (player.tickCount < 20) return;
        if (session.run == null) session.run = new Run(player);
        var run = session.run;
        require(run.player == player, "QA player connection changed during a run");
        if (run.phase == 10) return;
        require(System.nanoTime() - run.began < 360_000_000_000L,
                "lifecycle deadline in phase " + run.phase + ", dimension " + player.level().dimension().identifier());
        if (run.phase == 7) {
            long now = System.nanoTime();
            run.maximumPortalTickGap = Math.max(run.maximumPortalTickGap, now - run.lastPortalTick);
            run.lastPortalTick = now;
            run.portalTicks++;
        }
        var overworld = server.overworld();
        var nether = server.getLevel(Level.NETHER);
        require(nether != null, "Nether exists");
        switch (run.phase) {
            case 0 -> {
                AutomaticGenerationQa.check(server);
                require(player.level() == overworld, "QA player starts in Overworld");
                require(!nether.dimensionType().hasSkyLight() && nether.dimensionType().hasCeiling()
                        && nether.dimensionType().logicalHeight() == 128
                        && nether.dimensionType().coordinateScale() == 8.0, "vanilla Nether gameplay properties");
                run.reads = new ArrayList<>();
                run.reads.add(readWithCancelledObserver(overworld, STORAGE_PROBE, true));
                for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
                    run.reads.add(readWithCancelledObserver(nether,
                            new ChunkPos(STORAGE_PROBE.x() + x, STORAGE_PROBE.z() + z), x == 0 && z == 0));
                // Fixtures must not synchronously generate regions on the tick thread.
                run.preparation = CompletableFuture.allOf(
                        PortalRegionPreparation.prepareArea(overworld, BlockPos.ZERO, 256),
                        PortalRegionPreparation.prepareArea(nether, BlockPos.ZERO, 256));
                run.phase = 1;
            }
            case 1 -> {
                if (!run.preparation.isDone() || run.reads.stream().anyMatch(read -> !read.result().isDone())) return;
                run.preparation.join();
                var owTag = run.reads.get(0).result().join().orElseThrow();
                var netherTag = run.reads.get(1).result().join().orElseThrow();
                require(!owTag.getListOrEmpty("sections").equals(netherTag.getListOrEmpty("sections")),
                        "same-coordinate dimension reads contain independent terrain");
                checkStorageBounds(overworld, owTag, STORAGE_PROBE);
                int emitters = 0, adjacent = 0;
                for (int i = 1; i < run.reads.size(); i++) {
                    var tag = run.reads.get(i).result().join().orElseThrow();
                    checkStorageBounds(nether, tag, new ChunkPos(
                            STORAGE_PROBE.x() + (i - 1) % 4, STORAGE_PROBE.z() + (i - 1) / 4));
                    var light = checkGeneratedLight(tag);
                    emitters += light.emitters();
                    adjacent += light.adjacent();
                }
                require(emitters > 0 && adjacent > 0,
                        "generated probes exercise emission and propagation: " + emitters + "/" + adjacent);
                event("nether_saved_gpu_light", "\"chunks\":16,\"emitters\":" + emitters + ",\"air_neighbors\":" + adjacent);
                event("nether_concurrent_storage", "\"requests\":" + run.reads.size() + ",\"cancelled_observers\":"
                        + run.reads.stream().filter(Read::observerCancelled).count());
                run.reopened = overworld.getBlockState(SAVED_MARKER).is(Blocks.EMERALD_BLOCK);
                AutomaticGenerationQa.checkSavedEdits(server, run.reopened);
                portals(overworld, nether, run.reopened);
                enter(player, overworld, OVERWORLD_PORTAL);
                run.phase = 2;
            }
            case 2 -> {
                if (player.level() != nether) return;
                near(player, NETHER_PORTAL);
                event("nether_portal_arrival", "\"direction\":\"to_nether\",\"scale\":8");
                // Leave the portal so its ordinary cooldown can elapse.
                position(player, nether, NETHER_PORTAL.offset(0, 0, 4));
                lightFixtures(nether, true);
                run.since = server.getTickCount();
                run.phase = 3;
            }
            case 3 -> {
                if (server.getTickCount() - run.since < 80 || !lightMatches(nether, true)) return;
                event("nether_live_block_light", "\"emitters\":3,\"source\":15,\"glass\":14,\"air\":13,\"skylight\":0");
                lightFixtures(nether, false);
                run.since = server.getTickCount();
                run.phase = 4;
            }
            case 4 -> {
                if (server.getTickCount() - run.since < 20 || !lightMatches(nether, false)) return;
                event("nether_live_light_removal", "\"emitters\":3,\"remaining_light\":0");
                enter(player, nether, NETHER_PORTAL);
                run.phase = 5;
            }
            case 5 -> {
                if (player.level() != overworld) return;
                near(player, OVERWORLD_PORTAL);
                position(player, overworld, OVERWORLD_PORTAL.offset(0, 0, 4));
                overworld.setBlock(SAVED_MARKER, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
                event("nether_portal_return", "\"direction\":\"to_overworld\",\"scale\":8");
                run.preparation = PortalRegionPreparation.prepareArea(overworld, COLD_PORTAL, 256);
                run.since = server.getTickCount();
                run.phase = 6;
            }
            case 6 -> {
                // Telemetry is sent once per second; a warm destination can be ready immediately.
                if (!run.preparation.isDone() || server.getTickCount() - run.since < 40) return;
                run.preparation.join();
                if (run.reopened)
                    require(PortalShape.findAnyShape(overworld, COLD_PORTAL, Direction.Axis.X).isComplete(),
                            "saved remote source portal remains complete");
                else portal(overworld, COLD_PORTAL);
                run.generatedBefore = ((RetinaChunkGenerator) nether.getChunkSource().getGenerator()).metrics().snapshot().total();
                enter(player, overworld, COLD_PORTAL);
                run.portalBegan = run.lastPortalTick = System.nanoTime();
                run.maximumPortalTickGap = 0;
                run.portalTicks = 0;
                run.dhCompletedBefore = PortalBackgroundQa.completed();
                run.dhStartedBefore = PortalBackgroundQa.started();
                run.dhActiveAtStart = PortalBackgroundQa.active();
                run.phase = 7;
            }
            case 7 -> {
                if (player.level() != nether) return;
                require(Math.abs(player.getX() - COLD_PORTAL.getX() / 8.0) < 20
                        && Math.abs(player.getZ() - COLD_PORTAL.getZ() / 8.0) < 20,
                        "vanilla creates the exit within its scaled search area");
                run.createdPortal = player.blockPosition();
                require(PortalShape.findAnyShape(nether, run.createdPortal, Direction.Axis.X).isComplete()
                                || PortalShape.findAnyShape(nether, run.createdPortal, Direction.Axis.Z).isComplete(),
                        "vanilla-created destination is a complete portal");
                long generated = ((RetinaChunkGenerator) nether.getChunkSource().getGenerator()).metrics().snapshot().total()
                        - run.generatedBefore;
                require(generated == (run.reopened ? 0 : 4096),
                        "remote travel generates only its four destination regions, and reuses them on reopen: " + generated);
                event("nether_cold_portal_arrival", "\"generated_chunks\":" + generated + ",\"reopened\":" + run.reopened
                        + ",\"portal_ms\":" + (System.nanoTime() - run.portalBegan) / 1e6
                        + ",\"server_ticks\":" + run.portalTicks
                        + ",\"maximum_tick_gap_ms\":" + run.maximumPortalTickGap / 1e6);
                if (PortalBackgroundQa.ENABLED) {
                    long completed = PortalBackgroundQa.completed() - run.dhCompletedBefore;
                    require(run.reopened || completed > 0,
                            "cold portal wait overlaps successful native generation on an actual DH thread");
                    event("nether_portal_dh_overlap", "\"reopened\":" + run.reopened
                            + ",\"active_at_start\":" + run.dhActiveAtStart
                            + ",\"started_during_wait\":" + (PortalBackgroundQa.started() - run.dhStartedBefore)
                            + ",\"completed_during_wait\":" + completed);
                }
                run.since = server.getTickCount();
                run.phase = 8;
            }
            case 8 -> {
                // Allow the connected client to receive the destination and its telemetry.
                if (server.getTickCount() - run.since < 60) return;
                enter(player, nether, run.createdPortal);
                run.phase = 9;
            }
            case 9 -> {
                if (player.level() != overworld) return;
                // A newly created Nether frame can sit at the search edge. Its
                // scaled return may put the source just outside the 128-block
                // Overworld search, legitimately creating a different exit.
                require(Math.abs(player.getX() - run.createdPortal.getX() * 8.0) < 150
                        && Math.abs(player.getZ() - run.createdPortal.getZ() * 8.0) < 150,
                        "remote return stays within the scaled vanilla search/frame area");
                require(PortalShape.findAnyShape(overworld, player.blockPosition(), Direction.Axis.X).isComplete()
                                || PortalShape.findAnyShape(overworld, player.blockPosition(), Direction.Axis.Z).isComplete(),
                        "remote return uses a complete Overworld portal");
                event("nether_cold_portal_return", "\"reopened\":" + run.reopened
                        + ",\"x\":" + player.getX() + ",\"y\":" + player.getY() + ",\"z\":" + player.getZ());
                position(player, overworld, COLD_PORTAL.offset(0, 0, 4));
                run.phase = 10;
                session.completed++;
                event("nether_lifecycle_complete", "\"dedicated\":" + server.isDedicatedServer()
                        + ",\"connection\":" + session.completed + ",\"saved_fixtures\":" + run.reopened);
            }
            default -> throw new IllegalStateException("Unknown Nether lifecycle phase " + run.phase);
        }
    }

    private static Read readWithCancelledObserver(ServerLevel level, ChunkPos pos, boolean cancelObserver) {
        var worker = (IOWorker) level.getChunkSource().chunkMap.chunkScanner();
        var owner = worker.loadAsync(pos);
        return new Read(owner, cancelObserver && worker.loadAsync(pos).cancel(false));
    }

    private static void checkStorageBounds(ServerLevel level, CompoundTag tag, ChunkPos pos) {
        require(tag.getIntOr("xPos", 0) == pos.x()
                && tag.getIntOr("zPos", 0) == pos.z(), "correct requested chunk");
        int sections = 0;
        for (var entry : tag.getListOrEmpty("sections")) {
            var section = (CompoundTag) entry;
            if (!section.contains("block_states")) continue;
            int y = section.getIntOr("Y", 0) * 16;
            require(y >= level.getMinY() && y < level.getMaxY(), "section within actual dimension storage");
            sections++;
        }
        require(sections == level.getSectionsCount(), "all dimension storage sections retained");
    }

    private record LightStats(int emitters, int adjacent) {}
    private record LightSection(PalettedContainer<BlockState> blocks, byte[] light) {}

    private static LightStats checkGeneratedLight(CompoundTag tag) {
        require(tag.getBooleanOr("isLightOn", false), "native interior chunk is prelit");
        var codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        var sections = new TreeMap<Integer, LightSection>();
        for (var entry : tag.getListOrEmpty("sections")) {
            var section = (CompoundTag) entry;
            for (byte sky : section.getByteArray("SkyLight").orElse(new byte[0]))
                require(sky == 0, "native Nether data has no skylight");
            if (!section.contains("block_states")) continue;
            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
            var light = section.getByteArray("BlockLight").orElse(new byte[2048]);
            sections.put(section.getIntOr("Y", 0), new LightSection(blocks, light));
        }
        int emitters = 0, adjacent = 0;
        for (var entry : sections.entrySet()) {
            var blocks = entry.getValue().blocks();
            var light = entry.getValue().light();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int emission = blocks.get(x, y, z).getLightEmission();
                if (emission == 0) continue;
                emitters++;
                require(nibble(light, x, y, z) >= emission, "native emitter retains its block light");
                for (var direction : Direction.values()) {
                    int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                    var neighbor = sections.get(entry.getKey() + Math.floorDiv(ny, 16));
                    if (neighbor != null && neighbor.blocks().get(nx, Math.floorMod(ny, 16), nz).isAir()) {
                        require(nibble(neighbor.light(), nx, Math.floorMod(ny, 16), nz) >= emission - 1,
                                "native light propagates into adjacent air");
                        adjacent++;
                    }
                }
            }
        }
        return new LightStats(emitters, adjacent);
    }

    private static int nibble(byte[] light, int x, int y, int z) {
        int index = y * 256 + z * 16 + x;
        return (Byte.toUnsignedInt(light[index / 2]) >> ((index & 1) * 4)) & 15;
    }

    private static void portals(ServerLevel overworld, ServerLevel nether, boolean reopened) {
        var levels = List.of(overworld, nether, overworld, nether);
        // Incorrect unscaled travel would select the last two portals instead.
        var positions = List.of(OVERWORLD_PORTAL, NETHER_PORTAL, new BlockPos(16, 80, 16), new BlockPos(128, 70, 128));
        for (int i = 0; i < levels.size(); i++) {
            if (reopened) require(PortalShape.findAnyShape(levels.get(i), positions.get(i), Direction.Axis.X).isComplete(),
                    "saved portal remains complete without rebuilding");
            else portal(levels.get(i), positions.get(i));
        }
        event("nether_portal_fixtures", "\"portals\":4,\"preserved\":" + reopened);
    }

    private static void portal(ServerLevel level, BlockPos interior) {
        // Supported platform and room keep the fixture player clear of terrain.
        for (var pos : BlockPos.betweenClosed(interior.offset(-2, -1, -2), interior.offset(3, 4, 5))) {
            level.setBlock(pos, pos.getY() == interior.getY() - 1
                    ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
        for (int y = -1; y <= 3; y++) for (int x = -1; x <= 2; x++) {
            if (y == -1 || y == 3 || x == -1 || x == 2)
                level.setBlock(interior.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
        }
        PortalShape.findEmptyPortalShape(level, interior, Direction.Axis.X).orElseThrow().createPortalBlocks(level);
    }

    private static void enter(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.setPortalCooldown(0);
        position(player, level, pos);
        // Collision and PortalProcessor perform the dimension transfer on later ticks.
    }

    private static void position(ServerPlayer player, ServerLevel level, BlockPos pos) {
        require(player.level() == level, "fixture positioning never performs dimension transfer");
        require(player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5,
                Set.of(), 0, 0, true), "position player in fixture");
        player.setDeltaMovement(0, 0, 0);
    }

    private static void near(ServerPlayer player, BlockPos portal) {
        require(Math.abs(player.getX() - portal.getX()) < 3 && Math.abs(player.getZ() - portal.getZ()) < 3,
                "scaled travel selects the intended portal, not its unscaled decoy");
    }

    private static BlockPos emitter(int index) { return new BlockPos(31, 200, 32 + index * 8); }

    private static void lightFixtures(ServerLevel level, boolean create) {
        for (int i = 0; i < EMITTERS.size(); i++) {
            var pos = emitter(i);
            if (create) {
                for (var block : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(3, 1, 1)))
                    level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pos.east(), Blocks.GLASS.defaultBlockState(), 3);
                level.setBlock(pos.east(2), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(pos, (create ? EMITTERS.get(i) : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static boolean lightMatches(ServerLevel level, boolean emitting) {
        for (int i = 0; i < EMITTERS.size(); i++) {
            var pos = emitter(i);
            for (int distance = 0; distance < 3; distance++) {
                var sample = pos.east(distance);
                require(level.getBrightness(LightLayer.SKY, sample) == 0, "Nether runtime has no skylight");
                if (level.getBrightness(LightLayer.BLOCK, sample) != (emitting ? 15 - distance : 0)) return false;
            }
        }
        return true;
    }

    private static void event(String name, String context) {
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Nether lifecycle QA: " + message);
    }
}
