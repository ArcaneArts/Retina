package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read and reopen the actual regions generated from the 1.20.1 pool graph. */
final class JigsawVerification {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final List<String> OVERWORLD_SETS = List.of("villages", "ancient_cities", "trail_ruins", "pillager_outposts");
    private static final Set<String> OVERWORLD_KINDS = Set.of("village_plains", "village_savanna", "village_taiga",
            "village_snowy", "village_desert", "ancient_city", "trail_ruins", "pillager_outpost");

    private JigsawVerification() { }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            verifyDimension(definition, world, loaded, seed, OVERWORLD, OVERWORLD_SETS, OVERWORLD_KINDS);
            verifyDimension(definition, world, loaded, seed, NETHER, List.of("nether_complexes"), Set.of("bastion_remnant"));
        }
    }

    private static void verifyDimension(NativeWorldDefinition definition, Path world, RegistryExport.Loaded loaded,
                                        long seed, String dimension, List<String> names, Set<String> wanted) throws Exception {
        definition.bindDimension(dimension, 2);
        var profile = JsonParser.parseString(definition.diagnosticProfile(dimension)).getAsJsonObject().getAsJsonObject("structures");
        var sets = profile.getAsJsonArray("sets").asList();
        var definitions = profile.getAsJsonArray("definitions");
        var registry = loaded.registries().registryOrThrow(Registries.STRUCTURE_SET);
        var generated = new HashSet<Path>();
        for (String name : names) {
            String setId = "minecraft:" + name;
            var row = sets.stream().map(value -> value.getAsJsonObject()).filter(set -> set.get("id").getAsString().equals(setId))
                    .findFirst().orElseThrow();
            var placement = (RandomSpreadStructurePlacement) registry.get(new ResourceLocation(setId)).placement();
            if (name.equals("pillager_outposts")) {
                require(row.getAsJsonObject("placement").getAsJsonObject("exclusion_zone").get("other_set").getAsString()
                        .equals("minecraft:villages"), "preserved outpost exclusion");
            }
            var sample = find(definition, dimension, seed, placement, sets.indexOf(row), wanted, definitions);
            var pos = sample.position();
            int rx = pos.getRegionX();
            int rz = pos.getRegionZ();
            var folder = Files.createDirectories(world.resolve(dimension.equals(NETHER) ? "DIM-1/region" : "region"));
            var region = folder.resolve("r." + rx + "." + rz + ".mca");
            long started = System.nanoTime();
            if (generated.add(region)) {
                require(definition.generateRegion(dimension, rx, rz, region).generated() == 1024, "full jigsaw region " + region);
            }
            var result = inspect(region, sample);
            require(definition.generateRegion(dimension, rx, rz, region).preserved() == 1024, "jigsaw region reopen " + region);
            result.addProperty("event", "jigsaw_1201");
            result.addProperty("status", "pass");
            result.addProperty("set", setId);
            result.addProperty("structure", sample.id());
            result.addProperty("x", pos.x);
            result.addProperty("z", pos.z);
            result.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000);
            System.out.println("QA_EVT " + result);
        }
    }

    private static Sample find(NativeWorldDefinition definition, String dimension, long seed,
                               RandomSpreadStructurePlacement placement, int set, Set<String> wanted,
                               JsonArray definitions) {
        for (int radius = 0; radius <= 12; radius++) {
            int width = radius == 0 ? 1 : radius * 8;
            var candidates = new ChunkPos[width];
            int[] requests = new int[width * 3];
            int index = 0;
            for (int z = -radius; z <= radius; z++) for (int x = -radius; x <= radius; x++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                var pos = placement.getPotentialStructureChunk(seed, x * placement.spacing(), z * placement.spacing());
                candidates[index] = pos;
                requests[index * 3] = pos.x;
                requests[index * 3 + 1] = pos.z;
                requests[index * 3 + 2] = set;
                index++;
            }
            for (int from = 0; from < width; from += 64) {
                var hits = definition.structureStarts(dimension, Arrays.copyOfRange(requests, from * 3, Math.min(width, from + 64) * 3));
                for (int i = 0; i < hits.length; i++) {
                    if (hits[i] < 0) continue;
                    var row = definitions.get(hits[i]).getAsJsonObject();
                    String id = row.get("id").getAsString();
                    if (id.startsWith("minecraft:") && wanted.contains(id.substring("minecraft:".length()))) {
                        require(row.get("kind").getAsString().equals("jigsaw"), "jigsaw definition " + id);
                        return new Sample(candidates[from + i], id);
                    }
                }
            }
        }
        throw new IllegalStateException("No 1.20.1 jigsaw start for " + wanted + " in set " + set);
    }

    private static JsonObject inspect(Path region, Sample sample) throws Exception {
        var center = sample.position();
        int pieces = 0;
        int references = 0;
        int containers = 0;
        int entities = 0;
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (int slot = 0; slot < 1024; slot++) {
                var pos = new ChunkPos(center.getRegionX() * 32 + (slot & 31), center.getRegionZ() * 32 + (slot >> 5));
                try (var input = file.getChunkDataInputStream(pos)) {
                    require(input != null, "jigsaw chunk " + pos);
                    var chunk = NbtIo.read(input);
                    var structures = chunk.getCompound("structures");
                    if (pos.equals(center)) {
                        var start = structures.getCompound("starts").getCompound(sample.id());
                        require(start.getString("id").equals(sample.id()), "saved jigsaw start " + sample);
                        var children = start.getList("Children", Tag.TAG_COMPOUND);
                        pieces = children.size();
                        for (int i = 0; i < pieces; i++) {
                            var child = children.getCompound(i);
                            require(child.getString("id").equals("retina:template") && child.getIntArray("BB").length == 6,
                                    "saved jigsaw piece " + sample);
                        }
                    }
                    var refs = structures.getCompound("References").getLongArray(sample.id());
                    if (Arrays.stream(refs).noneMatch(value -> value == center.toLong())) continue;
                    references++;
                    for (var tag : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                        if (((CompoundTag) tag).contains("LootTable", Tag.TAG_STRING)) containers++;
                    }
                    entities += chunk.getList("entities", Tag.TAG_COMPOUND).size();
                }
            }
        }
        require(pieces > 1 && references > 1, "connected jigsaw and cross-chunk references " + sample + ": " + pieces);
        var result = new JsonObject();
        result.addProperty("pieces", pieces);
        result.addProperty("references", references);
        result.addProperty("loot_containers", containers);
        result.addProperty("entities", entities);
        return result;
    }

    private record Sample(ChunkPos position, String id) { }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
