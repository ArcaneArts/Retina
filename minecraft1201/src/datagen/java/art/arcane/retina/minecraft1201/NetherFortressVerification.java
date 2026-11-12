package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Export the changed placement, generate from the file, and check the 1.20.1 region reader. */
final class NetherFortressVerification {
    private static final String NETHER = "minecraft:the_nether";
    private static final String SET = "minecraft:nether_complexes";
    private static final String FORTRESS = "minecraft:fortress";
    private static final String BASTION = "minecraft:bastion_remnant";
    private static final int RX = -1;
    private static final int RZ = 0;

    private NetherFortressVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        var registry = loaded.registries().registryOrThrow(Registries.STRUCTURE_SET);
        var placement = (RandomSpreadStructurePlacement) registry.get(new ResourceLocation(SET)).placement();
        try (var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var profile = JsonParser.parseString(nativeWorld.diagnosticProfile(NETHER)).getAsJsonObject().getAsJsonObject("structures");
            var definitions = profile.getAsJsonArray("definitions");
            var sets = profile.getAsJsonArray("sets").asList();
            var set = sets.stream().map(row -> row.getAsJsonObject()).filter(row -> row.get("id").getAsString().equals(SET))
                    .findFirst().orElseThrow();
            var members = set.getAsJsonArray("entries");
            require(set.getAsJsonObject("placement").get("salt").getAsInt() == 7310029, "loaded fortress placement");
            require(members.size() == 2 && members.get(0).getAsJsonObject().get("weight").getAsInt() == 2
                    && members.get(1).getAsJsonObject().get("weight").getAsInt() == 3, "weighted Nether complexes");
            int fortress = members.get(0).getAsJsonObject().get("definition").getAsInt();
            int bastionIndex = members.get(1).getAsJsonObject().get("definition").getAsInt();
            var bastion = definitions.get(bastionIndex).getAsJsonObject();
            require(definitions.get(fortress).getAsJsonObject().get("id").getAsString().equals(FORTRESS)
                    && bastion.get("id").getAsString().equals(BASTION)
                    && bastion.get("kind").getAsString().equals("jigsaw"), "retained bastion membership");
            var kit = profile.getAsJsonObject("fortress");
            require(kit.getAsJsonArray("pieces").size() == 13
                    && kit.getAsJsonObject("end_filler").getAsJsonArray("templates").size() == 4
                    && profile.getAsJsonObject("fossils").getAsJsonArray("templates").size() == 14, "both Nether geometry kits");

            nativeWorld.bindDimension(NETHER, 2);
            var queries = new ArrayList<Integer>();
            var candidates = new ArrayList<ChunkPos>();
            for (int dx : new int[] {0, 16}) for (int dz : new int[] {0, 16}) {
                var pos = placement.getPotentialStructureChunk(seed, RX * 32 + dx, RZ * 32 + dz);
                candidates.add(pos);
                queries.add(pos.x);
                queries.add(pos.z);
                queries.add(sets.indexOf(set));
            }
            var starts = nativeWorld.structureStarts(NETHER, queries.stream().mapToInt(Integer::intValue).toArray());
            var expected = new HashSet<ChunkPos>();
            var expectedBastions = new HashSet<ChunkPos>();
            for (int i = 0; i < starts.length; i++) {
                if (starts[i] == fortress) expected.add(candidates.get(i));
                else if (starts[i] == bastionIndex) expectedBastions.add(candidates.get(i));
                else require(starts[i] == -1, "only members of the Nether complex set can generate");
            }
            require(!expected.isEmpty() && !expectedBastions.isEmpty(), "fortress fixture must exercise both choices");
            Path folder = Files.createDirectories(world.resolve("DIM-1/region"));
            Path region = folder.resolve("r." + RX + "." + RZ + ".mca");
            long started = System.nanoTime();
            require(nativeWorld.generateRegion(NETHER, RX, RZ, region).generated() == 1024, "full fortress region");
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var result = inspect(region, expected, expectedBastions);
            require(nativeWorld.generateRegion(NETHER, RX, RZ, region).preserved() == 1024, "fortress region reopen");
            result.addProperty("event", "nether_fortress_1201");
            result.addProperty("milliseconds", elapsed);
            result.addProperty("status", "pass");
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region, Set<ChunkPos> expected, Set<ChunkPos> expectedBastions) throws Exception {
        var seen = new HashSet<ChunkPos>();
        var seenBastions = new HashSet<ChunkPos>();
        int pieces = 0;
        int references = 0;
        int chests = 0;
        int spawners = 0;
        var expectedLava = new HashSet<BlockPos>();
        var lavaTicks = new HashSet<BlockPos>();
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(RX * 32 + (slot & 31), RZ * 32 + (slot >> 5));
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "fortress region chunk " + pos);
                    var chunk = NbtIo.read(input);
                    var structures = chunk.getCompound("structures");
                    var starts = structures.getCompound("starts");
                    if (starts.contains(BASTION)) seenBastions.add(pos);
                    if (starts.contains(FORTRESS)) {
                        var start = starts.getCompound(FORTRESS);
                        var children = start.getList("Children", Tag.TAG_COMPOUND);
                        require(expected.contains(pos) && start.getInt("ChunkX") == pos.x
                                && start.getInt("ChunkZ") == pos.z && children.size() > 1, "connected start " + pos);
                        for (int i = 0; i < children.size(); i++) {
                            var child = children.getCompound(i);
                            String template = child.getString("template");
                            var bounds = child.getIntArray("BB");
                            require(child.getString("id").equals("retina:template")
                                    && template.startsWith("retina:captured/fortress/")
                                    && bounds.length == 6, "saved fortress piece " + pos);
                            if (template.startsWith("retina:captured/fortress/castle_entrance/")) {
                                var lava = new BlockPos(bounds[0] + 6, bounds[1] + 5, bounds[2] + 6);
                                var chunkPos = new ChunkPos(lava);
                                if (chunkPos.getRegionX() == RX && chunkPos.getRegionZ() == RZ) expectedLava.add(lava);
                            }
                        }
                        seen.add(pos);
                        pieces += children.size();
                    }
                    for (long key : structures.getCompound("References").getLongArray(FORTRESS)) {
                        if (expected.contains(new ChunkPos(key))) references++;
                    }
                    for (var tag : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                        var block = (net.minecraft.nbt.CompoundTag) tag;
                        if (block.getString("LootTable").equals("minecraft:chests/nether_bridge")) chests++;
                        if (block.getCompound("SpawnData").getCompound("entity").getString("id").equals("minecraft:blaze")) spawners++;
                    }
                    for (var tag : chunk.getList("fluid_ticks", Tag.TAG_COMPOUND)) {
                        var tick = (net.minecraft.nbt.CompoundTag) tag;
                        if (tick.getString("i").equals("minecraft:lava") && tick.getInt("t") == 0) {
                            lavaTicks.add(new BlockPos(tick.getInt("x"), tick.getInt("y"), tick.getInt("z")));
                        }
                    }
                }
            }
        }
        require(seen.equals(expected) && references > seen.size(), "fortress starts and cross-chunk references");
        require(seenBastions.equals(expectedBastions), "weighted bastion starts");
        require(chests > 0 && spawners > 0, "fortress chest loot and blaze spawners: " + chests + "/" + spawners);
        require(!expectedLava.isEmpty() && lavaTicks.containsAll(expectedLava), "immediate lava ticks at fortress entrances");
        var result = new JsonObject();
        result.addProperty("starts", seen.size());
        result.addProperty("bastion_starts", seenBastions.size());
        result.addProperty("pieces", pieces);
        result.addProperty("references", references);
        result.addProperty("loot_chests", chests);
        result.addProperty("blaze_spawners", spawners);
        result.addProperty("entrance_lava_ticks", expectedLava.size());
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
