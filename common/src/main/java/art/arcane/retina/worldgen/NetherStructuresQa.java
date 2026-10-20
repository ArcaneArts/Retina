package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Opt-in live checks on ordinary source-selected Nether structures and their saved contents. */
final class NetherStructuresQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.structures");
    private static final String[] STRUCTURES = {"bastion_remnant", "nether_fossil", "ruined_portal_nether"};
    private static final BlockPos ORIGIN = new BlockPos(256, 100, 256), RETURN = new BlockPos(128, 100, 128);
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();

    private static final class Session {
        Run run;
        int completed;
    }

    private static final class Run {
        final ServerPlayer player;
        final long began = System.nanoTime();
        final List<ChunkPos> tickets = new ArrayList<>();
        final Set<UUID> observed = new HashSet<>();
        int phase, index, since, portalSearches, entitiesReadySince;
        boolean spawnMobs;
        CompoundTag saved, expected, prior, fixture;
        ListTag entities;
        BlockPos located, locateOrigin = ORIGIN;
        StructureStart start;
        CompletableFuture<Void> preparation;
        CompletableFuture<BlockPos> locate;
        Run(ServerPlayer player) { this.player = player; }
        String id() { return "minecraft:" + STRUCTURES[index]; }
    }

    static void unload(ServerLevel level) {
        if (ENABLED && level.dimension().equals(Level.NETHER)) SESSIONS.remove(level.getServer());
    }

    static void tick(MinecraftServer server) {
        if (!ENABLED) return;
        try { run(server); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static void run(MinecraftServer server) throws IOException {
        var session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            if (session.run != null) {
                require(session.run.phase == 8, "player disconnected before structure validation completed");
                session.run = null;
            }
            if (server.isDedicatedServer() && session.completed >= 2) server.halt(false);
            return;
        }
        require(players.size() == 1, "one connected QA player");
        var player = players.getFirst();
        if (player.tickCount < 20) return;
        if (session.run == null) session.run = new Run(player);
        var run = session.run;
        require(run.player == player, "connection changed during structure validation");
        if (run.phase == 8) return;
        require(System.nanoTime() - run.began < 1_080_000_000_000L, "deadline in phase " + run.phase + "/" + run.index);
        var level = server.getLevel(Level.NETHER);
        require(level != null && level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "automatic Nether adaptation");
        var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var path = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-nether-structures.nbt");
        switch (run.phase) {
            case 0 -> {
                AutomaticGenerationQa.check(server);
                run.saved = Files.exists(path) ? NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()) : null;
                if (run.saved != null) require(run.saved.getLongOr("seed", 0) == level.getSeed(), "saved QA seed");
                run.expected = new CompoundTag();
                run.expected.putLong("seed", level.getSeed());
                run.spawnMobs = server.getGameRules().get(GameRules.SPAWN_MOBS);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                player.setGameMode(GameType.SPECTATOR);
                run.preparation = CompletableFuture.allOf(PortalRegionPreparation.prepareArea(level, ORIGIN, 96),
                        PortalRegionPreparation.prepareArea(server.overworld(), RETURN, 96));
                run.phase = 1;
            }
            case 1 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                if (!TerrainQa.completed(server.overworld())) return;
                position(player, level, run.locateOrigin);
                run.locate = InteractiveLocateQa.locate(player, run.id());
                run.phase = 2;
            }
            case 2 -> {
                if (!run.locate.isDone()) return;
                run.located = run.locate.join();
                run.prior = run.saved == null ? null : run.saved.getCompoundOrEmpty(run.id());
                if (run.prior != null) require(run.located.equals(pos(run.prior, "located")), "saved locate result for " + run.id());
                run.preparation = PortalRegionPreparation.prepareArea(level, run.located, 32);
                run.phase = 3;
            }
            case 3 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(run.id()));
                run.start = level.getChunkAt(run.located).getAllStarts().get(structure);
                require(run.start != null && run.start.isValid(), "live registered start for " + run.id());
                var box = run.start.getBoundingBox();
                run.preparation = PortalRegionPreparation.prepareArea(level, box.getCenter(),
                        Math.max(box.getXSpan(), box.getZSpan()) / 2 + 96);
                run.phase = 4;
            }
            case 4 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                if (!inspect(level, generator, run)) {
                    run.locateOrigin = ORIGIN.offset(++run.portalSearches * 1024, 0, 0);
                    run.preparation = PortalRegionPreparation.prepareArea(level, run.locateOrigin, 96);
                    run.phase = 1;
                    return;
                }
                run.since = server.getTickCount();
                run.phase = 5;
            }
            case 5 -> {
                if (!inspectEntities(level, run)) {
                    // Forced chunks can still be waiting for their ticking halo.
                    // Start the observation window when Minecraft reports that the
                    // actual owner chunks have loaded entities and entered ticking.
                    boolean ready = run.entities.stream().map(value -> (CompoundTag) value)
                            .map(tag -> ChunkPos.containing(BlockPos.containing(Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get("Pos")).getOrThrow())))
                            .distinct().allMatch(level::areEntitiesActuallyLoadedAndTicking);
                    if (!ready) run.entitiesReadySince = 0;
                    else if (run.entitiesReadySince == 0) run.entitiesReadySince = server.getTickCount();
                    else require(server.getTickCount() - run.entitiesReadySince < 100, "native template entities missing from ticking chunks: observed "
                            + run.observed.size() + "/" + run.entities.size() + ", missing=" + run.entities.stream()
                            .map(value -> (CompoundTag) value)
                            .filter(tag -> !run.observed.contains(UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("UUID")).getOrThrow()))
                            .map(tag -> tag.getStringOr("id", "") + " at " + tag.get("Pos")).toList());
                    return;
                }
                run.fixture.put("entities", run.entities.copy());
                run.expected.put(run.id(), run.fixture);
                position(player, level, run.locateOrigin);
                run.locate = InteractiveLocateQa.locate(player, run.id());
                run.phase = 6;
            }
            case 6 -> {
                if (!run.locate.isDone()) return;
                require(run.located.equals(run.locate.join()), "locate stable after live loading and edits");
                for (var at : run.tickets) level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, at, 2);
                run.tickets.clear();
                run.observed.clear();
                run.index++;
                if (run.index < STRUCTURES.length) {
                    var prior = run.saved == null ? null : run.saved.getCompoundOrEmpty(run.id());
                    run.locateOrigin = prior != null && prior.contains("origin") ? pos(prior, "origin") : ORIGIN;
                    run.preparation = PortalRegionPreparation.prepareArea(level, run.locateOrigin, 96);
                    run.phase = 1;
                } else {
                    NbtIo.writeCompressed(run.expected, path);
                    server.getGameRules().set(GameRules.SPAWN_MOBS, run.spawnMobs, server);
                    run.phase = 7;
                    run.since = server.getTickCount();
                }
            }
            case 7 -> {
                if (server.getTickCount() - run.since < 40 || !TerrainQa.completed(level)) return;
                player.setGameMode(GameType.CREATIVE);
                position(player, server.overworld(), RETURN);
                run.phase = 8;
                session.completed++;
                event("nether_structures_complete", "\"dedicated\":" + server.isDedicatedServer()
                        + ",\"connection\":" + session.completed + ",\"reopened\":" + (run.saved != null));
            }
            default -> throw new IllegalStateException("Unknown structure QA phase " + run.phase);
        }
    }

    private static boolean inspect(ServerLevel level, RetinaChunkGenerator generator, Run run) {
        var start = run.start;
        var box = start.getBoundingBox();
        var tag = start.createTag(StructurePieceSerializationContext.fromLevel(level), start.getChunkPos());
        if (run.prior != null) require(tag.getListOrEmpty("Children").equals(run.prior.getListOrEmpty("pieces")), "persisted piece geometry");
        run.fixture = new CompoundTag();
        put(run.fixture, "located", run.located);
        put(run.fixture, "origin", run.locateOrigin);
        run.fixture.put("pieces", tag.getListOrEmpty("Children").copy());
        run.entities = run.prior == null ? new ListTag() : run.prior.getListOrEmpty("entities").copy();
        var chests = new ArrayList<BlockPos>();
        var nativeChests = new HashSet<BlockPos>();
        var regions = new HashSet<Long>();
        int chunks = 0, signatureBlocks = 0;
        for (int z = Math.floorDiv(box.minZ(), 16); z <= Math.floorDiv(box.maxZ(), 16); z++)
            for (int x = Math.floorDiv(box.minX(), 16); x <= Math.floorDiv(box.maxX(), 16); x++) {
                var at = new ChunkPos(x, z);
                var chunk = level.getChunk(x, z);
                require(chunk.getReferencesForStructure(start.getStructure()).contains(start.getChunkPos().pack()), "live chunk reference for " + run.id() + " at " + at + " start=" + start.getChunkPos() + " bounds=" + box);
                regions.add(ChunkPos.pack(at.getRegionX(), at.getRegionZ()));
                chunks++;
                for (var position : chunk.getBlockEntitiesPos()) if (box.isInside(position)
                        && chunk.getBlockEntity(position) instanceof RandomizableContainerBlockEntity) chests.add(position);
                for (var position : BlockPos.betweenClosed(Math.max(box.minX(), at.getMinBlockX()), box.minY(),
                        Math.max(box.minZ(), at.getMinBlockZ()), Math.min(box.maxX(), at.getMaxBlockX()), box.maxY(),
                        Math.min(box.maxZ(), at.getMaxBlockZ()))) {
                    var state = chunk.getBlockState(position);
                    if (run.index == 0 && (state.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || state.is(Blocks.BLACKSTONE))
                            || run.index == 1 && state.is(Blocks.BONE_BLOCK)
                            || run.index == 2 && (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN))) signatureBlocks++;
                }
                if (run.prior == null) {
                    var nativeData = generator.previewCache().read(at);
                    for (var value : nativeData.getListOrEmpty("block_entities")) {
                        var entity = (CompoundTag) value;
                        var position = new BlockPos(entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0));
                        if (box.isInside(position) && entity.getStringOr("id", "").equals("minecraft:chest")) nativeChests.add(position);
                    }
                    if (run.index == 0) for (var value : nativeData.getListOrEmpty("entities")) {
                        var entity = (CompoundTag) value;
                        var position = Vec3.CODEC.parse(NbtOps.INSTANCE, entity.get("Pos")).getOrThrow();
                        if (box.isInside(BlockPos.containing(position))) run.entities.add(entity.copy());
                    }
                }
                if (run.index == 0) {
                    level.getChunkSource().addTicketWithRadius(TicketType.FORCED, at, 2);
                    run.tickets.add(at);
                }
            }
        for (var piece : start.getPieces()) {
            var center = piece.getBoundingBox().getCenter();
            require(level.structureManager().getStructureWithPieceAt(center.getX(), center.getY(), center.getZ(), start.getStructure()).isValid(),
                    "structure manager resolves every live piece");
        }
        require(signatureBlocks > 0, "structure's actual generated blocks are present");
        if (run.index == 0) require(!run.entities.isEmpty(), "native bastion contains template mobs");
        if (run.prior == null) require(chests.containsAll(nativeChests), "every native chest loads as a live block entity");
        if (run.index != 1 && !(run.index == 2 && chests.isEmpty() && run.prior == null)) {
            require(!chests.isEmpty(), "generated structure has loot");
            chests.sort(Comparator.comparingLong(BlockPos::asLong));
            var at = run.prior == null ? chests.getFirst() : pos(run.prior, "chest");
            require(chests.contains(at), "saved chest remains present");
            checkLoot(level, run, at);
        }
        event("nether_live_structure_metadata", "\"structure\":\"" + run.id() + "\",\"chunks\":" + chunks
                + ",\"regions\":" + regions.size() + ",\"pieces\":" + start.getPieces().size()
                + ",\"blocks\":" + signatureBlocks + ",\"chests\":" + chests.size() + ",\"reopened\":" + (run.prior != null));
        if (run.index == 2 && chests.isEmpty()) {
            event("nether_portal_without_loot", "\"x\":" + run.located.getX() + ",\"z\":" + run.located.getZ());
            return false;
        }
        return true;
    }

    private static void checkLoot(ServerLevel level, Run run, BlockPos at) {
        var chest = (RandomizableContainerBlockEntity) level.getBlockEntity(at);
        if (run.prior == null) {
            require(chest.getLootTable() != null && chest.getLootTable().identifier().toString().startsWith(
                    run.index == 0 ? "minecraft:chests/bastion_" : "minecraft:chests/ruined_portal"), "source loot table");
        } else require(chest.getLootTable() == null && chest.saveWithFullMetadata(level.registryAccess())
                .getListOrEmpty("Items").equals(run.prior.getListOrEmpty("chest_items")), "saved opened loot and diamond edit");
        // Creative interaction resolves the real loot table. Underground ruined
        // portals can bury their chest; excavate the lid using ordinary block breaking.
        position(run.player, level, at.above());
        run.player.setGameMode(GameType.CREATIVE);
        run.player.gameMode.useItemOn(run.player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        boolean excavated = false;
        if (run.player.containerMenu == run.player.inventoryMenu) {
            var cover = at.above();
            require(level.getBlockState(cover).isRedstoneConductor(level, cover), "unopened chest has solid cover: " + at);
            require(run.player.gameMode.destroyBlock(cover), "player excavates generated chest cover");
            excavated = true;
            run.player.gameMode.useItemOn(run.player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
        }
        require(run.player.containerMenu != run.player.inventoryMenu && chest.getLootTable() == null, "normal interaction resolves generated loot");
        int stacks = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) if (!chest.getItem(slot).isEmpty()) stacks++;
        require(stacks > 0, "nonempty generated loot");
        run.player.closeContainer();
        run.player.setGameMode(GameType.SPECTATOR);
        chest.setItem(0, new ItemStack(Items.DIAMOND));
        chest.setChanged();
        put(run.fixture, "chest", at);
        run.fixture.put("chest_items", chest.saveWithFullMetadata(level.registryAccess()).getListOrEmpty("Items").copy());
        event("nether_live_structure_loot", "\"structure\":\"" + run.id() + "\",\"stacks\":" + stacks
                + ",\"cover_removed\":" + excavated + ",\"reopened\":" + (run.prior != null));
    }

    private static boolean inspectEntities(ServerLevel level, Run run) {
        var uuids = new HashSet<UUID>();
        int brutes = 0;
        for (var value : run.entities) {
            var tag = (CompoundTag) value;
            var uuid = UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("UUID")).getOrThrow();
            require(uuids.add(uuid), "native template UUIDs are distinct");
            if (tag.getStringOr("id", "").equals("minecraft:piglin_brute")) brutes++;
            if (run.observed.contains(uuid)) continue;
            var entity = level.getEntity(uuid);
            if (entity == null) continue;
            require(entity instanceof Mob, "template mob is loaded with its native UUID");
            var mob = (Mob) entity;
            require(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString().equals(tag.getStringOr("id", "")), "template mob type");
            require(mob.isPersistenceRequired(), "source mob persistence");
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            mob.saveWithoutId(output);
            var actual = output.buildResult();
            require(!actual.getBooleanOr(StructureMobInitialization.PENDING_TAG, false), "initialization marker is consumed before saving");
            for (String key : List.of("CannotHunt", "CannotBeHunted"))
                if (tag.getStringOr("id", "").equals(key.equals("CannotHunt") ? "minecraft:piglin" : "minecraft:hoglin"))
                    require(actual.getBooleanOr(key, false) == tag.getBooleanOr(key, false), "source " + key);
            String weapon = tag.getCompoundOrEmpty("equipment").getCompoundOrEmpty("mainhand").getStringOr("id", "");
            if (!weapon.isEmpty() && !(mob instanceof PiglinBrute))
                require(BuiltInRegistries.ITEM.getKey(mob.getMainHandItem().getItem()).toString().equals(weapon), "source template weapon");
            if (mob instanceof PiglinBrute brute) {
                var home = brute.getBrain().getMemory(MemoryModuleType.HOME);
                var expectedHome = run.prior == null ? BlockPos.containing(Vec3.CODEC.parse(NbtOps.INSTANCE, tag.get("Pos")).getOrThrow())
                        : pos(tag, "qa_home");
                require(brute.getMainHandItem().is(run.prior == null ? Items.GOLDEN_AXE : Items.DIAMOND_AXE)
                                && home.isPresent() && home.get().dimension().equals(Level.NETHER) && home.get().pos().equals(expectedHome),
                        "bastion brute finalized once: item=" + brute.getMainHandItem() + ", home=" + home + ", reopened=" + (run.prior != null));
                put(tag, "qa_home", expectedHome);
                // An intentional QA equipment edit must survive reloading without reinitialization.
                brute.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_AXE));
            }
            // Freeze only after checking original initialization; prevent combat/wandering
            // from obscuring persistence while the other structures are inspected.
            mob.setNoAi(true);
            mob.setPermanentlyInvulnerable(true);
            run.observed.add(uuid);
        }
        if (run.observed.size() != run.entities.size()) return false;
        if (run.index == 0) {
            require(brutes > 0, "live bastion includes a brute initialization check");
            long live = java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
                    .filter(entity -> uuids.contains(entity.getUUID())).count();
            require(live == uuids.size(), "one live entity per native template UUID");
            event("nether_live_bastion_entities", "\"entities\":" + live + ",\"brutes\":" + brutes + ",\"reopened\":" + (run.prior != null));
        }
        return true;
    }

    private static void position(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true);
    }
    private static void put(CompoundTag tag, String key, BlockPos pos) { tag.put(key, BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow()); }
    private static BlockPos pos(CompoundTag tag, String key) { return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get(key)).getOrThrow(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException("Nether structures QA: " + message); }
    private static void event(String name, String context) {
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }
}
