package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FlatProfileTest {
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
            check(registry, true);
            FlatIntegrationTest.check(registry, resources);
            FlatFeatureTest.check(registry);
        }
    }

    static void check(RegistryAccess registry, boolean nativeChecks) throws Exception {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var type = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        int count = 0;
        for (var holder : registry.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET).listElements().toList()) {
            var settings = holder.value().settings();
            var encoded = FlatLevelGeneratorSettings.CODEC.encodeStart(ops, settings).getOrThrow();
            var source = new FlatLevelSource(settings);
            var stem = new LevelStem(type, source);
            var retina = (RetinaChunkGenerator) RetinaGenerators.adapt(Level.OVERWORLD, stem, source);
            require(retina.selectedSource().flat(), "flat adapter selected");
            var data = FlatTerrainProfile.export(registry, retina.selectedSource(), 42, StructureProfile.Context.NONE, true);
            var json = JsonParser.parseString(data.json()).getAsJsonObject();
            require(data.biomes().equals(List.of(settings.getBiome())), "flat biome pool remains exact");
            require(!json.has("registry_program"), "flat has no density program");
            require(json.getAsJsonArray("cave_noises").isEmpty(), "flat has no borrowed cave fields");
            require(FlatLevelGeneratorSettings.CODEC.encodeStart(ops, settings).getOrThrow().equals(encoded), "export leaves source settings intact");
            var saved = ChunkGenerator.CODEC.encodeStart(ops, stem.generator()).getOrThrow();
            var reopened = ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            require(reopened instanceof FlatLevelSource, "save retains Minecraft's flat definition");
            require(RetinaGenerators.adapt(Level.OVERWORLD, new LevelStem(type, reopened), reopened) instanceof RetinaChunkGenerator, "flat reactivates on reopen");
            String name = holder.key().identifier().getPath();
            if (name.equals("overworld")) require(json.getAsJsonArray("decorations").asList().stream()
                    .anyMatch(v -> v.getAsJsonObject().get("kind").getAsString().equals("flat_lake")), "flat lakes are native recipes");
            if (name.equals("the_void")) require(json.getAsJsonArray("decorations").asList().stream()
                    .anyMatch(v -> v.getAsJsonObject().get("kind").getAsString().equals("void_platform")), "void start platform is native");
            if (nativeChecks) {
                // Load the complete preset, including its optional features, before the bare reference.
                try(var definition=DefinitionFixture.create(retina.selectedSource(),data,42)) {
                    require(definition.request(0,0).seed()==42,"saved flat seed");
                }
                var bare = encoded.deepCopy().getAsJsonObject();
                bare.addProperty("features", false);
                bare.addProperty("lakes", false);
                bare.add("structure_overrides", new com.google.gson.JsonArray());
                var bareSettings = FlatLevelGeneratorSettings.CODEC.parse(ops, bare).getOrThrow();
                checkNativeLayers(registry, new GenerationSource(new FlatLevelSource(bareSettings), type.value()), name);
            }
            count++;
        }
        if (nativeChecks) checkCustomLayers(registry, type.value());
        System.out.println("QA_EVT {\"event\":\"flat_profiles\",\"status\":\"pass\",\"context\":{\"native\":" + nativeChecks + ",\"presets\":" + count + "}}");
    }

    private static void checkCustomLayers(RegistryAccess registry, net.minecraft.world.level.dimension.DimensionType type) throws Exception {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        int custom=0;
        for (String layers : List.of("[]", "[{\"height\":1,\"block\":\"minecraft:air\"}]",
                "[{\"height\":1,\"block\":\"minecraft:bedrock\"},{\"height\":2,\"block\":\"minecraft:air\"},{\"height\":1,\"block\":\"minecraft:glass\"},{\"height\":3,\"block\":\"minecraft:water\"},{\"height\":1,\"block\":\"minecraft:short_grass\"}]")) {
            var encoded = JsonParser.parseString("{\"layers\":" + layers + ",\"biome\":\"minecraft:plains\",\"structure_overrides\":[]}");
            var settings = FlatLevelGeneratorSettings.CODEC.parse(ops, encoded).getOrThrow();
            checkNativeLayers(registry, new GenerationSource(new FlatLevelSource(settings), type), "custom-"+(custom++));
        }
    }

    private static void checkNativeLayers(RegistryAccess registry, GenerationSource source, String name) throws Exception {
        var data = FlatTerrainProfile.export(registry, source, 42, StructureProfile.Context.NONE, true);
        try(var definition=DefinitionFixture.create(source,data,42)) {
            var profile = definition.savedDimension().bind(registry);
            var states = profile.materials();
            var expected = new ArrayList<BlockState>();
            for (var layer : ((FlatLevelSource) source.generator()).settings().getLayersInfo())
                for (int i = 0; i < layer.getHeight() && expected.size() < source.dimension().height(); i++) expected.add(layer.getBlockState());
            while (expected.size() < source.dimension().height()) expected.add(Blocks.AIR.defaultBlockState());
            var request = definition.request(-33,32);
            try (var chunk = NativeTerrain.instance().generate(request)) {
                for (int y = 0; y < expected.size(); y++) for (int column = 0; column < 256; column++) {
                    int id = Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (y * 256L + column) * 2));
                    require(states[id].equals(expected.get(y)), name + " exact configured native layer at " + y);
                }
            }
            var base = JsonParser.parseString(data.json()).getAsJsonObject().getAsJsonObject("flat").getAsJsonArray("base_layers");
            var column = NativeTerrain.instance().column(request, 127);
            var ops = registry.createSerializationContext(JsonOps.INSTANCE);
            var original = ((FlatLevelSource) source.generator()).settings();
            var referenceSettings = FlatLevelGeneratorSettings.CODEC.parse(ops,
                    FlatLevelGeneratorSettings.CODEC.encodeStart(ops, original).getOrThrow()).getOrThrow();
            referenceSettings.adjustGenerationSettings(referenceSettings.getBiome());
            var reference = new FlatLevelSource(referenceSettings);
            var bounds = net.minecraft.world.level.LevelHeightAccessor.create(request.minY(), request.height());
            var vanilla = new net.minecraft.world.level.chunk.ProtoChunk(new net.minecraft.world.level.ChunkPos(0, 0),
                    net.minecraft.world.level.chunk.UpgradeData.EMPTY, bounds,
                    net.minecraft.world.level.chunk.PalettedContainerFactory.create(registry), null);
            reference.buildTerrain(vanilla, net.minecraft.world.level.levelgen.blending.Blender.empty(), null,
                    null, null, null, java.util.Set.of(original.getBiome())).join();
            for (int y = 0; y < column.length; y++) require(Short.toUnsignedInt(column[y]) == (y < base.size() ? base.get(y).getAsInt() : 0), "native base column matches deferred-layer semantics");
            for (int y = 0; y < column.length; y++) require(states[Short.toUnsignedInt(column[y])]
                    .equals(vanilla.getBlockState(new net.minecraft.core.BlockPos(0, request.minY() + y, 0))),
                    "base terrain matches Minecraft FlatLevelSource exactly");
            for (short biome : NativeTerrain.instance().sampleBiomes(request)) require(biome == 0, "fixed biome at every depth");
            var directory=Path.of("build/flat-profiles",name);Files.createDirectories(directory);
            Files.copy(definition.file(),directory.resolve("retina.dat"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
