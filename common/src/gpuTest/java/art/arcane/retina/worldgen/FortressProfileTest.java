package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import java.nio.file.*;
import java.util.*;

/** Replay exported geometry/supports against actual piece postProcess on independent floor depths. */
final class FortressProfileTest {
    static void run(HolderLookup.Provider registry, JsonObject structures, List<BlockState> materials) throws Exception {
        var fortress = structures.getAsJsonObject("fortress"); require(fortress != null, "fortress source geometry exported");
        var reference = new FortressProfile.Capture(registry);
        int variants = 0, comparisons = 0, voxels = 0, spawners = 0, chests = 0, lava = 0, processing = 0;
        var kinds = new HashSet<String>();
        for (var value : fortress.getAsJsonArray("pieces")) {
            var kind = value.getAsJsonObject(); String name = kind.get("kind").getAsString(); kinds.add(name);
            for (int direction = 0; direction < 4; direction++) {
                var entries = kind.getAsJsonArray("orientations").get(direction).getAsJsonArray();
                require(entries.size() == (FortressProfile.hasChest(name) ? 2 : 1), "explicit chest/no-chest variants");
                for (int variant = 0; variant < entries.size(); variant++) {
                    var data = entries.get(variant).getAsJsonObject(); variants++;
                    var template = structures.getAsJsonArray("templates").get(data.get("template").getAsInt()).getAsJsonObject();
                    var blocks = template.getAsJsonArray("blocks"); var palette = template.getAsJsonArray("palettes").get(0).getAsJsonArray();
                    var tags = template.getAsJsonObject("tags");
                    for (int floor : new int[]{-63, 10, 40, 64}) {
                        var source = FortressProfile.piece(name, FortressProfile.DIRECTIONS[direction], variant != 0, 0);
                        var box = source.getBoundingBox(); reference.run(source, floor, 1945L);
                        var expectedBounds = StructureProfile.ints(box.minX(), box.minY() - 64, box.minZ(), box.maxX(), box.maxY() - 64, box.maxZ());
                        require(data.get("bounds").equals(expectedBounds), name + " collision bounds");
                        var placed = new HashMap<BlockPos, BlockState>(); var entities = new HashMap<BlockPos, JsonElement>();
                        for (int i = 0; i < blocks.size(); i += 4) {
                            var pos = new BlockPos(box.minX() + blocks.get(i).getAsInt(), box.minY() + blocks.get(i + 1).getAsInt(), box.minZ() + blocks.get(i + 2).getAsInt());
                            var state = materials.get(palette.get(blocks.get(i + 3).getAsInt()).getAsJsonArray().get(0).getAsInt());
                            placed.put(pos, state);
                            if (tags.has(Integer.toString(i / 4))) entities.put(pos, tags.get(Integer.toString(i / 4)));
                        }
                        for (var entry : data.getAsJsonArray("supports")) {
                            var a = entry.getAsJsonArray(); int x = box.minX() + a.get(0).getAsInt(), z = box.minZ() + a.get(2).getAsInt();
                            for (int y = box.minY() + a.get(1).getAsInt(); y > Math.max(floor, -63); y--)
                                placed.put(new BlockPos(x, y, z), materials.get(a.get(3).getAsInt()));
                        }
                        require(placed.equals(reference.blocks), name + " source blocks, orientation=" + direction + " variant=" + variant + " floor=" + floor);
                        require(entities.size() == reference.entities.size(), name + " block entity count");
                        for (var e : reference.entities.entrySet())
                            require(StructureProfile.tag(e.getValue().saveWithFullMetadata(registry)).equals(entities.get(e.getKey())), name + " source block-entity NBT");
                        var tickSet = new LinkedHashSet<FortressProfile.Tick>();
                        for (var t : data.getAsJsonArray("ticks")) {
                            var tick = t.getAsJsonObject(); var p = tick.getAsJsonArray("pos");
                            tickSet.add(new FortressProfile.Tick(new BlockPos(box.minX() + p.get(0).getAsInt(), box.minY() + p.get(1).getAsInt(), box.minZ() + p.get(2).getAsInt()),
                                    BuiltInRegistries.FLUID.getValue(net.minecraft.resources.Identifier.parse(tick.get("fluid").getAsString())), tick.get("delay").getAsInt(), tick.get("priority").getAsInt()));
                        }
                        require(tickSet.equals(reference.ticks), name + " scheduled fluid updates");
                        var positions = new HashSet<BlockPos>();
                        for (var p : data.getAsJsonArray("post_processing")) {
                            var a = p.getAsJsonArray(); positions.add(new BlockPos(box.minX() + a.get(0).getAsInt(), box.minY() + a.get(1).getAsInt(), box.minZ() + a.get(2).getAsInt()));
                        }
                        require(positions.equals(reference.postProcessing), name + " shape postprocessing positions");
                        comparisons++; voxels += placed.size();
                    }
                    if (name.equals("monster_throne")) {
                        require(template.getAsJsonArray("size").get(1).getAsInt() == 9, "throne fences extend above height-8 collision box");
                        require(tags.size() == 1 && tags.toString().contains("minecraft:blaze"), "blaze spawner entity retained"); spawners++;
                    }
                    if (FortressProfile.hasChest(name)) {
                        require(tags.size() == variant, "optional chest retained");
                        if (variant != 0) { require(tags.toString().contains("minecraft:chests/nether_bridge"), "Nether bridge loot table"); chests++; }
                    }
                    if (name.equals("castle_entrance")) {
                        require(data.getAsJsonArray("ticks").size() == 1, "deduplicated lava well tick"); lava++;
                    }
                    processing += data.getAsJsonArray("post_processing").size();
                }
            }
        }
        require(kinds.equals(Set.copyOf(FortressProfile.KINDS)) && variants == 60, "all 13 piece kinds, four directions and chest variants");
        require(spawners == 4 && chests == 8 && lava == 4 && processing > 0, "progression metadata exported in every orientation");
        var probe = new ReplaceabilityProbe();
        var replaceable = fortress.getAsJsonArray("support_replaceable");
        for (int i = 0; i < materials.size(); i++)
            require(replaceable.contains(new JsonPrimitive(i)) == probe.accepts(materials.get(i)), "source support replaceability " + materials.get(i));
        int fillers = endFillers(fortress.getAsJsonObject("end_filler"), reference, materials);
        var report = new JsonObject(); report.addProperty("status", "pass"); report.addProperty("piece_kinds", kinds.size());
        report.addProperty("oriented_variants", variants); report.addProperty("source_replays", comparisons);
        report.addProperty("compared_writes", voxels); report.addProperty("seeded_end_filler_replays", fillers);
        report.addProperty("blaze_spawners", spawners); report.addProperty("loot_variants", chests); report.addProperty("lava_ticks", lava);
        report.addProperty("post_processing_positions", processing);
        Files.writeString(Path.of("build/nether/fortress-export.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("QA_EVT {\"event\":\"nether_fortress_export\",\"context\":" + report + "}");
    }

    private static int endFillers(JsonObject data, FortressProfile.Capture reference, List<BlockState> materials) {
        int count = 0; var shapes = new HashSet<Set<BlockPos>>();
        for (int seed : new int[]{0, 1, -1, 42, 1945, Integer.MIN_VALUE, Integer.MAX_VALUE, 123456789}) {
            for (int direction = 0; direction < 4; direction++) {
                var source = FortressProfile.piece("end_filler", FortressProfile.DIRECTIONS[direction], false, seed);
                var box = source.getBoundingBox(); reference.run(source, -63, 0);
                require(data.getAsJsonArray("bounds").get(direction).equals(StructureProfile.ints(box.minX(), box.minY() - 64, box.minZ(), box.maxX(), box.maxY() - 64, box.maxZ())), "end filler source bounds");
                var random = new Random(seed); var placed = new HashMap<BlockPos, BlockState>();
                for (var row : data.getAsJsonArray("rows")) {
                    var r = row.getAsJsonArray(); int x = r.get(0).getAsInt(), y = r.get(1).getAsInt(), maxZ = random.nextInt(r.get(2).getAsInt());
                    for (int z = 0; z <= maxZ; z++) {
                        int xx = switch (direction) { case 1 -> box.getXSpan() - 1 - z; case 3 -> z; default -> x; };
                        int zz = switch (direction) { case 1, 3 -> x; case 2 -> box.getZSpan() - 1 - z; default -> z; };
                        placed.put(new BlockPos(box.minX() + xx, box.minY() + y, box.minZ() + zz), materials.get(data.getAsJsonArray("material").get(0).getAsInt()));
                    }
                }
                require(placed.equals(reference.blocks), "seeded rubble source parity: " + seed + "/" + direction);
                shapes.add(Set.copyOf(placed.keySet())); count++;
            }
        }
        require(shapes.size() > 16, "rubble varies with seed and orientation"); return count;
    }

    private static final class ReplaceabilityProbe extends NetherFortressPieces.BridgeCrossing {
        ReplaceabilityProbe() { super(0, new BoundingBox(0, 64, 0, 18, 73, 18), Direction.SOUTH); }
        boolean accepts(BlockState state) { return isReplaceableByStructures(state); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
