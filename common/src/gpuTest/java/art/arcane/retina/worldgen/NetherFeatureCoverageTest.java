package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.placement.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Inventory evidence for loaded graphs and deliberate, independently attributable export gaps. */
final class NetherFeatureCoverageTest {
    static void check(RegistryAccess registry, TerrainProfileData exported) throws Exception {
        var data = JsonParser.parseString(exported.json()).getAsJsonObject();
        var coverage = data.getAsJsonObject("decoration_coverage");
        var roots = coverage.getAsJsonArray("roots");
        require(roots.size() == 37 && coverage.getAsJsonObject("statuses").get("exported").getAsInt() == 37,
                "all loaded Nether roots have an export result");
        require(coverage.get("inventory_errors").getAsInt() == 0, "loaded source definitions expand without gaps");
        var expectedFeatures = Set.of("block_column", "delta_feature", "huge_fungus", "netherrack_replace_blobs", "ore", "overlay",
                "projected_random_patchy_square", "random_neighbor_spread", "scattered_ore", "simple_block", "single_block_pillar",
                "spring_feature", "stepped_column_cluster", "weighted_random_selector");
        require(strings(coverage.getAsJsonArray("feature_types")).equals(namespaced(expectedFeatures)), "complete loaded feature kind inventory");
        require(strings(coverage.getAsJsonArray("input_types")).containsAll(namespaced(Set.of("rule_based", "weighted", "randomized_int",
                "matching_block_tag", "environment_scan", "count_on_every_layer", "weighted_list", "very_biased_to_bottom"))),
                "providers, predicates, placements and nested distributions are retained");
        var sourceSteps = FeatureSorter.buildFeaturesPerStep(exported.biomes(), b -> b.value().getGenerationSettings().features(), true);
        var sourceRoots = new ArrayList<PlacedFeature>(); var steps = new ArrayList<Integer>();
        for (int step = 0; step < sourceSteps.size(); step++) for (var placed : sourceSteps.get(step).features()) {
            sourceRoots.add(placed); steps.add(step);
        }
        var assigned = new HashSet<Integer>();
        var registered = registry.lookupOrThrow(Registries.PLACED_FEATURE);
        for (int order = 0; order < roots.size(); order++) {
            var root = roots.get(order).getAsJsonObject(); int step = steps.get(order); var placed = sourceRoots.get(order);
            require(root.get("root_order").getAsInt() == order && root.get("generation_step").getAsInt() == step
                    && registered.getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(root.get("source").getAsString()))).value() == placed,
                    "coverage follows the actual loaded feature DAG");
            var members = new TreeSet<String>();
            for (var biome : exported.biomes()) {
                var features = biome.value().getGenerationSettings().features();
                if (step < features.size() && features.get(step).stream().anyMatch(h -> h.value() == placed))
                    members.add(biome.unwrapKey().orElseThrow().identifier().toString());
            }
            require(strings(root.getAsJsonArray("biomes")).equals(members), "exact root biome membership");
            require(root.get("references_inlined").getAsBoolean() && root.getAsJsonArray("omissions").isEmpty(), "loaded root fully inventoried and exported");
            for (var value : root.getAsJsonArray("recipes")) {
                int id = value.getAsInt(); require(assigned.add(id), "recipe assigned to one source root");
                var recipe = data.getAsJsonArray("decorations").get(id).getAsJsonObject();
                require(recipe.get("root_source").equals(root.get("source")) && recipe.get("root_order").getAsInt() == order, "reported recipe is the emitted root recipe");
            }
            for (var value : root.getAsJsonArray("typed_inputs")) {
                var input = value.getAsJsonObject(); var resolved = pointer(root.get("source_definition"), input.get("path").getAsString());
                require(resolved.getAsJsonObject().get(input.get("discriminator").getAsString()).equals(input.get("type")), "typed input locates its original source value");
            }
        }
        require(assigned.size() == 39 && assigned.size() == data.getAsJsonArray("decorations").size(), "every emitted recipe has coverage evidence");
        var pillar = roots.asList().stream().map(JsonElement::getAsJsonObject)
                .filter(r -> r.get("source").getAsString().equals("minecraft:basalt_pillar")).findFirst().orElseThrow();
        require(strings(pillar.getAsJsonArray("feature_types")).contains("minecraft:projected_random_patchy_square"),
                "pillar cap is inventoried even though Minecraft getSubFeatures omits it");

        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var before = new ArrayList<JsonElement>();
        for (var placed : sourceRoots) before.add(PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed).getOrThrow());
        var repeat = export(registry, exported, exported.biomes(), exported.biomes().stream().map(b -> b.value().getGenerationSettings()).toList());
        require(coverage.equals(JsonParser.parseString(repeat.coverage.toString())), "report is deterministic and independent of later height/material resolution");
        for (int i = 0; i < sourceRoots.size(); i++)
            require(before.get(i).equals(PlacedFeature.DIRECT_CODEC.encodeStart(ops, sourceRoots.get(i)).getOrThrow()), "inventory preserves the loaded source definition");

        var glowstone = registered.getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("minecraft:glowstone")));
        var omitted = placed(new VoidStartPlatformFeature());
        var partialA = placed(new SimpleRandomSelectorFeature(HolderSet.direct(glowstone, omitted)));
        var partialB = placed(new SimpleRandomSelectorFeature(HolderSet.direct(glowstone, omitted)));
        var composite = placed(new SequenceFeature(HolderSet.direct(placed(glowstone.value().feature().value()), omitted)));
        // Fixed placement is now supported. Keep this omission probe on an unsupported modifier.
        var unsupportedPlacement = Holder.direct(new PlacedFeature(glowstone.value().feature(),
                List.of(new RandomlySelectedPlacement(FixedPlacement.of(net.minecraft.core.BlockPos.ZERO)))));
        var first = new BiomeGenerationSettings.PlainBuilder().addFeature(0, omitted).addFeature(4, partialA)
                .addFeature(4, partialB).addFeature(7, composite).addFeature(8, unsupportedPlacement).build();
        var second = new BiomeGenerationSettings.PlainBuilder().addFeature(4, partialA).addFeature(4, partialB).build();
        var fixture = export(registry, exported, exported.biomes().subList(0, 2), List.of(first, second));
        var failures = fixture.coverage.getAsJsonArray("roots");
        require(failures.size() == 5 && fixture.recipes.size() == 2, "failed roots retained and only supported selector branches emitted");
        for (int index = 0; index < failures.size(); index++) {
            var root = failures.get(index).getAsJsonObject(); boolean partial = index == 1 || index == 2;
            require(root.get("status").getAsString().equals(partial ? "partial" : "omitted") && !root.getAsJsonArray("omissions").isEmpty(),
                    "each root independently retains its omissions, including repeated unsupported branches");
            require(root.get("references_inlined").getAsBoolean(), "unsupported graph is still completely inventoried");
        }
        require(failures.get(0).getAsJsonObject().get("generation_step").getAsInt() == 0, "unsupported roots outside vegetation are not skipped");
        require(strings(failures.get(3).getAsJsonObject().getAsJsonArray("omissions")).contains("vegetation_patch:nested_feature:VoidStartPlatformFeature"),
                "an unsupported nested feature prevents an incomplete composite from being emitted");
        require(strings(failures.get(4).getAsJsonObject().getAsJsonArray("omissions")).contains("placement:randomly_selected"), "unsupported modifier attributed to its root");
        require(strings(fixture.coverage.getAsJsonArray("input_types")).contains("minecraft:randomly_selected"), "unsupported modifier retained in source inventory");

        // Named holder sets require registry-backed serialization. Failure to inline
        // this audit graph must not prevent the working selector from being exported.
        var tagRegistry = new MappedRegistry<>(Registries.PLACED_FEATURE, Lifecycle.stable());
        var taggedGlowstone = tagRegistry.register(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("retina:coverage_glowstone")),
                glowstone.value(), RegistrationInfo.BUILT_IN);
        var tag = TagKey.create(Registries.PLACED_FEATURE, Identifier.parse("retina:coverage_choices"));
        tagRegistry.bindTags(Map.of(tag, List.of(taggedGlowstone))); tagRegistry.freeze();
        var taggedAccess = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
                registry.registries().filter(r -> !r.key().equals(Registries.PLACED_FEATURE)),
                java.util.stream.Stream.of(new RegistryAccess.RegistryEntry<>(Registries.PLACED_FEATURE, tagRegistry))));
        var taggedRoot = placed(new SimpleRandomSelectorFeature(tagRegistry.getOrThrow(tag)));
        var tagged = export(taggedAccess, exported, exported.biomes().subList(0, 1),
                List.of(new BiomeGenerationSettings.PlainBuilder().addFeature(7, taggedRoot).build()));
        var tagResult = tagged.coverage.getAsJsonArray("roots").get(0).getAsJsonObject();
        require(tagged.recipes.size() == 1 && tagResult.get("status").getAsString().equals("exported"), "inventory limitations do not gate a working adapter");
        require(!tagResult.get("references_inlined").getAsBoolean() && !tagResult.getAsJsonArray("inventory_errors").isEmpty()
                && tagResult.has("source_definition"), "incomplete expansion is explicit and keeps the registry-backed source definition");

        Files.createDirectories(Path.of("build/nether"));
        Files.writeString(Path.of("build/nether/feature-coverage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(coverage));
        Files.writeString(Path.of("build/nether/feature-coverage-omissions.json"), new GsonBuilder().setPrettyPrinting().create().toJson(fixture.coverage));
        Files.writeString(Path.of("build/nether/feature-coverage-tag.json"), new GsonBuilder().setPrettyPrinting().create().toJson(tagged.coverage));
        System.out.println("QA_EVT {\"event\":\"nether_feature_coverage\",\"status\":\"pass\",\"context\":{\"roots\":37,\"recipes\":39,\"feature_types\":14,\"omission_roots\":5,\"inventory_limitation_fixtures\":1}}");
    }

    private record Export(JsonObject coverage, JsonArray recipes) {}

    private static Export export(RegistryAccess registry, TerrainProfileData source, List<Holder<Biome>> biomes, List<BiomeGenerationSettings> settings) {
        var world = JsonParser.parseString(source.json()).getAsJsonObject();
        var profiles = new JsonArray(); for (var ignored : biomes) profiles.add(new JsonObject());
        var palette = new LinkedHashMap<BlockState, Integer>(); for (var state : source.materials()) palette.put(state, palette.size());
        var recipes = DecorationProfile.export(registry, biomes, profiles, palette, world,
                Collections.newSetFromMap(new IdentityHashMap<>()), b -> settings.get(biomes.indexOf(b)));
        return new Export(world.getAsJsonObject("decoration_coverage"), recipes);
    }

    private static Holder<PlacedFeature> placed(Feature feature) { return Holder.direct(new PlacedFeature(Holder.direct(feature), List.of())); }
    private static Set<String> strings(JsonArray array) { var result = new TreeSet<String>(); for (var value : array) result.add(value.getAsString()); return result; }
    private static Set<String> namespaced(Set<String> names) { var result = new TreeSet<String>(); for (String name : names) result.add("minecraft:" + name); return result; }
    private static JsonElement pointer(JsonElement value, String path) {
        if (path.isEmpty()) return value;
        for (String segment : path.substring(1).split("/")) {
            String key = segment.replace("~1", "/").replace("~0", "~");
            value = value.isJsonArray() ? value.getAsJsonArray().get(Integer.parseInt(key)) : value.getAsJsonObject().get(key);
        }
        return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
