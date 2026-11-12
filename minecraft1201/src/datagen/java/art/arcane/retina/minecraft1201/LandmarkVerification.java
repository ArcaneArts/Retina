package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class LandmarkVerification {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final int RX = -2;
    private static final int RZ = 2;
    private record Landmark(String set, String id, String template) { }
    private static final Landmark[] LANDMARKS = {
            new Landmark("desert_pyramids", "minecraft:desert_pyramid", "retina:captured/desert_pyramid"),
            new Landmark("jungle_temples", "minecraft:jungle_pyramid", "retina:captured/jungle_temple"),
            new Landmark("swamp_huts", "minecraft:swamp_hut", "retina:captured/swamp_hut")
    };

    private LandmarkVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var profile = JsonParser.parseString(definition.diagnosticProfile(OVERWORLD)).getAsJsonObject().getAsJsonObject("structures");
            var sets = profile.getAsJsonArray("sets").asList();
            var entries = profile.getAsJsonArray("definitions");
            var registry = loaded.registries().registryOrThrow(Registries.STRUCTURE_SET);
            var positions = new LinkedHashMap<Landmark, ChunkPos>();
            definition.bindDimension(OVERWORLD, 2);
            for (var landmark : LANDMARKS) {
                var placement = (RandomSpreadStructurePlacement) registry.get(new ResourceLocation(landmark.set())).placement();
                var candidate = placement.getPotentialStructureChunk(seed, RX * 32, RZ * 32);
                require(candidate.getRegionX() == RX && candidate.getRegionZ() == RZ, "fixture placement " + landmark);
                positions.put(landmark, candidate);
                var set = sets.stream().filter(row -> row.getAsJsonObject().get("id").getAsString().equals("minecraft:" + landmark.set()))
                        .findFirst().orElseThrow();
                int index = definition.structureStarts(OVERWORLD, new int[] {candidate.x, candidate.z, sets.indexOf(set)})[0];
                require(index >= 0 && entries.get(index).getAsJsonObject().get("id").getAsString().equals(landmark.id()),
                        "native landmark start " + landmark);
            }
            Path directory = Files.createDirectories(world.resolve("region"));
            Path region = directory.resolve("r." + RX + "." + RZ + ".mca");
            long started = System.nanoTime();
            require(definition.generateRegion(OVERWORLD, RX, RZ, region).generated() == 1024, "full landmark region");
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var result = inspect(region, positions);
            require(definition.generateRegion(OVERWORLD, RX, RZ, region).preserved() == 1024, "landmark region reopen");
            result.addProperty("event", "overworld_landmarks_1201");
            result.addProperty("milliseconds", elapsed);
            result.addProperty("status", "pass");
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region, Map<Landmark, ChunkPos> expected) throws Exception {
        var starts = new HashSet<Landmark>();
        var references = new HashMap<Landmark, Integer>();
        var loot = new HashMap<String, Integer>();
        var residents = new HashSet<String>();
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(RX * 32 + (slot & 31), RZ * 32 + (slot >> 5));
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "landmark region chunk " + pos);
                    var chunk = NbtIo.read(input);
                    var structures = chunk.getCompound("structures");
                    var saved = structures.getCompound("starts");
                    var refs = structures.getCompound("References");
                    var used = false;
                    for (var entry : expected.entrySet()) {
                        var landmark = entry.getKey();
                        var origin = entry.getValue();
                        if (saved.contains(landmark.id())) {
                            var start = saved.getCompound(landmark.id());
                            var pieces = start.getList("Children", Tag.TAG_COMPOUND);
                            var piece = pieces.getCompound(0);
                            require(pos.equals(origin) && start.getInt("ChunkX") == origin.x && start.getInt("ChunkZ") == origin.z
                                            && pieces.size() == 1 && piece.getString("id").equals("retina:template")
                                            && piece.getString("template").equals(landmark.template())
                                            && piece.getIntArray("BB").length == 6,
                                    "saved landmark " + landmark + " at " + pos);
                            starts.add(landmark);
                        }
                        for (long key : refs.getLongArray(landmark.id())) {
                            if (key == origin.toLong()) {
                                references.merge(landmark, 1, Integer::sum);
                                used = true;
                            }
                        }
                    }
                    if (!used) continue;
                    for (var tag : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                        var block = (net.minecraft.nbt.CompoundTag) tag;
                        if (block.contains("LootTable", Tag.TAG_STRING)) loot.merge(block.getString("LootTable"), 1, Integer::sum);
                    }
                    for (var tag : chunk.getList("entities", Tag.TAG_COMPOUND)) {
                        var entity = (net.minecraft.nbt.CompoundTag) tag;
                        if (entity.getBoolean("PersistenceRequired")) residents.add(entity.getString("id"));
                    }
                }
            }
        }
        require(starts.equals(expected.keySet()), "all three landmark starts");
        require(references.getOrDefault(LANDMARKS[0], 0) > 1 && references.keySet().equals(expected.keySet()), "cross-chunk landmark references");
        require(loot.keySet().containsAll(Set.of("minecraft:chests/desert_pyramid", "minecraft:archaeology/desert_pyramid",
                        "minecraft:chests/jungle_temple", "minecraft:chests/jungle_temple_dispenser")), "landmark loot " + loot);
        require(residents.containsAll(Set.of("minecraft:witch", "minecraft:cat")), "persistent swamp hut residents");
        var result = new JsonObject();
        result.addProperty("starts", starts.size());
        result.addProperty("references", references.values().stream().mapToInt(Integer::intValue).sum());
        result.add("loot", new com.google.gson.Gson().toJsonTree(loot));
        result.add("residents", new com.google.gson.Gson().toJsonTree(residents));
        return result;
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
