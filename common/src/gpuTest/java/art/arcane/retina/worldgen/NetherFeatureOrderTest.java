package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;

import java.lang.foreign.ValueLayout;
import java.util.*;

/** Loaded feature DAG order, shared-root membership and observable native replacement ordering. */
final class NetherFeatureOrderTest {
    static void check(RegistryAccess registry, TerrainProfileData exported) {
        var data = JsonParser.parseString(exported.json()).getAsJsonObject();
        var steps = FeatureSorter.buildFeaturesPerStep(exported.biomes(), b -> b.value().getGenerationSettings().features(), true);
        var roots = new ArrayList<PlacedFeature>();
        var rootSteps = new ArrayList<Integer>();
        for (int step = 0; step < steps.size(); step++) for (var root : steps.get(step).features()) {
            roots.add(root); rootSteps.add(step);
        }
        var placedRegistry = registry.lookupOrThrow(Registries.PLACED_FEATURE);
        var recipes = data.getAsJsonArray("decorations");
        int previous = -1;
        for (int id = 0; id < recipes.size(); id++) {
            var recipe = recipes.get(id).getAsJsonObject();
            int order = recipe.get("root_order").getAsInt(), step = recipe.get("generation_step").getAsInt();
            String name = recipe.get("root_source").getAsString();
            var root = placedRegistry.getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(name))).value();
            require(order >= previous && roots.get(order) == root && rootSteps.get(order) == step,
                    "recipe IDs follow Minecraft's complete loaded root order: " + name);
            previous = order;
            for (int biome = 0; biome < exported.biomes().size(); biome++) {
                var source = exported.biomes().get(biome).value().getGenerationSettings().features();
                boolean expected = step < source.size() && source.get(step).stream().anyMatch(h -> h.value() == root);
                var selected = data.getAsJsonArray("biomes").get(biome).getAsJsonObject().getAsJsonArray("decorations");
                require(selected.contains(new JsonPrimitive(id)) == expected, "shared root retains exact biome membership: " + name);
            }
        }
        require(recipes.size() > 20, "loaded Nether recipes exercised");

        var biomes = exported.biomes().subList(0, 2);
        var a = pillar(registry, "minecraft:basalt", "minecraft:netherrack");
        var b = pillar(registry, "minecraft:magma_block", "minecraft:basalt");
        var c = pillar(registry, "minecraft:gold_block", "minecraft:magma_block");
        var first = new BiomeGenerationSettings.PlainBuilder().addFeature(7, a).addFeature(7, c).build();
        var second = new BiomeGenerationSettings.PlainBuilder().addFeature(7, a).addFeature(7, b).addFeature(7, c).build();
        var fixture = export(registry, exported, biomes, List.of(first, second));
        require(ids(fixture.profiles, 0).equals(List.of(0, 2)) && ids(fixture.profiles, 1).equals(List.of(0, 1, 2)),
                "later biome inserts its prerequisite before a shared root");
        nativeReplay(registry, exported, fixture, false);
        nativeReplay(registry, exported, fixture, true);

        var ore = Holder.direct(new PlacedFeature(Holder.direct(new ScatteredOreFeature(new BlockMatchTest(Blocks.BASALT),
                Blocks.MAGMA_BLOCK.defaultBlockState(), 64, 0)), a.value().placement().subList(0, 2)));
        var withOre = export(registry, exported, biomes, List.of(first,
                new BiomeGenerationSettings.PlainBuilder().addFeature(7, a).addFeature(7, ore).addFeature(7, c).build()));
        require(withOre.recipes.size() == 3 && withOre.recipes.get(1).getAsJsonObject().getAsJsonObject("nether").get("kind").getAsString().equals("ore"),
                "ore joins its source position between dependent decoration roots");
        nativeReplay(registry, exported, withOre, false, true);
        nativeReplay(registry, exported, withOre, true, true);

        first = new BiomeGenerationSettings.PlainBuilder().addFeature(8, c).build();
        second = new BiomeGenerationSettings.PlainBuilder().addFeature(4, a).addFeature(8, b).addFeature(8, c).build();
        var staged = export(registry, exported, biomes, List.of(first, second));
        require(ids(staged.profiles, 0).equals(List.of(2)) && ids(staged.profiles, 1).equals(List.of(0, 1, 2)),
                "generation steps precede source encounter order");
        nativeReplay(registry, exported, staged, false);

        var glowstone = placedRegistry.getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("minecraft:glowstone")));
        var repeated = new BiomeGenerationSettings.PlainBuilder().addFeature(3, glowstone).addFeature(8, glowstone).build();
        var twice = export(registry, exported, biomes, List.of(repeated, repeated));
        require(twice.recipes.size() == 2 && ids(twice.profiles, 0).equals(List.of(0, 1)) && ids(twice.profiles, 1).equals(List.of(0, 1)),
                "one registered root in two generation steps is emitted twice");
        var one = twice.recipes.get(0).getAsJsonObject(); var two = twice.recipes.get(1).getAsJsonObject();
        require(one.get("source").equals(two.get("source")) && !one.get("placement_salt").equals(two.get("placement_salt")),
                "step-specific RNG streams preserve the resolvable source name");

        first = new BiomeGenerationSettings.PlainBuilder().addFeature(7, a).addFeature(7, b).build();
        second = new BiomeGenerationSettings.PlainBuilder().addFeature(7, b).addFeature(7, a).build();
        boolean failed = false;
        try { export(registry, exported, biomes, List.of(first, second)); }
        catch (IllegalStateException e) { failed = e.getMessage().contains("Feature order cycle found"); }
        require(failed, "an actual loaded feature cycle fails rather than silently reordering");
        crossAnchor(registry, exported);
        System.out.println("QA_EVT {\"event\":\"nether_feature_order\",\"status\":\"pass\",\"context\":{\"loaded_recipes\":"
                + recipes.size() + ",\"loaded_roots\":" + roots.size() + ",\"native_chunks\":54,\"fixture_cases\":11}}");
    }

    private record Export(JsonObject world, JsonArray profiles, JsonArray recipes, LinkedHashMap<BlockState, Integer> palette) {}

    private static Export export(RegistryAccess registry, TerrainProfileData source, List<Holder<Biome>> biomes, List<BiomeGenerationSettings> settings) {
        var world = JsonParser.parseString(source.json()).getAsJsonObject();
        var profiles = new JsonArray(); for (var biome : biomes) profiles.add(new JsonObject());
        var palette = new LinkedHashMap<BlockState, Integer>(); for (var state : source.materials()) palette.put(state, palette.size());
        var recipes = DecorationProfile.export(registry, biomes, profiles, palette, world,
                Collections.newSetFromMap(new IdentityHashMap<>()), b -> settings.get(biomes.indexOf(b)));
        return new Export(world, profiles, recipes, palette);
    }

    private static List<Integer> ids(JsonArray profiles, int biome) {
        return profiles.get(biome).getAsJsonObject().getAsJsonArray("decorations").asList().stream().map(JsonElement::getAsInt).toList();
    }

    private static Holder<PlacedFeature> pillar(RegistryAccess registry, String material, String replace) {
        var root = JsonParser.parseString("""
                {"feature":{"type":"minecraft:single_block_pillar",
                  "direction":"up","chance_to_continue":1,
                  "can_replace":{"type":"minecraft:height_range","min_inclusive":{"absolute":64},"max_inclusive":{"absolute":64}}},
                 "placement":[{"type":"minecraft:height_range","height":{"absolute":64}},
                  {"type":"minecraft:offset","x":8,"y":0,"z":8},
                  {"type":"minecraft:block_predicate_filter","predicate":{"type":"minecraft:matching_blocks","blocks":["minecraft:netherrack"]}}]}
                """).getAsJsonObject();
        var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(material)).defaultBlockState();
        root.getAsJsonObject("feature").add("block", BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow());
        var matches = new JsonArray(); matches.add(replace);
        root.getAsJsonArray("placement").get(2).getAsJsonObject().getAsJsonObject("predicate").add("blocks", matches);
        return Holder.direct(PlacedFeature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE), root).getOrThrow());
    }

    private static void nativeReplay(RegistryAccess registry, TerrainProfileData source, Export fixture, boolean wrongOrder) {
        nativeReplay(registry, source, fixture, wrongOrder, false);
    }

    private static void nativeReplay(RegistryAccess registry, TerrainProfileData source, Export fixture, boolean wrongOrder, boolean oreChain) {
        var profile = replayProfile(registry, source, fixture, wrongOrder, true);
        var states = profile.states;
        int transitioned = 0;
        for (var position : new int[][]{{0, 0}, {-1, -1}, {-33, 31}, {32, -32}, {31, 32}, {-32, -33}}) {
            var request = new TerrainRequest(42, position[0], position[1], 0, 256, 64, 48, .008f, profile.id);
            try (var chunk = NativeTerrain.instance().generate(request)) {
                for (int c = 0; c < 256; c++) {
                    var expected = c == 8 * 16 + 8 ? wrongOrder ? Blocks.MAGMA_BLOCK : Blocks.GOLD_BLOCK : Blocks.NETHERRACK;
                    int actual = Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (64 * 256L + c) * 2));
                    // Scattered ores may choose zero attempts. Every nonempty ore run includes its origin first.
                    if (oreChain && c == 8 * 16 + 8 && states[actual].is(Blocks.BASALT)) continue;
                    require(states[actual].is(expected), "native live placement order at " + Arrays.toString(position) + ":" + c + " wrongOrder=" + wrongOrder + ": " + states[actual]);
                    if (c == 8 * 16 + 8) transitioned++;
                }
            }
        }
        require(transitioned > 0, "dependent replacements must produce an observable final state");
    }

    private record ReplayProfile(int id, BlockState[] states) {}
    private static ReplayProfile replayProfile(RegistryAccess registry, TerrainProfileData source, Export fixture, boolean wrongOrder, boolean sourceOrder) {
        var data = fixture.world.deepCopy(); var recipes = fixture.recipes.deepCopy();
        var palette = new LinkedHashMap<>(fixture.palette);
        DecorationProfile.finishPlacements(recipes, palette, GenerationBounds.uniform(0, 256), 32);
        if (wrongOrder) {
            var b = recipes.get(1); recipes.set(1, recipes.get(2)); recipes.set(2, b);
            for (int i = 0; i < recipes.size(); i++) recipes.get(i).getAsJsonObject().addProperty("root_order", i);
        }
        data.addProperty("decoration_source_order", sourceOrder);
        data.add("decorations", recipes); data.add("ores", new JsonArray());
        for (var biome : data.getAsJsonArray("biomes")) {
            var selected = new JsonArray(); for (int i = 0; i < recipes.size(); i++) selected.add(i);
            var entry = biome.getAsJsonObject(); entry.add("decorations", selected);
            entry.add("ores", new JsonArray()); entry.add("carvers", new JsonArray()); entry.remove("cave_features");
        }
        var compiler = new RegistryGpuProgram(registry, 0, 128, 32);
        var density = compiler.new Program(); density.roots.add(density.constant(1)); density.roots.add(density.constant(128));
        var material = compiler.new Program(); material.roots.add(material.constant(data.get("stone").getAsInt() + 1));
        var gpu = data.getAsJsonObject("registry_program"); var programs = gpu.getAsJsonArray("programs");
        programs.set(1, density.finish()); programs.set(2, density.finish()); gpu.add("interpolations", new JsonArray());
        for (int i = 0; i < source.biomes().size(); i++) programs.set(3 + i, material.finish());
        DecorationProfile.materialFlags(data, palette);
        var serialized = new JsonArray(); var carveable = new JsonArray();
        for (var state : palette.keySet()) {
            serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()); carveable.add(false);
        }
        data.add("materials", serialized); data.add("carveable", carveable); data.add("lighting", LightingProfile.export(palette.keySet(), false));
        return new ReplayProfile(NativeTerrain.instance().registerProfile(data.toString()), palette.keySet().toArray(BlockState[]::new));
    }

    private static void crossAnchor(RegistryAccess registry, TerrainProfileData source) {
        for (boolean zAxis : new boolean[]{false, true}) {
            var roots = new ArrayList<Holder<PlacedFeature>>();
            for (int i = 0; i < 2; i++) {
                var definition = JsonParser.parseString("""
                        {"feature":{"type":"minecraft:simple_block","to_place":"minecraft:basalt"},
                         "placement":[{"type":"minecraft:height_range","height":{"absolute":64}},
                          {"type":"minecraft:offset","x":8,"y":0,"z":8}]}
                        """).getAsJsonObject();
                definition.getAsJsonObject("feature").add("to_place", net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.DIRECT_CODEC
                        .encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),
                                net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider.of(i == 0 ? Blocks.BASALT : Blocks.GOLD_BLOCK)).getOrThrow());
                definition.getAsJsonArray("placement").get(1).getAsJsonObject().addProperty(zAxis ? "z" : "x", i == 0 ? -1 : 15);
                roots.add(Holder.direct(PlacedFeature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE), definition).getOrThrow()));
            }
            var settings = new BiomeGenerationSettings.PlainBuilder().addFeature(3, roots.get(0)).addFeature(7, roots.get(1)).build();
            var fixture = export(registry, source, source.biomes().subList(0, 1), List.of(settings));
            for (boolean ordered : new boolean[]{true, false}) {
                var profile = replayProfile(registry, source, fixture, false, ordered);
                for (var position : new int[][]{{0, 0}, {-1, -1}, {-33, 31}, {32, -32}, {31, 32}, {-32, -33}}) {
                    var request = new TerrainRequest(42, position[0], position[1], 0, 256, 64, 48, .008f, profile.id);
                    try (var chunk = NativeTerrain.instance().generate(request)) {
                        for (int c = 0; c < 256; c++) {
                            var expected = c == (zAxis ? 15 * 16 + 8 : 8 * 16 + 15)
                                    ? ordered ? Blocks.GOLD_BLOCK : Blocks.BASALT : Blocks.NETHERRACK;
                            int actual = Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (64 * 256L + c) * 2));
                            require(profile.states[actual].is(expected), "cross-anchor source order " + Arrays.toString(position) + ":" + c
                                    + " zAxis=" + zAxis + " ordered=" + ordered + ": " + profile.states[actual]);
                        }
                    }
                }
            }
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
