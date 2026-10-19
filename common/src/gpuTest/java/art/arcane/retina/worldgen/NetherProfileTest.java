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
            var definition = new GenerationSource(source, dimension.value());
            var runtime = RetinaChunkGenerator.fromSource(definition);
            require(runtime.getMinY() == 0 && runtime.getGenDepth() == 128, "generator exposes source noise bounds");
            require(runtime.request(42, -33, 63).minY() == 0 && runtime.request(42, -33, 63).height() == 256,
                    "native requests retain full dimension storage, including above-roof builds");
            var description = definition.describe();
            require(description.get("ceiling").getAsBoolean() && !description.get("skylight").getAsBoolean()
                    && description.get("logical_height").getAsInt() == 128
                    && description.get("coordinate_scale").getAsDouble() == 8.0, "dimension metadata retained");
            require(RetinaGenerators.adapt(Level.NETHER, new LevelStem(dimension, source), source) == source,
                    "Nether activation remains deferred until implementation is complete");

            var data = TerrainProfileData.export(registry, (RetinaBiomeSource) runtime.getBiomeSource(), 0, 256, 42,
                    settings.value(), StructureProfile.Context.NONE, true, false, true);
            var json = JsonParser.parseString(data.json()).getAsJsonObject();
            require(json.getAsJsonObject("generation_bounds").equals(description.getAsJsonObject("bounds")), "shared bounds descriptor");
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
            for (var ore : json.getAsJsonArray("ores")) {
                var recipe = ore.getAsJsonObject();
                if (recipe.get("id").getAsString().equals("minecraft:ore_quartz_nether"))
                    require(recipe.getAsJsonObject("height").get("max").getAsInt() == 117, "loaded ore below-top anchor");
                var bands = ore.getAsJsonObject().getAsJsonArray("replacement_bands");
                require(bands.get(0).getAsJsonObject().get("min").getAsInt() == 0
                        && bands.get(bands.size() - 1).getAsJsonObject().get("max").getAsInt() == 255,
                        "replacement predicates still cover storage bounds");
            }

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
            require(new GenerationSource(reopened, dimension.value()).describe().equals(description), "save/reopen preserves profile context");
            if (List.of(args).contains("--rust")) {
                for (var exported : List.of(data, customData)) {
                    var registered = BiomeTerrainProfile.register(exported);
                    require(registered.json().equals(exported.json()), "native registration preserves exported profile");
                }
                System.out.println("QA_EVT {\"event\":\"nether_native_profile_registration\",\"status\":\"pass\"}");
            }
            Files.createDirectories(Path.of("build/nether"));
            Files.writeString(Path.of("build/nether/profile.json"), data.json());
            System.out.println("QA_EVT {\"event\":\"nether_profiles\",\"status\":\"pass\",\"context\":{\"biomes\":5,\"height_contexts\":5,\"placement_heights\":" + placements + "}}");
        }
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
