package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Opt-in checks using a connected survival player and unmodified native fortress blocks. */
final class NetherProgressionQa {
    private static final boolean ENABLED = Boolean.getBoolean("retina.qa.nether.progression");
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final BlockPos ORIGIN = new BlockPos(256, 100, 256);
    private static final BlockPos RETURN = new BlockPos(128, 100, 128);

    private NetherProgressionQa() {}

    private static final class Session {
        Run run;
        int completed;
    }

    private static final class Run {
        final ServerPlayer player;
        final long began = System.nanoTime();
        final Set<UUID> observed = new HashSet<>();
        int phase, since, rodsBefore, kills;
        boolean spawnMobs;
        CompoundTag saved, expected;
        BlockPos located, chest, spawner, standing;
        StructureStart start;
        CompletableFuture<Void> preparation;
        CompletableFuture<BlockPos> locate;
        Blaze target;
        Run(ServerPlayer player) { this.player = player; }
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
                require(session.run.phase == 8, "player disconnected before progression completed");
                session.run = null;
            }
            if (server.isDedicatedServer() && session.completed >= 2) server.halt(false);
            return;
        }
        require(players.size() == 1, "one connected QA player required");
        var player = players.getFirst();
        if (player.tickCount < 20) return;
        if (session.run == null) session.run = new Run(player);
        var run = session.run;
        require(run.player == player, "connection changed during progression");
        if (run.phase == 8) return;
        require(System.nanoTime() - run.began < 480_000_000_000L,
                "progression deadline in phase " + run.phase);
        var level = server.getLevel(Level.NETHER);
        require(level != null && level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator,
                "Nether automatically uses Retina");
        var generator = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var path = server.getWorldPath(LevelResource.ROOT).resolve("retina-qa-nether-progression.nbt");
        switch (run.phase) {
            case 0 -> {
                AutomaticGenerationQa.check(server);
                run.saved = Files.exists(path) ? NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()) : null;
                if (run.saved != null) require(run.saved.getLongOr("seed", 0) == level.getSeed(), "saved QA seed");
                run.spawnMobs = server.getGameRules().get(GameRules.SPAWN_MOBS);
                // Isolate the generated spawner from natural spawning; leave the spawner itself untouched.
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                player.setGameMode(GameType.SPECTATOR);
                run.preparation = CompletableFuture.allOf(
                        PortalRegionPreparation.prepareArea(level, ORIGIN, 96),
                        PortalRegionPreparation.prepareArea(server.overworld(), RETURN, 96));
                run.phase = 1;
            }
            case 1 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                require(player.level() == server.overworld(), "progression fixture begins in the Overworld");
                if (!TerrainQa.completed(server.overworld())) return;
                position(player, level, ORIGIN);
                run.locate = InteractiveLocateQa.locate(player, "minecraft:fortress");
                run.since = server.getTickCount();
                run.phase = 2;
            }
            case 2 -> {
                if (!run.locate.isDone()) return;
                run.located = run.locate.join();
                if (run.saved != null) require(run.located.equals(pos(run.saved, "located")), "saved fortress locate result");
                event("nether_live_locate", "\"x\":" + run.located.getX() + ",\"z\":" + run.located.getZ()
                        + ",\"server_ticks\":" + (server.getTickCount() - run.since) + ",\"reopened\":" + (run.saved != null));
                run.preparation = PortalRegionPreparation.prepareArea(level, run.located, 32);
                run.phase = 3;
            }
            case 3 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.FORTRESS);
                var chunk = level.getChunkAt(run.located);
                run.start = chunk.getAllStarts().get(structure);
                require(run.start != null && run.start.isValid(), "located fortress has a live registered start");
                var box = run.start.getBoundingBox();
                run.preparation = PortalRegionPreparation.prepareArea(level, box.getCenter(),
                        Math.max(box.getXSpan(), box.getZSpan()) / 2 + 96);
                run.phase = 4;
            }
            case 4 -> {
                if (!run.preparation.isDone()) return;
                run.preparation.join();
                inspectFortress(level, run);
                run.standing = standing(level, run.spawner, 8);
                position(player, level, standing(level, run.chest, 3));
                player.setGameMode(GameType.SURVIVAL);
                player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 12000));
                player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 12000, 4));
                var chest = (RandomizableContainerBlockEntity) level.getBlockEntity(run.chest);
                require(chest != null, "live chest block entity");
                if (run.saved == null) {
                    require(chest.getLootTable() != null && chest.getLootTable().identifier().toString()
                            .equals("minecraft:chests/nether_bridge"), "unopened native fortress loot table");
                } else {
                    require(chest.getLootTable() == null && chest.saveWithFullMetadata(level.registryAccess())
                            .getListOrEmpty("Items").equals(run.saved.getListOrEmpty("chest_items")),
                            "opened chest contents and player edit survive reopening");
                }
                player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(run.chest), Direction.UP, run.chest, false));
                require(player.containerMenu != player.inventoryMenu && chest.getLootTable() == null,
                        "ordinary survival block interaction opens and resolves generated chest");
                int stacks = 0;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) if (!chest.getItem(slot).isEmpty()) stacks++;
                require(stacks > 0, "fortress loot is nonempty");
                player.closeContainer();
                chest.setItem(0, new ItemStack(Items.DIAMOND));
                chest.setChanged();
                run.expected.put("chest_items", chest.saveWithFullMetadata(level.registryAccess()).getListOrEmpty("Items").copy());
                event("nether_live_fortress_loot", "\"stacks\":" + stacks + ",\"reopened\":" + (run.saved != null));
                checkSpawning(level, generator, run);
                // A reopened fixture can contain survivors from its previous spawner cycle.
                for (var blaze : level.getEntitiesOfClass(Blaze.class, new AABB(run.spawner).inflate(24))) blaze.discard();
                for (var drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(run.spawner).inflate(24),
                        item -> item.getItem().is(Items.BLAZE_ROD))) drop.discard();
                position(player, level, run.standing);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                run.rodsBefore = rods(player);
                run.since = server.getTickCount();
                run.phase = 5;
            }
            case 5 -> {
                require(server.getTickCount() - run.since < 2400, "generated blaze spawner produces collectible progression loot");
                require(player.isAlive() && !player.isCreative() && !player.isSpectator(), "connected survival player remains alive");
                var nearby = new AABB(run.spawner).inflate(24);
                var blazes = level.getEntitiesOfClass(Blaze.class, nearby);
                for (var blaze : blazes) if (run.observed.add(blaze.getUUID()))
                    event("nether_live_blaze_spawn", "\"uuid\":\"" + blaze.getUUID() + "\",\"spawner_untouched\":true");
                var drops = level.getEntitiesOfClass(ItemEntity.class, nearby,
                        item -> item.getItem().is(Items.BLAZE_ROD));
                if (!drops.isEmpty()) {
                    var drop = drops.getFirst();
                    player.teleportTo(level, drop.getX(), drop.getY(), drop.getZ(), Set.of(), 0, 0, true);
                } else if (run.target != null && run.target.isAlive()) {
                    if (server.getTickCount() % 20 == 0) {
                        player.teleportTo(level, run.target.getX() + 1.5, run.target.getY(), run.target.getZ(), Set.of(), 90, 0, true);
                        player.attack(run.target);
                        if (!run.target.isAlive()) run.kills++;
                    }
                } else {
                    run.target = blazes.stream().filter(Blaze::isAlive).findFirst().orElse(null);
                    if (run.target == null) position(player, level, run.standing);
                }
                if (rods(player) <= run.rodsBefore) return;
                require(!run.observed.isEmpty() && run.kills > 0, "spawned blaze killed by ordinary player attack before pickup");
                event("nether_live_blaze_progression", "\"spawned\":" + run.observed.size() + ",\"kills\":" + run.kills
                        + ",\"rods_collected\":" + (rods(player) - run.rodsBefore) + ",\"survival\":true");
                NbtIo.writeCompressed(run.expected, path);
                server.getGameRules().set(GameRules.SPAWN_MOBS, run.spawnMobs, server);
                player.removeEffect(MobEffects.FIRE_RESISTANCE);
                player.removeEffect(MobEffects.RESISTANCE);
                player.setGameMode(GameType.CREATIVE);
                // Recheck the actual command after loaded chunks and loot have changed.
                position(player, level, ORIGIN);
                run.locate = InteractiveLocateQa.locate(player, "minecraft:fortress");
                run.phase = 6;
            }
            case 6 -> {
                if (!run.locate.isDone()) return;
                require(run.located.equals(run.locate.join()), "locate remains stable after live generation and progression");
                run.since = server.getTickCount();
                run.phase = 7;
            }
            case 7 -> {
                if (server.getTickCount() - run.since < 40
                        || !TerrainQa.completed(level) || !TerrainQa.completed(server.overworld())) return;
                position(player, server.overworld(), RETURN);
                run.phase = 8;
                session.completed++;
                event("nether_progression_complete", "\"dedicated\":" + server.isDedicatedServer()
                        + ",\"connection\":" + session.completed + ",\"reopened\":" + (run.saved != null));
            }
            default -> throw new IllegalStateException("Unknown progression phase " + run.phase);
        }
    }

    private static void inspectFortress(ServerLevel level, Run run) {
        var start = run.start;
        var box = start.getBoundingBox();
        var context = StructurePieceSerializationContext.fromLevel(level);
        var tag = start.createTag(context, start.getChunkPos());
        if (run.saved != null) require(tag.getListOrEmpty("Children").equals(run.saved.getListOrEmpty("pieces")),
                "saved piece bounds and geometry metadata remain intact");
        var chests = new ArrayList<BlockPos>();
        var spawners = new ArrayList<BlockPos>();
        var regions = new HashSet<Long>();
        int chunks = 0, pieces = 0;
        for (int z = Math.floorDiv(box.minZ(), 16); z <= Math.floorDiv(box.maxZ(), 16); z++)
            for (int x = Math.floorDiv(box.minX(), 16); x <= Math.floorDiv(box.maxX(), 16); x++) {
                var chunk = level.getChunk(x, z);
                require(chunk.getReferencesForStructure(start.getStructure()).contains(start.getChunkPos().pack()), "live fortress chunk reference");
                regions.add(ChunkPos.pack(Math.floorDiv(x, 32), Math.floorDiv(z, 32)));
                chunks++;
                // Full chunks outside the ticking area can still hold lazy block-entity NBT.
                for (var at : chunk.getBlockEntitiesPos()) if (box.isInside(at)) {
                    var entity = chunk.getBlockEntity(at);
                    if (entity instanceof RandomizableContainerBlockEntity) chests.add(at);
                    if (entity instanceof SpawnerBlockEntity spawner) {
                        var data = spawner.saveWithFullMetadata(level.registryAccess());
                        require(data.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "")
                                .equals("minecraft:blaze"), "generated spawner retains blaze identity");
                        spawners.add(at);
                    }
                }
            }
        for (var piece : start.getPieces()) {
            var at = piece.getBoundingBox().getCenter();
            require(level.structureManager().getStructureWithPieceAt(at.getX(), at.getY(), at.getZ(), start.getStructure()).isValid(),
                    "structure manager resolves every live piece");
            pieces++;
        }
        require(!chests.isEmpty() && !spawners.isEmpty() && chunks > 1,
                "fortress spans chunks and contains loot and spawners: chunks=" + chunks
                        + ", chests=" + chests.size() + ", spawners=" + spawners.size());
        chests.sort(Comparator.comparingLong(BlockPos::asLong));
        spawners.sort(Comparator.comparingLong(BlockPos::asLong));
        run.chest = run.saved == null ? chests.getFirst() : pos(run.saved, "chest");
        run.spawner = run.saved == null ? spawners.getFirst() : pos(run.saved, "spawner");
        require(chests.contains(run.chest) && spawners.contains(run.spawner), "saved gameplay fixtures remain present");
        run.expected = new CompoundTag();
        run.expected.putLong("seed", level.getSeed());
        run.expected.put("pieces", tag.getListOrEmpty("Children").copy());
        put(run.expected, "located", run.located);
        put(run.expected, "chest", run.chest);
        put(run.expected, "spawner", run.spawner);
        event("nether_live_fortress_metadata", "\"chunks\":" + chunks + ",\"regions\":" + regions.size()
                + ",\"pieces\":" + pieces + ",\"chests\":" + chests.size() + ",\"spawners\":" + spawners.size()
                + ",\"reopened\":" + (run.saved != null));
    }

    private static void checkSpawning(ServerLevel level, RetinaChunkGenerator generator, Run run) {
        var actual = generator.getMobsAt(level, level.structureManager(), MobCategory.MONSTER, run.standing);
        var expected = run.start.getStructure().spawnOverrides().get(MobCategory.MONSTER).spawns();
        require(actual.unwrap().equals(expected.unwrap()), "loaded fortress piece uses registered spawn override");
        require(actual.unwrap().stream().anyMatch(entry -> entry.value().type() == EntityTypes.BLAZE)
                && actual.unwrap().stream().anyMatch(entry -> entry.value().type() == EntityTypes.WITHER_SKELETON),
                "fortress override includes blaze and wither skeleton");
        require(NaturalSpawner.isInNetherFortressBounds(run.standing, level, MobCategory.MONSTER, level.structureManager()),
                "natural spawning recognizes generated Nether-brick fortress floor");
        require(!NaturalSpawner.isInNetherFortressBounds(run.standing, level, MobCategory.CREATURE, level.structureManager()),
                "fortress override does not affect passive mobs");
        require(NetherFortressStructure.FORTRESS_ENEMIES.unwrap().equals(expected.unwrap()), "vanilla fortress spawn weights");
        event("nether_live_fortress_spawn_rules", "\"entries\":" + actual.unwrap().size() + ",\"nether_brick_floor\":true");
        // Drive Minecraft's real natural-spawn placement routine on existing terrain.
        // The controlled attempts bypass the random chunk/height lottery and mob cap,
        // but retain its spawn table, player distance, light, collision and mob rules.
        position(run.player, level, run.standing.above(40));
        var types = new HashSet<net.minecraft.world.entity.EntityType<?>>();
        int attempts = 0;
        try {
            while ((!types.contains(EntityTypes.BLAZE) || !types.contains(EntityTypes.WITHER_SKELETON)) && attempts < 512) {
                attempts++;
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, level.getChunkAt(run.standing),
                        run.standing, (type, world, pos, chunk) -> true, (mob, chunk) -> {
                            if (level.structureManager().getStructureAt(mob.blockPosition(), run.start.getStructure()).isValid())
                                types.add(mob.getType());
                            // Keep these controlled natural-spawn attempts separate from the spawner check.
                            mob.discard();
                        });
            }
        } finally {
            position(run.player, level, run.standing);
        }
        require(types.contains(EntityTypes.BLAZE) && types.contains(EntityTypes.WITHER_SKELETON),
                "Minecraft natural spawning places blaze and wither skeleton on the generated fortress");
        event("nether_live_fortress_natural_spawns", "\"attempts\":" + attempts + ",\"blaze\":true,\"wither_skeleton\":true");
    }

    private static BlockPos standing(ServerLevel level, BlockPos center, int radius) {
        var choices = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius)))
            if (level.getBlockState(at.below()).is(Blocks.NETHER_BRICKS)
                    && level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()) choices.add(at.immutable());
        return choices.stream().min(Comparator.comparingDouble(center::distSqr))
                .orElseThrow(() -> new IllegalStateException("No accessible fortress floor near " + center));
    }

    private static int rods(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var item = player.getInventory().getItem(slot);
            if (item.is(Items.BLAZE_ROD)) count += item.getCount();
        }
        return count;
    }

    private static void position(ServerPlayer player, ServerLevel level, BlockPos pos) {
        player.teleportTo(level, pos.getX() + .5, pos.getY(), pos.getZ() + .5, Set.of(), 0, 0, true);
    }

    private static void put(CompoundTag tag, String name, BlockPos pos) {
        tag.put(name, BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, pos).getOrThrow());
    }

    private static BlockPos pos(CompoundTag tag, String name) {
        return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get(name)).getOrThrow();
    }

    private static void event(String name, String context) {
        Retina.LOGGER.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\",\"context\":{}}", name, "{" + context + "}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Nether progression QA: " + message);
    }
}
