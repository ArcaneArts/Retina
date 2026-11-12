package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records piece geometry from the loaded 1.20.1 game. */
final class LegacyStrongholdProfile {
    private record Shape(String name, int width, int height, int depth, int variants, String... exits) {
        int height(int variant) { return name.equals("library") && variant == 1 ? 11 : height; }
        int depth(int variant) { return name.equals("filler") ? depth + variant : depth; }
        boolean doors() { return !name.equals("start") && !name.equals("portal") && !name.equals("filler"); }
    }

    private static final List<Shape> SHAPES = List.of(
            new Shape("straight", 5, 5, 7, 1, "Left", "Right"),
            new Shape("prison", 9, 5, 11, 1),
            new Shape("left", 5, 5, 5, 1),
            new Shape("right", 5, 5, 5, 1),
            new Shape("room", 11, 7, 11, 5),
            new Shape("straight_stairs", 5, 11, 8, 1),
            new Shape("stairs", 5, 11, 5, 1),
            new Shape("crossing", 10, 9, 11, 1, "leftLow", "leftHigh", "rightLow", "rightHigh"),
            new Shape("chest", 5, 5, 7, 1),
            new Shape("library", 14, 6, 15, 2),
            new Shape("portal", 11, 8, 16, 1),
            new Shape("start", 5, 11, 5, 1),
            new Shape("filler", 5, 5, 2, 2));
    private static final Direction[] DIRECTIONS = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
    private static final List<String> DOORS = List.of("OPENING", "WOOD_DOOR", "GRATES", "IRON_DOOR");

    private LegacyStrongholdProfile() { }

    static JsonObject export(RegistryAccess registry, LinkedHashMap<BlockState, Integer> materials, JsonArray templates,
                             int generatorMinY) {
        var pieces = new JsonObject();
        for (var shape : SHAPES) {
            var geometry = new JsonObject();
            var orientations = new JsonArray();
            for (var direction : DIRECTIONS) {
                var row = new JsonObject();
                var base = new JsonArray();
                var doors = new JsonArray();
                var exits = new JsonArray();
                var source = piece(shape, direction, 0, 0, 0);
                geometry.addProperty("id", source.createTag(null).getString("id"));
                var first = capture(registry, source);
                for (int variant = 0; variant < shape.variants(); variant++) {
                    var variantPiece = variant == 0 ? source : piece(shape, direction, variant, 0, 0);
                    var capture = variant == 0 ? first : capture(registry, variantPiece);
                    base.add(add(templates, materials, shape, direction, "base_" + variant, variantPiece, capture, Map.of()));
                }
                if (shape.doors()) {
                    for (int variant = 1; variant < DOORS.size(); variant++) {
                        var variantPiece = piece(shape, direction, 0, variant, 0);
                        doors.add(add(templates, materials, shape, direction, "door_" + variant, variantPiece,
                                capture(registry, variantPiece), first.blocks()));
                    }
                }
                for (int bit = 0; bit < shape.exits().length; bit++) {
                    var variantPiece = piece(shape, direction, 0, 0, 1 << bit);
                    exits.add(add(templates, materials, shape, direction, "exit_" + bit, variantPiece,
                            capture(registry, variantPiece), first.blocks()));
                }
                row.add("base", base);
                row.add("doors", doors);
                row.add("exits", exits);
                orientations.add(row);
            }
            geometry.add("orientations", orientations);
            pieces.add(shape.name(), geometry);
        }
        var result = new JsonObject();
        result.addProperty("generator_min_y", generatorMinY);
        result.add("pieces", pieces);
        return result;
    }

    private static LegacyStructureCapture capture(RegistryAccess registry, StructurePiece piece) {
        var level = new LegacyStructureCapture(registry, 96, Blocks.STONE.defaultBlockState());
        piece.postProcess(level, null, null, level.getRandom(), BoundingBox.infinite(), new ChunkPos(0, 0), BlockPos.ZERO);
        return level;
    }

    private static int add(JsonArray templates, LinkedHashMap<BlockState, Integer> materials, Shape shape,
                           Direction direction, String name, StructurePiece piece, LegacyStructureCapture capture,
                           Map<BlockPos, BlockState> previous) {
        var b = piece.getBoundingBox();
        // Iron door buttons can extend one block beyond a piece's bounding box.
        var padded = new BoundingBox(b.minX() - 1, b.minY(), b.minZ() - 1, b.maxX() + 1, b.maxY(), b.maxZ() + 1);
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        capture.blocks().forEach((pos, state) -> {
            if (state != previous.get(pos)) {
                blocks.put(pos.offset(-padded.minX(), -padded.minY(), -padded.minZ()), state);
            }
        });
        var raw = LegacyFortressProfile.rawTemplate(blocks, capture, padded);
        return LegacyFortressProfile.addTemplate(templates,
                "retina:captured/stronghold/" + shape.name() + "/" + direction.getName() + "/" + name, raw, materials);
    }

    private static StructurePiece piece(Shape shape, Direction direction, int variant, int door, int exits) {
        boolean sideways = direction.getAxis() == Direction.Axis.X;
        int width = sideways ? shape.depth(variant) : shape.width();
        int depth = sideways ? shape.width() : shape.depth(variant);
        var tag = new CompoundTag();
        tag.putInt("O", direction.get2DDataValue());
        tag.putInt("GD", 0);
        tag.putIntArray("BB", new int[]{0, 32, 0, width - 1, 31 + shape.height(variant), depth - 1});
        tag.putString("EntryDoor", DOORS.get(door));
        tag.putBoolean("Source", shape.name().equals("start"));
        tag.putBoolean("Tall", variant == 1);
        tag.putInt("Type", variant);
        tag.putInt("Steps", shape.depth(variant));
        for (int bit = 0; bit < shape.exits().length; bit++) tag.putBoolean(shape.exits()[bit], (exits & (1 << bit)) != 0);
        return switch (shape.name()) {
            case "straight" -> new StrongholdPieces.Straight(tag);
            case "prison" -> new StrongholdPieces.PrisonHall(tag);
            case "left" -> new StrongholdPieces.LeftTurn(tag);
            case "right" -> new StrongholdPieces.RightTurn(tag);
            case "room" -> new StrongholdPieces.RoomCrossing(tag);
            case "straight_stairs" -> new StrongholdPieces.StraightStairsDown(tag);
            case "stairs" -> new StrongholdPieces.StairsDown(tag);
            case "crossing" -> new StrongholdPieces.FiveCrossing(tag);
            case "chest" -> new StrongholdPieces.ChestCorridor(tag);
            case "library" -> new StrongholdPieces.Library(tag);
            case "portal" -> new StrongholdPieces.PortalRoom(tag);
            case "start" -> new StrongholdPieces.StartPiece(tag);
            case "filler" -> new StrongholdPieces.FillerCorridor(tag);
            default -> throw new IllegalArgumentException(shape.name());
        };
    }
}
