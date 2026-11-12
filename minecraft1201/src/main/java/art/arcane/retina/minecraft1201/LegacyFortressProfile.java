package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.BridgeCrossing;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.BridgeEndFiller;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.BridgeStraight;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleCorridorStairsPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleCorridorTBalconyPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleEntrance;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorCrossingPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorLeftTurnPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorRightTurnPiece;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleStalkRoom;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.MonsterThrone;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.RoomCrossing;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.StairsRoom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static art.arcane.retina.definition.FlatProfileModel.array;

/** Captures actual 1.20.1 geometry for the shared native fortress planner. */
final class LegacyFortressProfile {
    // Order matches Direction's horizontal data value, not Rotation or the 3D direction ordinal.
    private static final int CAPTURE_Y = 64;
    private static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    private static final List<String> KINDS = List.of("bridge_straight", "bridge_crossing", "room_crossing", "stairs_room",
            "monster_throne", "castle_entrance", "small_corridor", "corridor_crossing", "right_turn",
            "left_turn", "corridor_stairs", "corridor_balcony", "stalk_room");

    private LegacyFortressProfile() { }

    static JsonObject export(RegistryAccess registry, LinkedHashMap<BlockState, Integer> materials, JsonArray templates) {
        var result = new JsonObject();
        var pieces = new JsonArray();
        for (String kind : KINDS) {
            var piece = new JsonObject();
            piece.addProperty("kind", kind);
            var orientations = new JsonArray();
            for (Direction direction : DIRECTIONS) {
                var variants = new JsonArray();
                for (int variant = 0; variant < (hasChest(kind) ? 2 : 1); variant++) {
                    var source = piece(kind, direction, variant != 0);
                    var box = source.getBoundingBox();
                    // Only the first support block is captured; the native engine fills below it against terrain.
                    var capture = new LegacyStructureCapture(registry, box.minY() - 1, Blocks.NETHERRACK.defaultBlockState());
                    source.postProcess(capture, null, null, capture.getRandom(), BoundingBox.infinite(),
                            new ChunkPos(0, 0), BlockPos.ZERO);
                    var data = new JsonObject();
                    data.add("bounds", array(box.minX(), box.minY() - CAPTURE_Y, box.minZ(),
                            box.maxX(), box.maxY() - CAPTURE_Y, box.maxZ()));
                    var supports = new JsonArray();
                    var geometry = new LinkedHashMap<BlockPos, BlockState>();
                    capture.blocks().forEach((pos, state) -> {
                        var local = pos.offset(-box.minX(), -box.minY(), -box.minZ());
                        if (local.getY() == -1) {
                            supports.add(array(local.getX(), -1, local.getZ(),
                                    LegacyTerrainProfile.material(materials, state)));
                        } else {
                            if (local.getY() < 0) throw new IllegalStateException("Fortress piece writes below its support layer: " + kind);
                            geometry.put(local, state);
                        }
                    });
                    var raw = rawTemplate(geometry, capture, box);
                    String id = "retina:captured/fortress/" + kind + "/" + direction.getName() + "/" + variant;
                    data.addProperty("template", addTemplate(templates, id, raw, materials));
                    data.add("supports", supports);
                    var ticks = new JsonArray();
                    for (var tick : capture.ticks()) {
                        var t = new JsonObject();
                        t.add("pos", position(tick.pos().offset(-box.minX(), -box.minY(), -box.minZ())));
                        t.addProperty("fluid", tick.fluid());
                        t.addProperty("delay", tick.delay());
                        t.addProperty("priority", tick.priority());
                        ticks.add(t);
                    }
                    data.add("ticks", ticks);
                    var processing = new JsonArray();
                    capture.postProcessing().forEach(pos -> processing.add(position(pos.offset(-box.minX(), -box.minY(), -box.minZ()))));
                    data.add("post_processing", processing);
                    variants.add(data);
                }
                orientations.add(variants);
            }
            piece.add("orientations", orientations);
            pieces.add(piece);
        }
        result.add("pieces", pieces);
        // End fillers own a seed and draw one random inclusive length per row.
        // Export the recipe, not a single frozen capture shared by every fortress.
        var end = new JsonObject();
        var rows = new JsonArray();
        for (int x = 0; x <= 4; x++) for (int y = 3; y <= 4; y++) rows.add(array(x, y, 8));
        rows.add(array(0, 5, 8));
        rows.add(array(4, 5, 8));
        for (int x = 0; x <= 4; x++) rows.add(array(x, 2, 5));
        for (int x = 0; x <= 4; x++) for (int y = 0; y <= 1; y++) rows.add(array(x, y, 3));
        end.add("rows", rows);
        var bounds = new JsonArray();
        var indices = new JsonArray();
        for (Direction direction : DIRECTIONS) {
            var b = piece("end_filler", direction, false).getBoundingBox();
            bounds.add(array(b.minX(), b.minY() - CAPTURE_Y, b.minZ(), b.maxX(), b.maxY() - CAPTURE_Y, b.maxZ()));
            var geometry = new LinkedHashMap<BlockPos, BlockState>();
            for (var value : rows) {
                var row = value.getAsJsonArray();
                int x = row.get(0).getAsInt(), y = row.get(1).getAsInt();
                for (int z = 0; z < row.get(2).getAsInt(); z++) {
                    int xx = switch (direction) { case WEST -> b.getXSpan() - 1 - z; case EAST -> z; default -> x; };
                    int zz = switch (direction) { case WEST, EAST -> x; case NORTH -> b.getZSpan() - 1 - z; default -> z; };
                    geometry.put(new BlockPos(xx, y, zz), Blocks.NETHER_BRICKS.defaultBlockState());
                }
            }
            indices.add(addTemplate(templates, "retina:captured/fortress/end_filler/" + direction.getName(),
                    rawTemplate(geometry, null, b), materials));
        }
        end.add("bounds", bounds);
        end.add("templates", indices);
        result.add("end_filler", end);
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

    private static boolean hasChest(String kind) { return kind.equals("left_turn") || kind.equals("right_turn"); }

    /** Use the loaded piece factories for collision bounds, orientation and source geometry. */
    private static StructurePiece piece(String kind, Direction direction, boolean chest) {
        var builder = new StructurePiecesBuilder();
        var random = RandomSource.create(0);
        StructurePiece piece = switch (kind) {
            case "bridge_straight" -> BridgeStraight.createPiece(builder, random, 0, CAPTURE_Y, 0, direction, 0);
            case "bridge_crossing" -> BridgeCrossing.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "room_crossing" -> RoomCrossing.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "stairs_room" -> StairsRoom.createPiece(builder, 0, CAPTURE_Y, 0, 0, direction);
            case "monster_throne" -> MonsterThrone.createPiece(builder, 0, CAPTURE_Y, 0, 0, direction);
            case "castle_entrance" -> CastleEntrance.createPiece(builder, random, 0, CAPTURE_Y, 0, direction, 0);
            case "small_corridor" -> CastleSmallCorridorPiece.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "corridor_crossing" -> CastleSmallCorridorCrossingPiece.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "right_turn" -> CastleSmallCorridorRightTurnPiece.createPiece(builder, random, 0, CAPTURE_Y, 0, direction, 0);
            case "left_turn" -> CastleSmallCorridorLeftTurnPiece.createPiece(builder, random, 0, CAPTURE_Y, 0, direction, 0);
            case "corridor_stairs" -> CastleCorridorStairsPiece.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "corridor_balcony" -> CastleCorridorTBalconyPiece.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "stalk_room" -> CastleStalkRoom.createPiece(builder, 0, CAPTURE_Y, 0, direction, 0);
            case "end_filler" -> BridgeEndFiller.createPiece(builder, random, 0, CAPTURE_Y, 0, direction, 0);
            default -> throw new IllegalArgumentException("Fortress piece " + kind);
        };
        Objects.requireNonNull(piece, "Fortress factory " + kind);
        if (hasChest(kind)) {
            var tag = piece.createTag(null);
            tag.putBoolean("Chest", chest);
            piece = kind.equals("left_turn") ? new CastleSmallCorridorLeftTurnPiece(tag) : new CastleSmallCorridorRightTurnPiece(tag);
        }
        return piece;
    }

    static CompoundTag rawTemplate(Map<BlockPos, BlockState> geometry, LegacyStructureCapture capture,
                                           BoundingBox bounds) {
        var raw = new CompoundTag();
        var palette = new LinkedHashMap<BlockState, Integer>();
        var blocks = new ListTag();
        int width = bounds.getXSpan(), height = bounds.getYSpan(), depth = bounds.getZSpan();
        for (var entry : geometry.entrySet()) {
            var pos = entry.getKey();
            var b = new CompoundTag();
            width = Math.max(width, pos.getX() + 1);
            height = Math.max(height, pos.getY() + 1);
            depth = Math.max(depth, pos.getZ() + 1);
            b.put("pos", list(pos.getX(), pos.getY(), pos.getZ()));
            b.putInt("state", palette.computeIfAbsent(entry.getValue(), s -> palette.size()));
            var entity = capture == null ? null : capture.getBlockEntity(pos.offset(bounds.minX(), bounds.minY(), bounds.minZ()));
            if (entity != null) b.put("nbt", entity.saveWithFullMetadata());
            blocks.add(b);
        }
        var states = new ListTag();
        palette.keySet().forEach(state -> states.add(LegacyDensityCompiler.decode(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state))));
        raw.put("size", list(width, height, depth));
        raw.put("palette", states);
        raw.put("blocks", blocks);
        return raw;
    }

    static int addTemplate(JsonArray templates, String id, CompoundTag raw, LinkedHashMap<BlockState, Integer> materials) {
        int index = templates.size();
        templates.add(LegacyTemplateNbt.template(id, raw, 0, materials, new JsonArray()));
        return index;
    }

    private static JsonArray position(BlockPos pos) {
        return array(pos.getX(), pos.getY(), pos.getZ());
    }

    private static ListTag list(int... values) {
        var tag = new ListTag();
        for (int value : values) tag.add(IntTag.valueOf(value));
        return tag;
    }
}
