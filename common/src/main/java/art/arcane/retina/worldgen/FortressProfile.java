package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.*;
import net.minecraft.world.level.material.Fluid;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;

/** Startup-only capture of fortress pieces; native planning chooses pieces and places supports. */
final class FortressProfile {
    // Order matches Direction's horizontal data value, not Rotation or the 3D direction ordinal.
    static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    static final List<String> KINDS = List.of("bridge_straight", "bridge_crossing", "room_crossing", "stairs_room",
            "monster_throne", "castle_entrance", "small_corridor", "corridor_crossing", "right_turn",
            "left_turn", "corridor_stairs", "corridor_balcony", "stalk_room");

    static JsonObject export(StructureProfile profile) {
        var result = new JsonObject(); var pieces = new JsonArray(); var capture = new Capture(profile.registry);
        for (String kind : KINDS) {
            var piece = new JsonObject(); piece.addProperty("kind", kind); var orientations = new JsonArray();
            for (Direction direction : DIRECTIONS) {
                var variants = new JsonArray();
                for (int variant = 0; variant < (hasChest(kind) ? 2 : 1); variant++) {
                    var source = piece(kind, direction, variant != 0, 0);
                    var box = source.getBoundingBox();
                    capture.run(source, box.minY() - 2, 1945L);
                    var data = new JsonObject();
                    data.add("bounds", StructureProfile.ints(box.minX(), box.minY() - 64, box.minZ(),
                            box.maxX(), box.maxY() - 64, box.maxZ()));
                    var supports = new JsonArray();
                    var geometry = new LinkedHashMap<BlockPos, BlockState>();
                    capture.blocks.forEach((pos, state) -> {
                        var local = pos.offset(-box.minX(), -box.minY(), -box.minZ());
                        if (local.getY() == -1) {
                            supports.add(StructureProfile.ints(local.getX(), -1, local.getZ(),
                                    profile.materials.computeIfAbsent(state, s -> profile.materials.size())));
                        } else {
                            if (local.getY() < 0) throw new IllegalStateException("Fortress piece writes below its support layer: " + kind);
                            geometry.put(local, state);
                        }
                    });
                    var raw = rawTemplate(geometry, capture.entities, box, profile.registry);
                    String id = "retina:captured/fortress/" + kind + "/" + direction.getName() + "/" + variant;
                    data.addProperty("template", profile.template(id, raw, 0));
                    data.add("supports", supports);
                    var ticks = new JsonArray();
                    for (var tick : capture.ticks) {
                        var t = new JsonObject();
                        t.add("pos", position(tick.pos.offset(-box.minX(), -box.minY(), -box.minZ())));
                        t.addProperty("fluid", BuiltInRegistries.FLUID.getKey(tick.fluid).toString());
                        t.addProperty("delay", tick.delay); t.addProperty("priority", tick.priority); ticks.add(t);
                    }
                    data.add("ticks", ticks);
                    var processing = new JsonArray();
                    capture.postProcessing.forEach(pos -> processing.add(position(pos.offset(-box.minX(), -box.minY(), -box.minZ()))));
                    data.add("post_processing", processing); variants.add(data);
                }
                orientations.add(variants);
            }
            piece.add("orientations", orientations); pieces.add(piece);
        }
        result.add("pieces", pieces);
        // End fillers own a seed and draw one random inclusive length per row.
        // Export the recipe, not a single frozen capture shared by every fortress.
        var end = new JsonObject(); var rows = new JsonArray();
        for (int x = 0; x <= 4; x++) for (int y = 3; y <= 4; y++) rows.add(StructureProfile.ints(x, y, 8));
        rows.add(StructureProfile.ints(0, 5, 8)); rows.add(StructureProfile.ints(4, 5, 8));
        for (int x = 0; x <= 4; x++) rows.add(StructureProfile.ints(x, 2, 5));
        for (int x = 0; x <= 4; x++) for (int y = 0; y <= 1; y++) rows.add(StructureProfile.ints(x, y, 3));
        end.add("rows", rows); end.add("material", profile.rotations(Blocks.NETHER_BRICKS.defaultBlockState()));
        var bounds = new JsonArray();
        for (Direction direction : DIRECTIONS) {
            var b = piece("end_filler", direction, false, 0).getBoundingBox();
            bounds.add(StructureProfile.ints(b.minX(), b.minY() - 64, b.minZ(), b.maxX(), b.maxY() - 64, b.maxZ()));
        }
        end.add("bounds", bounds); result.add("end_filler", end);
        return result;
    }

    static void finish(JsonObject result, LinkedHashMap<BlockState, Integer> materials) {
        var replaceable = new JsonArray();
        materials.forEach((state, id) -> {
            if (state.isAir() || state.liquid() || state.is(Blocks.GLOW_LICHEN)
                    || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)) replaceable.add(id);
        });
        result.add("support_replaceable", replaceable);
    }

    static boolean hasChest(String kind) { return kind.equals("left_turn") || kind.equals("right_turn"); }

    /** Use the loaded piece factories for collision bounds, orientation and source geometry. */
    static StructurePiece piece(String kind, Direction direction, boolean chest, int seed) {
        var builder = new StructurePiecesBuilder(); var random = RandomSource.create(seed);
        StructurePiece piece = switch (kind) {
            case "bridge_straight" -> BridgeStraight.createPiece(builder, random, 0, 64, 0, direction, 0);
            case "bridge_crossing" -> BridgeCrossing.createPiece(builder, 0, 64, 0, direction, 0);
            case "room_crossing" -> RoomCrossing.createPiece(builder, 0, 64, 0, direction, 0);
            case "stairs_room" -> StairsRoom.createPiece(builder, 0, 64, 0, 0, direction);
            case "monster_throne" -> MonsterThrone.createPiece(builder, 0, 64, 0, 0, direction);
            case "castle_entrance" -> CastleEntrance.createPiece(builder, random, 0, 64, 0, direction, 0);
            case "small_corridor" -> CastleSmallCorridorPiece.createPiece(builder, 0, 64, 0, direction, 0);
            case "corridor_crossing" -> CastleSmallCorridorCrossingPiece.createPiece(builder, 0, 64, 0, direction, 0);
            case "right_turn" -> CastleSmallCorridorRightTurnPiece.createPiece(builder, random, 0, 64, 0, direction, 0);
            case "left_turn" -> CastleSmallCorridorLeftTurnPiece.createPiece(builder, random, 0, 64, 0, direction, 0);
            case "corridor_stairs" -> CastleCorridorStairsPiece.createPiece(builder, 0, 64, 0, direction, 0);
            case "corridor_balcony" -> CastleCorridorTBalconyPiece.createPiece(builder, 0, 64, 0, direction, 0);
            case "stalk_room" -> CastleStalkRoom.createPiece(builder, 0, 64, 0, direction, 0);
            case "end_filler" -> BridgeEndFiller.createPiece(builder, random, 0, 64, 0, direction, 0);
            default -> throw new IllegalArgumentException("Fortress piece " + kind);
        };
        Objects.requireNonNull(piece, "Fortress factory " + kind);
        if (hasChest(kind)) {
            var tag = piece.createTag(null); tag.putBoolean("Chest", chest);
            piece = kind.equals("left_turn") ? new CastleSmallCorridorLeftTurnPiece(tag) : new CastleSmallCorridorRightTurnPiece(tag);
        } else if (kind.equals("end_filler")) {
            var tag = piece.createTag(null); tag.putInt("Seed", seed); piece = new BridgeEndFiller(tag);
        }
        return piece;
    }

    static CompoundTag rawTemplate(Map<BlockPos, BlockState> geometry, Map<BlockPos, BlockEntity> entities,
                                   BoundingBox bounds, HolderLookup.Provider registry) {
        var raw = new CompoundTag(); var palette = new LinkedHashMap<BlockState, Integer>(); var blocks = new ListTag();
        int width = bounds.getXSpan(), height = bounds.getYSpan(), depth = bounds.getZSpan();
        for (var entry : geometry.entrySet()) {
            var pos = entry.getKey(); var b = new CompoundTag();
            width = Math.max(width, pos.getX() + 1); height = Math.max(height, pos.getY() + 1); depth = Math.max(depth, pos.getZ() + 1);
            b.put("pos", StructureProfile.list(pos.getX(), pos.getY(), pos.getZ()));
            b.putInt("state", palette.computeIfAbsent(entry.getValue(), s -> palette.size()));
            var entity = entities.get(pos.offset(bounds.minX(), bounds.minY(), bounds.minZ()));
            if (entity != null) b.put("nbt", entity.saveWithFullMetadata(registry));
            blocks.add(b);
        }
        var states = new ListTag();
        palette.keySet().forEach(state -> states.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()));
        raw.put("size", StructureProfile.list(width, height, depth)); raw.put("palette", states);
        raw.put("blocks", blocks); raw.put("entities", new ListTag()); return raw;
    }

    static JsonArray position(BlockPos pos) { return StructureProfile.ints(pos.getX(), pos.getY(), pos.getZ()); }
    record Tick(BlockPos pos, Fluid fluid, int delay, int priority) {}

    /** Real postProcess calls against a recording level. The floor stops supports after one layer. */
    static final class Capture {
        final LinkedHashMap<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Map<BlockPos, BlockEntity> entities = new HashMap<>();
        final LinkedHashSet<Tick> ticks = new LinkedHashSet<>();
        final LinkedHashSet<BlockPos> postProcessing = new LinkedHashSet<>();
        final Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
        final WorldGenLevel level;
        final LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        RandomSource random;
        int floor;

        Capture(HolderLookup.Provider registry) {
            var biomeIds = new IdMapper<Holder<Biome>>(); registry.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeIds::add);
            var bs = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY); var bio = Strategy.createForBiomes(biomeIds);
            var air = Blocks.AIR.defaultBlockState(); var biome = registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
            var factory = new PalettedContainerFactory(bs, air, PalettedContainer.codecRW(BlockState.CODEC, bs, air), bio, biome, PalettedContainer.codecRO(Biome.CODEC, bio, biome));
            level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getMinY": return height.getMinY();
                    case "getMaxY": return height.getMaxY();
                    case "getRandom": return random;
                    case "registryAccess": return registry;
                    case "getBlockState": return state((BlockPos) args[0]);
                    case "getFluidState": return state((BlockPos) args[0]).getFluidState();
                    case "setBlock": {
                        var pos = ((BlockPos) args[0]).immutable(); var state = (BlockState) args[1]; blocks.put(pos, state);
                        if (state.getBlock() instanceof EntityBlock block) {
                            var entity = block.newBlockEntity(pos, state); if (entity != null) entities.put(pos, entity);
                        } else entities.remove(pos);
                        return true;
                    }
                    case "getBlockEntity": return entities.get((BlockPos) args[0]);
                    case "getChunk": {
                        var pos = args[0] instanceof BlockPos bp ? ChunkPos.containing(bp) : new ChunkPos((int) args[0], (int) args[1]);
                        return chunks.computeIfAbsent(pos, p -> new ProtoChunk(p, UpgradeData.EMPTY, height, factory, null) {
                            @Override public void markPosForPostProcessing(BlockPos at) { Capture.this.postProcessing.add(at.immutable()); }
                        });
                    }
                    case "scheduleTick": {
                        int priority = args.length > 3 ? ((net.minecraft.world.ticks.TickPriority) args[3]).getValue() : 0;
                        ticks.add(new Tick(((BlockPos) args[0]).immutable(), (Fluid) args[1], (int) args[2], priority)); return null;
                    }
                    case "isClientSide": return false;
                    default:
                        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                        throw new UnsupportedOperationException("Fortress capture: " + method);
                }
            });
        }
        BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, pos.getY() <= floor ? Blocks.NETHERRACK.defaultBlockState() : Blocks.AIR.defaultBlockState()); }
        void run(StructurePiece piece, int floor, long seed) {
            this.floor = floor; random = RandomSource.create(seed);
            blocks.clear(); entities.clear(); ticks.clear(); chunks.clear(); postProcessing.clear();
            piece.postProcess(level, null, null, random, BoundingBox.infinite(), new ChunkPos(0, 0), BlockPos.ZERO);
        }
    }
}
