package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

final class RingPlacementVerification {
    private static final String SET = "retina:ring_pyramids";
    private static final String STRUCTURE = "retina:ring_pyramid";
    private static final String BLOCKED = "retina:ring_blocked";
    private static final String RAW = "retina:ring_raw";
    private static final String DORMANT = "retina:ring_dormant";
    private static final String INDEPENDENT = "retina:ring_independent";
    private static final String SUBSET = "retina:ring_subset";
    private static final List<String> SETS = List.of(SET, RAW, BLOCKED, INDEPENDENT, SUBSET);

    private RingPlacementVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-concentric-rings");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina 1.20.1 concentric ring fixture"}}
                """);
        for (var id : List.of(STRUCTURE, BLOCKED, RAW, DORMANT, INDEPENDENT, SUBSET)) {
            var biomes = id.equals(DORMANT) ? "[\"minecraft:the_void\"]" : "[\"minecraft:desert\",\"minecraft:plains\"]";
            write(data(pack, "worldgen/structure", id), """
                    {"type":"minecraft:desert_pyramid","biomes":%s,
                     "step":"surface_structures","spawn_overrides":{},"terrain_adaptation":"none"}
                    """.formatted(biomes));
        }
        var preferred = "[\"minecraft:desert\",\"minecraft:plains\"]";
        structureSet(pack, SET, STRUCTURE, preferred, "");
        structureSet(pack, RAW, RAW, "[\"minecraft:the_void\"]", "");
        structureSet(pack, BLOCKED, BLOCKED, preferred, """
                ,"exclusion_zone":{"other_set":"retina:ring_pyramids","chunk_count":1}
                """);
        structureSet(pack, DORMANT, DORMANT, preferred, "");
        structureSet(pack, INDEPENDENT, INDEPENDENT, preferred, """
                ,"exclusion_zone":{"other_set":"retina:ring_dormant","chunk_count":1}
                """);
        structureSet(pack, SUBSET, SUBSET, "[\"minecraft:desert\"]", "");
        write(data(pack, "dimension", "retina:ring_flat"), """
                {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                  "biome":"minecraft:desert","features":false,"lakes":false,
                  "structure_overrides":["retina:ring_pyramids","retina:ring_raw","retina:ring_blocked",
                    "retina:ring_independent","retina:ring_subset"],
                  "layers":[{"block":"minecraft:sandstone","height":90},{"block":"minecraft:sand","height":4}]}}}
                """);
        noise(pack, "fixed", """
                {"type":"minecraft:fixed","biome":"minecraft:desert"}
                """);
        noise(pack, "multi", """
                {"type":"minecraft:multi_noise","biomes":[
                  {"biome":"minecraft:desert","parameters":{"temperature":[-1,0],"humidity":0,"continentalness":0,
                    "erosion":0,"weirdness":0,"depth":0,"offset":0}},
                  {"biome":"minecraft:plains","parameters":{"temperature":[0,1],"humidity":0,"continentalness":0,
                    "erosion":0,"weirdness":0,"depth":0,"offset":0}}]}
                """);
    }

    private static void structureSet(Path pack, String id, String structure, String preferred, String exclusion) throws Exception {
        write(data(pack, "worldgen/structure_set", id), """
                {"structures":[{"structure":"%s","weight":1}],"placement":{"type":"minecraft:concentric_rings",
                  "distance":11,"spread":3,"count":9,"salt":23,"locate_offset":[4,0,9],"preferred_biomes":%s%s}}
                """.formatted(structure, preferred, exclusion));
    }

    private static void noise(Path pack, String id, String biomeSource) throws Exception {
        write(data(pack, "dimension", "retina:ring_" + id), """
                {"type":"minecraft:overworld","generator":{"type":"minecraft:noise",
                  "settings":"minecraft:overworld","biome_source":%s}}
                """.formatted(biomeSource));
    }

    private static Path data(Path pack, String folder, String id) {
        return pack.resolve("data/retina/" + folder + "/" + id.substring(id.indexOf(':') + 1) + ".json");
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    static void verify(Path world, RegistryExport.Loaded loaded, long seed) throws Exception {
        var registries = loaded.registries();
        var noise = RandomState.create(registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(), registries.lookupOrThrow(Registries.NOISE), seed);
        var vanillaSets = registries.registryOrThrow(Registries.STRUCTURE_SET);
        try (var nativeWorld = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var dimensions = JsonParser.parseString(nativeWorld.metadata()).getAsJsonObject().getAsJsonObject("dimensions");
            for (String mode : List.of("flat", "fixed", "multi")) {
                String dimension = "retina:ring_" + mode;
                var stem = loaded.dimensions().getHolderOrThrow(ResourceKey.create(Registries.LEVEL_STEM,
                        new ResourceLocation(dimension))).value();
                var state = stem.generator().createState(vanillaSets.asLookup(), noise, seed);
                nativeWorld.bindDimension(dimension, 2);
                var profile = JsonParser.parseString(nativeWorld.diagnosticProfile(dimension)).getAsJsonObject();
                var structures = profile.getAsJsonObject("structures");
                var adapterSets = dimensions.getAsJsonObject(dimension).getAsJsonObject("profile_metadata")
                        .getAsJsonObject("structures").getAsJsonArray("sets");
                long start = System.nanoTime();
                ChunkPos negative = null;
                int total = 0;
                int subsetVanillaMatches = 0;
                int subsetSavedMatches = 0;
                int[] subsetPositions = null;
                List<ChunkPos> unrelocated = null;
                int dormant = setIndex(structures, DORMANT);
                require(adapterSets.get(dormant).getAsJsonObject().get("ring_count").getAsInt() == 0,
                        "dormant ring adapter metadata " + dimension);
                require(nativeWorld.structureRingPositions(dimension, dormant, 0).length == 0,
                        "an inapplicable set has no ring positions " + dimension);
                for (String id : SETS) {
                    int index = setIndex(structures, id);
                    var placement = (ConcentricRingsStructurePlacement) vanillaSets.get(new ResourceLocation(id)).placement();
                    require(adapterSets.get(index).getAsJsonObject().get("ring_count").getAsInt() == placement.count(),
                            "ring adapter metadata " + dimension + " " + id);
                    var expected = state.getRingPositionsFor(placement);
                    int[] actual = nativeWorld.structureRingPositions(dimension, index, placement.count());
                    if (id.equals(RAW)) unrelocated = expected;
                    boolean subset = mode.equals("multi") && id.equals(SUBSET);
                    if (subset) {
                        subsetPositions = actual;
                        for (int point = 0; point < expected.size(); point++) {
                            if (expected.get(point).equals(new ChunkPos(actual[point * 2], actual[point * 2 + 1]))) subsetVanillaMatches++;
                        }
                        expected = relocateSavedBiomes(nativeWorld, dimension, profile, unrelocated, vanillaSets, noise,
                                registries.registryOrThrow(Registries.BIOME), seed, placement);
                        for (int point = 0; point < expected.size(); point++) {
                            if (expected.get(point).equals(new ChunkPos(actual[point * 2], actual[point * 2 + 1]))) subsetSavedMatches++;
                        }
                    }
                    int[] flattened = expected.stream().flatMapToInt(pos -> java.util.stream.IntStream.of(pos.x, pos.z)).toArray();
                    // Ring placement always uses the CPU source sampler. GPU terrain samples can
                    // differ at climate boundaries, so emit their comparison and the actual positions.
                    if (!subset || "cpu".equals(System.getProperty("retina.gpuBackend"))) {
                        require(Arrays.equals(flattened, actual), dimension + " " + id + " positions "
                                + Arrays.toString(flattened) + " vs " + Arrays.toString(actual));
                    }
                    if (id.equals(SET)) {
                        negative = expected.stream().filter(pos -> pos.x < 0).findFirst().orElseThrow();
                    }
                    if (id.equals(BLOCKED) || id.equals(INDEPENDENT) && mode.equals("flat")) {
                        int[] candidates = expected.stream().flatMapToInt(pos -> java.util.stream.IntStream.of(pos.x, pos.z, index)).toArray();
                        require(Arrays.stream(nativeWorld.structureStarts(dimension, candidates))
                                        .allMatch(found -> id.equals(BLOCKED) ? found == -1 : found >= 0),
                                "ring exclusion " + dimension);
                    }
                    total += expected.size();
                }
                require(negative != null, "negative ring candidate");
                var result = new JsonObject();
                if (mode.equals("flat")) region(world, nativeWorld, dimension, structures, negative, result);
                result.addProperty("event", "concentric_rings_1201");
                result.addProperty("dimension", dimension);
                result.addProperty("chunk_x", negative.x);
                result.addProperty("chunk_z", negative.z);
                result.addProperty("locate_x", negative.getMinBlockX() + 4);
                result.addProperty("locate_z", negative.getMinBlockZ() + 9);
                result.addProperty("positions", total);
                if (subsetPositions != null) {
                    result.addProperty("subset_matches_unmodified_vanilla_biomes", subsetVanillaMatches);
                    result.addProperty("subset_matches_sampled_retina_biomes", subsetSavedMatches);
                    var positions = new JsonArray();
                    for (int coordinate : subsetPositions) positions.add(coordinate);
                    result.add("subset_ring_positions", positions);
                }
                result.addProperty("milliseconds", (System.nanoTime() - start) / 1_000_000);
                System.out.println("QA_EVT " + result);
            }
        }
    }

    private static List<ChunkPos> relocateSavedBiomes(NativeWorldDefinition world, String dimension, JsonObject profile,
                                                     List<ChunkPos> centers, Registry<StructureSet> sets, RandomState noise,
                                                     Registry<Biome> registry, long seed, ConcentricRingsStructurePlacement placement) {
        var biomes = profile.getAsJsonArray("biomes").asList().stream()
                .<Holder<Biome>>map(row -> registry.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                        new ResourceLocation(row.getAsJsonObject().get("id").getAsString())))).toList();
        var grid = new HashMap<Long, Holder<Biome>>();
        for (var center : centers) {
            int[] coords = new int[57 * 57 * 3];
            int at = 0;
            for (int dz = -112; dz <= 112; dz += 4) for (int dx = -112; dx <= 112; dx += 4) {
                coords[at++] = center.getMiddleBlockX() + dx;
                coords[at++] = 0;
                coords[at++] = center.getMiddleBlockZ() + dz;
            }
            for (int start = 0; start < coords.length; start += 3072) {
                int[] batch = Arrays.copyOfRange(coords, start, Math.min(start + 3072, coords.length));
                int[] samples = world.biomePoints(dimension, batch);
                for (int i = 0; i < samples.length; i++) {
                    grid.put(ChunkPos.asLong(batch[i * 3] >> 2, batch[i * 3 + 2] >> 2), biomes.get(samples[i]));
                }
            }
        }
        var savedSource = new BiomeSource() {
            @Override protected Codec<? extends BiomeSource> codec() { return Codec.unit(this); }
            @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return biomes.stream(); }
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                var biome = grid.get(ChunkPos.asLong(x, z));
                if (biome == null) throw new IllegalStateException("Unprobed ring biome " + x + "," + z);
                return biome;
            }
        };
        var target = sets.get(new ResourceLocation(SUBSET));
        return ChunkGeneratorStructureState.createForNormal(noise, seed, savedSource,
                sets.asLookup().filterElements(set -> set == target)).getRingPositionsFor(placement);
    }

    private static int setIndex(JsonObject structures, String name) {
        var sets = structures.getAsJsonArray("sets");
        for (int i = 0; i < sets.size(); i++) {
            if (sets.get(i).getAsJsonObject().get("id").getAsString().equals(name)) return i;
        }
        throw new IllegalStateException("Missing exported set " + name);
    }

    private static void region(Path world, NativeWorldDefinition nativeWorld, String dimension, JsonObject structures,
                               ChunkPos candidate, JsonObject result) throws Exception {
        int set = setIndex(structures, SET);
        int definition = nativeWorld.structureStarts(dimension, new int[] {candidate.x, candidate.z, set})[0];
        require(definition >= 0 && structures.getAsJsonArray("definitions").get(definition).getAsJsonObject()
                .get("id").getAsString().equals(STRUCTURE), "Flat native ring start");
        var directory = Files.createDirectories(world.resolve("ring_region"));
        var path = directory.resolve("r." + candidate.getRegionX() + "." + candidate.getRegionZ() + ".mca");
        require(nativeWorld.generateRegion(dimension, candidate.getRegionX(), candidate.getRegionZ(), path).generated() == 1024,
                "whole Flat ring region");
        try (var file = new RegionFile(path, directory, true); var input = file.getChunkDataInputStream(candidate)) {
            var structuresTag = NbtIo.read(input).getCompound("structures");
            var saved = structuresTag.getCompound("starts").getCompound(STRUCTURE);
            require(saved.getString("id").equals(STRUCTURE) && saved.contains("Children"), "saved ring structure start");
            require(Arrays.stream(structuresTag.getCompound("References").getLongArray(STRUCTURE))
                    .anyMatch(reference -> reference == candidate.toLong()), "saved ring structure reference");
        }
        require(nativeWorld.generateRegion(dimension, candidate.getRegionX(), candidate.getRegionZ(), path).preserved() == 1024,
                "reopened Flat ring region");
        result.addProperty("chunks", 1024);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
