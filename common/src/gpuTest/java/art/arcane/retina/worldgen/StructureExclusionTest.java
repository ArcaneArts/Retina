package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Compare exported exclusion graphs with Minecraft's actual placement implementation. */
final class StructureExclusionTest {
    static void run(RegistryAccess registry, ResourceManager resources, TerrainProfileData exported) throws Exception {
        var source = JsonParser.parseString(exported.json()).getAsJsonObject();
        var terrain = NativeTerrain.instance();
        var report = new JsonArray();
        int checked = 0, excluded = 0, accepted = 0;
        var bastion = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:bastion_remnant"));
        var stronghold = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:stronghold"));
        var pyramid = registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key("minecraft:desert_pyramid"));
        var originalContext = StructureProfile.Context.of(resources);
        for (int variant = 0; variant < 6; variant++) {
            var sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
            var reduction = FrequencyReductionMethod.values()[variant % 4];
            var leafPlacement = placement(7, 2, 831, FrequencyReductionMethod.DEFAULT, .7f, Optional.empty(), RandomSpreadType.TRIANGULAR);
            var leaf = sets.register(setKey("leaf"), new StructureSet(pyramid, leafPlacement), RegistrationInfo.BUILT_IN);
            // A supported family outside the selected source sets is still only a dependency.
            var middleFamily = variant == 4 ? bastion : stronghold;
            var middlePlacement = placement(9, 3, 719, reduction, .45f,
                    Optional.of(new ExclusionZone(leaf, 2)), RandomSpreadType.LINEAR);
            var middle = sets.register(setKey("middle"), new StructureSet(middleFamily, middlePlacement), RegistrationInfo.BUILT_IN);
            var rootPlacement = placement(13, 5, 239, reduction, .8f,
                    Optional.of(new ExclusionZone(middle, 4)), RandomSpreadType.TRIANGULAR);
            var root = sets.register(setKey("root"), new StructureSet(bastion, rootPlacement), RegistrationInfo.BUILT_IN);
            sets.freeze();
            var access = overlay(registry, sets);
            var context = new StructureProfile.Context(originalContext.templates(), true,
                    variant == 5 ? Optional.empty() : Optional.of(Set.of(setKey("root").identifier())));
            var palette = palette(exported.materials());
            var structures = StructureProfile.export(access, exported.biomes(), palette, context);
            if (variant == 4) {
                var generating = StructureProfile.export(access, exported.biomes(), palette,
                        new StructureProfile.Context(originalContext.templates(), true,
                                Optional.of(Set.of(setKey("root").identifier(), setKey("middle").identifier()))));
                var shared = generating.getAsJsonArray("sets");
                require(shared.size() == 3, "already exported dependency is not duplicated");
                var middleSet = shared.asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(s -> s.get("id").getAsString().equals("retina:middle")).findFirst().orElseThrow();
                require(!middleSet.has("placement_only") && middleSet.getAsJsonArray("entries").size() == 1,
                        "a dependency also selected for generation retains its weighted entries");
            }
            var actualSets = structures.getAsJsonArray("sets");
            require(actualSets.size() == 3, "transitive exclusion closure retained");
            require(actualSets.get(0).getAsJsonObject().get("id").getAsString().equals("retina:root"), "root index remains stable");
            for (int i = 1; i < 3; i++) {
                var dependency = actualSets.get(i).getAsJsonObject();
                require(dependency.get("placement_only").getAsBoolean() && dependency.getAsJsonArray("entries").isEmpty(),
                        "excluded/ineligible families are placement dependencies only");
            }
            require(actualSets.get(0).getAsJsonObject().getAsJsonArray("entries").size() == 1, "source weights untouched");
            var ops = access.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
            for (var item : actualSets) {
                var set = item.getAsJsonObject();
                require(set.get("placement").equals(StructurePlacement.CODEC.encodeStart(ops,
                        sets.getValue(Identifier.parse(set.get("id").getAsString())).placement()).getOrThrow()), "effective placement preserved");
            }
            // Isolate placement eligibility from terrain/layout acceptance. Every permitted
            // root has a tiny, valid jigsaw start in every source biome.
            int definition = actualSets.get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("definition").getAsInt();
            var d = structures.getAsJsonArray("definitions").get(definition).getAsJsonObject();
            var templateExport = new StructureProfile(access, exported.biomes(), palette, StructureProfile.Context.NONE);
            templateExport.template("retina:exclusion_probe", GenerationStepIntegrationTest.template(false), 0);
            structures.add("templates", templateExport.templates);
            structures.remove("fortress"); structures.remove("fossils"); structures.remove("portals");
            var definitions = new JsonArray();
            for (var value : structures.getAsJsonArray("definitions")) {
                var unused = value.getAsJsonObject();
                if (unused != d) unused.addProperty("unimplemented", true);
                definitions.add(unused);
            }
            structures.add("definitions", definitions);
            d.addProperty("kind", "jigsaw"); d.addProperty("pool", "retina:probe"); d.remove("unimplemented");
            d.add("config", JsonParser.parseString("{\"size\":0,\"start_height\":{\"absolute\":64},\"terrain_adaptation\":\"none\"}"));
            var biomes = new JsonArray(); for (int i = 0; i < exported.biomes().size(); i++) biomes.add(i); d.add("biomes", biomes);
            structures.add("pools", JsonParser.parseString("""
                    {"retina:probe":{"fallback":"minecraft:empty","entries":[{"weight":1,"terrain_matching":false,
                    "parts":[{"template":0,"ignore_air":false,"processors":[]}]}]}}
                    """));
            StructureProfile.finishMaterials(structures, palette);
            var fixture = source.deepCopy(); fixture.add("structures", structures);
            var materials = new JsonArray();
            palette.keySet().forEach(state -> materials.add(BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, state).getOrThrow()));
            require(palette.size() == exported.materials().length, "probe uses existing material palette");
            fixture.add("materials", materials);
            Files.writeString(Path.of("build/nether/exclusion-fixture-" + variant + ".json"), fixture.toString());
            var samples = new JsonArray();
            for (long seed : new long[]{0, 42, -1, 123456789}) {
                try(var saved=NetherTerrainTest.fixture(fixture,seed)) {
                    int profile=saved.request(0,0).profile();
                    var random = RandomState.create(registry.lookupOrThrow(Registries.NOISE), seed,
                            registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value());
                    var state = ChunkGeneratorStructureState.createForFlat(random, seed, new ChunkPos(0, 0),
                            new FixedBiomeSource(exported.biomes().getFirst()), Stream.of(root));
                    int count = 64;
                    int[] input = new int[count * 3], expected = new int[count];
                    for (int i = 0; i < count; i++) {
                        var at = rootPlacement.getPotentialStructureChunk(seed, (i - 32) * 37, (31 - i) * 29);
                        int x = at.x() + (i % 8 == 0 ? 1 : 0), z = at.z();
                        input[i * 3] = x; input[i * 3 + 1] = z;
                        boolean permitted = rootPlacement.isStructureChunk(state, x, z);
                        expected[i] = permitted ? definition : -1;
                        if (permitted) accepted++;
                        else if (at.x() == x && rootPlacement.applyAdditionalChunkRestrictions(x, z, seed)) excluded++;
                        var sample = new JsonObject(); sample.addProperty("seed", seed); sample.addProperty("x", x); sample.addProperty("z", z); sample.addProperty("permitted", permitted); samples.add(sample);
                    }
                    var request = saved.request(0,0);
                    long chunks = terrain.timings(profile).chunks();
                    require(Arrays.equals(expected, terrain.queryStructureStarts(request, input)), "Minecraft exclusion parity variant=" + variant + " seed=" + seed);
                    require(Arrays.equals(expected, terrain.queryStructureStarts(request, input)), "cached exclusion parity");
                    require(terrain.timings(profile).chunks() == chunks, "placement queries do not generate chunks");
                    for (int i = 1; i < 3; i++) {
                        var holder = i == 1 ? middle : leaf;
                        var at = ((RandomSpreadStructurePlacement) holder.value().placement()).getPotentialStructureChunk(seed, -39, 27);
                        require(terrain.queryStructureStarts(request, new int[]{at.x(), at.z(), i})[0] == -1, "dependencies cannot become native starts");
                    }
                    var data = saved.savedDimension().bind(access);
                    try (var queries = new TerrainQueries(request, data)) {
                        var search = queries.prepareStructures(access, HolderSet.direct(bastion, middleFamily, pyramid), BlockPos.ZERO, 1);
                        require(search.sets().size() == 1 && search.sets().getFirst().index() == 0, "locate never advertises dependency-only sets");
                    }
                    checked += count;
                }
            }
            var result = new JsonObject(); result.addProperty("variant", variant); result.addProperty("frequency_method", reduction.getSerializedName()); result.add("samples", samples); report.add(result);
        }
        require(excluded > 0 && accepted > 0, "fixture exercises both exclusion and acceptance");
        unsupportedDependency(registry, originalContext, exported, bastion, pyramid);
        Files.writeString(Path.of("build/nether/exclusion-parity.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("QA_EVT {\"event\":\"structure_exclusion_parity\",\"status\":\"pass\",\"context\":{\"candidates\":" + checked
                + ",\"accepted\":" + accepted + ",\"excluded\":" + excluded + ",\"variants\":6}}");
    }

    private static void unsupportedDependency(RegistryAccess registry, StructureProfile.Context templates, TerrainProfileData exported,
                                               Holder<Structure> rootFamily, Holder<Structure> dependencyFamily) {
        for (boolean subclass : new boolean[]{false, true}) {
            var sets = new MappedRegistry<>(Registries.STRUCTURE_SET, Lifecycle.stable());
            StructurePlacement unsupported = subclass ? new RandomSpreadStructurePlacement(9, 2, RandomSpreadType.LINEAR, 1) {
                @Override public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) { return true; }
            } : registry.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(
                    ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse("minecraft:strongholds"))).value().placement();
            var dependency = sets.register(setKey("unsupported"), new StructureSet(dependencyFamily, unsupported), RegistrationInfo.BUILT_IN);
            sets.register(setKey("root"), new StructureSet(rootFamily, placement(13, 5, 239, FrequencyReductionMethod.DEFAULT, 1,
                    Optional.of(new ExclusionZone(dependency, 4)), RandomSpreadType.LINEAR)), RegistrationInfo.BUILT_IN);
            sets.freeze();
            try {
                StructureProfile.export(overlay(registry, sets), exported.biomes(), palette(exported.materials()),
                        new StructureProfile.Context(templates.templates(), true, Optional.of(Set.of(setKey("root").identifier()))));
                throw new AssertionError("unsupported required placement silently omitted");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("Unsupported exclusion placement") && expected.getMessage().contains("retina:unsupported"),
                        "failure identifies actual unsupported exclusion dependency: " + expected);
            }
        }
    }
    private static RandomSpreadStructurePlacement placement(int spacing, int separation, int salt, FrequencyReductionMethod reduction,
                                                            float frequency, Optional<ExclusionZone> exclusion, RandomSpreadType spread) {
        return new RandomSpreadStructurePlacement(Vec3i.ZERO, reduction, frequency, salt, exclusion, spacing, separation, spread);
    }
    private static RegistryAccess overlay(RegistryAccess registry, Registry<StructureSet> sets) {
        return new RegistryAccess.ImmutableRegistryAccess(Stream.concat(registry.registries().filter(r -> !r.key().equals(Registries.STRUCTURE_SET)),
                Stream.of(new RegistryAccess.RegistryEntry<>(Registries.STRUCTURE_SET, sets))));
    }
    private static LinkedHashMap<BlockState, Integer> palette(BlockState[] states) {
        var result = new LinkedHashMap<BlockState, Integer>(); for (var state : states) result.put(state, result.size()); return result;
    }
    private static ResourceKey<Structure> key(String id) { return ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)); }
    private static ResourceKey<StructureSet> setKey(String id) { return ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse("retina:" + id)); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
