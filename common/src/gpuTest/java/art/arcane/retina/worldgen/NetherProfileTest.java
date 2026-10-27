package art.arcane.retina.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Loaded Nether settings, plus Minecraft reference contexts for non-matching vertical ranges. */
public final class NetherProfileTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NativeRegistryLoadingTest.registerCodecs();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
            var biomes = MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            var source = new NoiseBasedChunkGenerator(biomes, settings);
            var dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER);
            var ops = registry.createSerializationContext(JsonOps.INSTANCE);
            var saved = ChunkGenerator.CODEC.encodeStart(ops, source).getOrThrow();
            var savedType = DimensionType.DIRECT_CODEC.encodeStart(ops, dimension.value()).getOrThrow();
            var definition = new GenerationSource(Level.NETHER, source, dimension.value());
            var runtime = RetinaChunkGenerator.fromSource(definition);
            require(runtime.getMinY() == 0 && runtime.getGenDepth() == 128, "generator exposes source noise bounds");
            var description = definition.describe();
            require(description.get("ceiling").getAsBoolean() && !description.get("skylight").getAsBoolean()
                    && description.get("logical_height").getAsInt() == 128
                    && description.get("coordinate_scale").getAsDouble() == 8.0, "dimension metadata retained");
            require(RetinaGenerators.adapt(Level.NETHER, new LevelStem(dimension, source), source) instanceof RetinaChunkGenerator,
                    "Nether activates automatically");

            var data = TerrainProfileData.export(registry, (RetinaBiomeSource) runtime.getBiomeSource(), 0, 256, 42,
                    settings.value(), StructureProfile.Context.NONE, true, false, true);
            var json = JsonParser.parseString(data.json()).getAsJsonObject();
            NetherFeatureCoverageTest.check(registry, data);
            require(json.getAsJsonObject("generation_bounds").equals(description.getAsJsonObject("bounds")), "shared bounds descriptor");
            require(json.get("generator_depth").getAsInt()==source.getGenDepth(),"feature limits retain original generator depth");
            require(data.biomes().size() == 5 && new HashSet<>(data.biomes()).equals(biomes.possibleBiomes()), "exact five source biomes");
            require(data.materials()[json.get("stone").getAsInt()].is(Blocks.NETHERRACK), "source solid material");
            require(data.materials()[json.get("water").getAsInt()].is(Blocks.LAVA) && data.seaLevel() == 32, "source lava and sea level");
            require(json.get("geology_min_y").getAsInt() == 0 && json.get("geology_height").getAsInt() == 128,
                    "geology uses generator height context");
            for (int i = 0; i < data.biomes().size(); i++) {
                var nodes = json.getAsJsonObject("registry_program").getAsJsonArray("programs")
                        .get(i + 3).getAsJsonObject().getAsJsonArray("nodes");
                require(nodes.asList().stream().anyMatch(node -> {
                    var n = node.getAsJsonObject();
                    return n.get("op").getAsInt() == 48 && n.getAsJsonArray("p").get(0).getAsInt() == 122
                            && n.getAsJsonArray("p").get(1).getAsInt() == 127;
                }), "loaded GPU bedrock roof gradient ends at noise ceiling, not storage ceiling");
            }
            for (var biome : json.getAsJsonArray("biomes")) {
                for (var carver : biome.getAsJsonObject().getAsJsonArray("carvers")) {
                    var range = carver.getAsJsonObject().getAsJsonObject("height");
                    require(range.get("min").getAsInt() == 0 && range.get("max").getAsInt() == 126,
                            "loaded Nether carver resolves below_top against generator context");
                }
            }
            int placements = checkResolvedPlacements(json.getAsJsonArray("decorations"));
            require(placements > 0, "loaded Nether placement heights exercised");
            require(json.getAsJsonArray("ores").isEmpty(), "ordered Nether ores do not also run in the earlier geology pass");
            int ores = 0;
            for (var value : json.getAsJsonArray("decorations")) {
                var recipe = value.getAsJsonObject();
                if (!recipe.has("nether") || !recipe.getAsJsonObject("nether").get("kind").getAsString().equals("ore")) continue;
                ores++;
                if (recipe.get("source").getAsString().equals("minecraft:ore_quartz_nether")) {
                    var range = recipe.getAsJsonArray("placement").asList().stream().map(JsonElement::getAsJsonObject)
                            .filter(p -> p.get("type").getAsString().equals("height_range")).findFirst().orElseThrow();
                    require(range.getAsJsonObject("height").getAsJsonObject("max_inclusive").get("absolute").getAsInt() == 117,
                            "ordered ore below-top anchor retains generator context");
                }
            }
            require(ores == 10, "all ten loaded Nether ore/replacement roots join decoration order");

            checkContext(registry, source, 0, 256);
            checkContext(registry, source, 32, 160);
            checkContext(registry, source, -64, 320);
            checkContext(registry, source, 256, 64);
            // Inline settings emulate effective datapack overrides, without relying on the NETHER registry key.
            var customJson = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings.value()).getOrThrow().getAsJsonObject();
            customJson.getAsJsonObject("noise").addProperty("min_y", 64);
            customJson.getAsJsonObject("noise").addProperty("height", 192);
            customJson.addProperty("sea_level", 91);
            var custom = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, customJson).getOrThrow();
            var fixed = new FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.WARPED_FOREST));
            var customized = new NoiseBasedChunkGenerator(fixed, Holder.direct(custom));
            var customTypeJson = savedType.getAsJsonObject().deepCopy();
            customTypeJson.addProperty("has_ceiling", false);
            customTypeJson.addProperty("coordinate_scale", 4.0);
            customTypeJson.addProperty("logical_height", 192);
            var customType = DimensionType.DIRECT_CODEC.parse(ops, customTypeJson).getOrThrow();
            var customRuntime = (RetinaChunkGenerator) RetinaGenerators.adapt(Level.NETHER,
                    new LevelStem(Holder.direct(customType), customized), customized);
            require(customRuntime.selectedSource().enclosedTerrain(),
                    "inline Nether settings retain Nether terrain semantics without a ceiling flag");
            require(!customRuntime.selectedSource().describe().get("ceiling").getAsBoolean()
                    && customRuntime.selectedSource().describe().get("coordinate_scale").getAsDouble() == 4.0
                    && customRuntime.selectedSource().describe().get("logical_height").getAsInt() == 192,
                    "custom dimension gameplay attributes remain independent of terrain semantics");
            require(DimensionType.DIRECT_CODEC.encodeStart(ops, customType).getOrThrow().equals(customTypeJson),
                    "compatible dimension override is not rewritten");
            checkContext(registry, customized, 96, 128);
            var customData = TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(fixed), 96, 128, 42,
                    custom, StructureProfile.Context.NONE, true, false, true);
            var customProfile = JsonParser.parseString(customData.json()).getAsJsonObject();
            require(customData.biomes().size() == 1 && new HashSet<>(customData.biomes()).equals(fixed.possibleBiomes()), "fixed source stays fixed");
            require(customData.seaLevel() == 91 && customProfile.get("geology_min_y").getAsInt() == 96
                    && customProfile.get("geology_height").getAsInt() == 128, "custom effective settings exported");
            require(ChunkGenerator.CODEC.encodeStart(ops, source).getOrThrow().equals(saved), "original generator serialization unchanged");
            require(DimensionType.DIRECT_CODEC.encodeStart(ops, dimension.value()).getOrThrow().equals(savedType), "dimension attributes unchanged");
            var reopened = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            require(new GenerationSource(Level.NETHER, reopened, dimension.value()).describe().equals(description), "save/reopen preserves profile context");
            if (List.of(args).contains("--rust")) {
                var parameters=WorldDefinitionCompiler.parameters(customized,customType);
                parameters.putInt("min_y",96);parameters.putInt("height",128);
                var assembly=new WorldDefinitionAssembler(new WorldOptions(42,true,false),List.of(),"Cropped Nether fixture");
                assembly.supported("minecraft:the_nether",parameters,customData,null);
                try(var normal=DefinitionFixture.create(definition,data,42);
                    var cropped=DefinitionFixture.create(assembly.build(),"minecraft:the_nether")) {
                    checkSaved(registry,normal,data,0,256);
                    checkSaved(registry,cropped,customData,96,128);
                }
                var document=WorldDefinitionCompiler.compile(registry,new WorldOptions(42,true,false),
                        java.util.Map.of(LevelStem.NETHER,new LevelStem(Holder.direct(customType),customized)),
                        StructureProfile.Context.NONE,List.of("vanilla"),"Customized Nether runtime fixture");
                try(var file=DefinitionFixture.create(document,"minecraft:the_nether")) {
                    var dim=file.savedDimension();dim.bindDimensionType(customType);
                    var boundRuntime=RetinaChunkGenerator.fromDefinition(dim,registry);
                    try {
                        boundRuntime.bindWorld(net.minecraft.world.level.chunk.PalettedContainerFactory.create(registry));
                        var bound=JsonParser.parseString(boundRuntime.profile().diagnosticJson()).getAsJsonObject();
                        require(bound.get("dimension").getAsString().equals("minecraft:the_nether")
                                &&bound.get("ordered_decorations").getAsBoolean(),"saved customized Nether retains identity and ordering");
                        require(boundRuntime.previewCache().dimension().equals(Level.NETHER),"saved preview retains actual dimension");
                        require(boundRuntime.request(-33,63).seed()==42,"saved seed owns runtime requests");
                    } finally {boundRuntime.closePreviews();}
                }
                System.out.println("QA_EVT {\"event\":\"nether_native_file_binding\",\"status\":\"pass\"}");
            }
            Files.createDirectories(Path.of("build/nether"));
            Files.writeString(Path.of("build/nether/profile.json"), data.json());
            System.out.println("QA_EVT {\"event\":\"nether_profiles\",\"status\":\"pass\",\"context\":{\"biomes\":5,\"height_contexts\":5,\"placement_heights\":" + placements + "}}");
        }
    }

    private static void checkSaved(RegistryAccess registry,DefinitionFixture saved,TerrainProfileData exported,int minY,int height) {
        var bound=saved.savedDimension().bind(registry);
        require(java.util.Arrays.equals(bound.materials(),exported.materials())&&bound.biomes().equals(exported.biomes())
                &&bound.seaLevel()==exported.seaLevel(),"file binding preserves exported palettes and sea level");
        var request=saved.request(-33,63);
        require(request.seed()==42&&request.minY()==minY&&request.height()==height,"native geometry and seed come from the file");
        var model=JsonParser.parseString(saved.diagnosticProfile()).getAsJsonObject();
        var original=JsonParser.parseString(exported.json()).getAsJsonObject();
        for(String key:List.of("generator_depth","generation_bounds","ordered_decorations"))
            require(model.get(key).equals(original.get(key)),"saved Nether generation field: "+key);
    }

    private static void checkContext(RegistryAccess registry, NoiseBasedChunkGenerator source, int minY, int height) {
        var accessor = LevelHeightAccessor.create(minY, height);
        var expected = new WorldGenerationContext(source, accessor);
        var settings = source.generatorSettings().value();
        var bounds = GenerationBounds.noise(settings.noiseSettings(), minY, height);
        require(bounds.minY() == expected.getMinGenY() && bounds.height() == expected.getGenDepth(), "Minecraft anchor context");
        var clipped = settings.noiseSettings().clampToHeightAccessor(accessor);
        require(bounds.terrainMinY() == clipped.minY() && bounds.terrainHeight() == Math.max(0, clipped.height()), "Minecraft density clipping");
        var compiler = new RegistryGpuProgram(registry, bounds.minY(), bounds.height(), settings.seaLevel());
        var program = compiler.new Program();
        for (var anchor : List.of(VerticalAnchor.aboveBottom(3), VerticalAnchor.belowTop(7),
                VerticalAnchor.absolute(19), VerticalAnchor.relativeToSeaLevel(-2))) {
            int reference = anchor.resolveY(expected);
            require(program.anchor(anchor) == reference, "GPU surface anchor agrees with Minecraft");
            var encoded = VerticalAnchor.CODEC.encodeStart(JsonOps.INSTANCE, anchor).getOrThrow();
            var constant = new JsonObject();
            constant.addProperty("type", "minecraft:constant"); constant.add("value", encoded);
            require(GeologyProfile.range(constant, bounds.minY(), bounds.height(), settings.seaLevel()).get("min").getAsInt() == reference,
                    "geology anchor agrees with Minecraft");
            bounds.resolveHeightProvider(constant, settings.seaLevel());
            require(constant.getAsJsonObject("value").get("absolute").getAsInt() == reference,
                    "exported feature/structure anchor agrees with Minecraft");
        }
        for (String provider : List.of(
                "{\"type\":\"minecraft:uniform\",\"min_inclusive\":{\"above_bottom\":2},\"max_inclusive\":{\"below_top\":5}}",
                "{\"type\":\"minecraft:trapezoid\",\"min_inclusive\":{\"relative_to_sea_level\":-12},\"max_inclusive\":{\"below_top\":9},\"plateau\":4}")) {
            var encoded = JsonParser.parseString(provider);
            var reference = HeightProvider.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            bounds.resolveHeightProvider(encoded, settings.seaLevel());
            var actual = HeightProvider.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            // Evaluate the resolved provider against storage bounds: native requests retain those bounds.
            var storage = new WorldGenerationContext(new RetinaChunkGenerator(RetinaBiomeSource.fromSelected(source.getBiomeSource()),
                    minY, height, 64, 48, .008f, "mca"), accessor);
            var a = RandomSource.create(923);
            var b = RandomSource.create(923);
            for (int i = 0; i < 64; i++) require(reference.sample(a, expected) == actual.sample(b, storage), "height distribution and RNG preserved");
        }
    }

    private static int checkResolvedPlacements(JsonElement value) {
        int count = 0;
        if (value.isJsonArray()) for (var child : value.getAsJsonArray()) count += checkResolvedPlacements(child);
        else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("height_range")) {
                String height = object.get("height").toString();
                require(!height.contains("below_top") && !height.contains("above_bottom") && !height.contains("relative_to_sea_level"),
                        "native placement cannot reinterpret generator anchors using storage height");
                count++;
            }
            for (var entry : object.entrySet()) count += checkResolvedPlacements(entry.getValue());
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
