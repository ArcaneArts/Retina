package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Real loaded registries can be projected without loading Rust or starting a GPU. */
public final class RegistryProfileExportTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        boolean rust = Arrays.asList(args).contains("--rust");
        var noRust = Files.createTempDirectory("retina-registry-no-rust-");
        String originalPath = System.getProperty("retina.native.path");
        if (!rust) System.setProperty("retina.native.path", noRust.resolve("absent-native-library").toString());
        try {
            var stack = new ArrayList<PackResources>();
            stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
            int packs = 0;
            for (String argument : args) if (!argument.equals("--rust")) {
                var location = new PackLocationInfo("registry-export-" + packs++, Component.literal("Export test"),
                        PackSource.DEFAULT, Optional.empty());
                stack.add((PackResources) new FilePackResources.FileResourcesSupplier(Path.of(argument)).openMetadata(location));
            }
            try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, stack)) {
                var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
                net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
                var world = RegistryDataLoader.load(resources, builtin.listRegistries().toList(),
                        RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
                var registry = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(
                        builtin.registries(), world.registries())).freeze();
                NoiseBasedChunkGenerator generator;
                if (packs == 0) {
                    // Vanilla supplies its Overworld via world presets, not a
                    // datapack dimension resource. Use its registered preset source.
                    generator = new NoiseBasedChunkGenerator(net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(
                            registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                                    .getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                            registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD));
                } else {
                    var context = new ArrayList<HolderLookup.RegistryLookup<?>>(builtin.listRegistries().toList());
                    context.addAll(world.listRegistries().toList());
                    var dimensions = RegistryDataLoader.load(resources, context, RegistryDataLoader.DIMENSION_REGISTRIES, Runnable::run).join();
                    generator = (NoiseBasedChunkGenerator) dimensions.lookupOrThrow(Registries.LEVEL_STEM)
                            .getValueOrThrow(LevelStem.OVERWORLD).generator();
                }
                var source = RetinaBiomeSource.fromRegistry(generator.getBiomeSource(), 128, .55f);
                var data = TerrainProfileData.export(registry, source, -64, 384, Long.MIN_VALUE + 123456789,
                        generator.generatorSettings().value(), StructureProfile.Context.of(resources));
                check(data);
                if (packs > 0) require(data.biomes().stream().anyMatch(b -> b.unwrapKey().orElseThrow()
                        .identifier().getNamespace().equals("terralith")), "Terralith biomes survive shared export");
                var destination = Path.of("build/registry-export-" + (rust ? "rust" : "independent") + ".json");
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, data.json());
                if (rust) {
                    var model=JsonParser.parseString(data.json()).getAsJsonObject();
                    try(var saved=RegistryDefinitionFixture.create(model,Long.MIN_VALUE+123456789);
                        var cold=DefinitionFixture.open(saved.file(),"minecraft:overworld",2)) {
                        var registered=saved.savedDimension().bind(registry);
                        require(registered.seaLevel()==data.seaLevel()
                                && Arrays.equals(registered.materials(),data.materials()) && registered.biomes().equals(data.biomes()),
                                "saved adapter preserves exact projection and ID ordering");
                        var columns=NativeTerrain.instance().sampleColumns(saved.request(-33,63));
                        var reopened=NativeTerrain.instance().sampleColumns(cold.request(-33,63));
                        require(Arrays.equals(columns.heights(),reopened.heights())&&Arrays.equals(columns.packed(),reopened.packed())
                                &&Arrays.equals(columns.materials(),reopened.materials()),"independent reopen preserves saved columns");
                        for(int i=0;i<256;i++) {
                            require(columns.biome(i)<data.biomes().size(),"Rust receives valid saved biome indices");
                            require(columns.heights()[i]>=-64&&columns.heights()[i]<=320,"Rust samples saved registry terrain");
                        }
                    }
                } else {
                    // Positive control: an actual Rust initialization must fail in
                    // this JVM. Export above succeeded despite the missing library.
                    try {
                        NativeTerrain.instance();
                        throw new AssertionError("Rust unexpectedly initialized without its library");
                    } catch (ExceptionInInitializerError expected) {
                        require(expected.getCause() instanceof IllegalArgumentException, "actual native library lookup failure");
                    }
                }
                System.out.println("QA_EVT {\"event\":\"registry_profile_" + (rust ? "saved_file_binding" : "independent_export")
                        + "\",\"status\":\"pass\",\"context\":{\"packs\":" + packs + ",\"biomes\":"
                        + data.biomes().size() + ",\"materials\":" + data.materials().length + "}}");
            }
        } finally {
            if (originalPath == null) System.clearProperty("retina.native.path");
            else System.setProperty("retina.native.path", originalPath);
            Files.delete(noRust);
        }
    }

    private static void check(TerrainProfileData data) {
        var json = JsonParser.parseString(data.json()).getAsJsonObject();
        var materials = data.materials();
        require(materials.length == json.getAsJsonArray("materials").size(), "complete ordered material palette");
        require(data.biomes().size() == json.getAsJsonArray("biomes").size(), "complete ordered biome palette");
        require(data.seaLevel() == json.get("sea_level").getAsInt(), "sea level");
        for (int id = 0; id < materials.length; id++) {
            require(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, materials[id]).getOrThrow()
                    .equals(json.getAsJsonArray("materials").get(id)), "exact state properties at material " + id);
            int mask = 0;
            for (var type : Heightmap.Types.values()) if (type.isOpaque().test(materials[id])) mask |= 1 << type.ordinal();
            require(mask == json.getAsJsonArray("heightmap_masks").get(id).getAsInt(), "registered heightmap predicates");
        }
        for (int id = 0; id < data.biomes().size(); id++) require(data.biomes().get(id).unwrapKey().orElseThrow()
                .identifier().toString().equals(json.getAsJsonArray("biomes").get(id).getAsJsonObject().get("id").getAsString()),
                "biome ordering");
        require(!json.getAsJsonArray("decorations").isEmpty() && !json.getAsJsonArray("ores").isEmpty(), "registered feature/ore recipes");
        require(!json.getAsJsonArray("climate_targets").isEmpty(), "actual climate intervals");
        require(json.getAsJsonObject("registry_program").get("density_composition").getAsBoolean(), "density programs preserved");
        require(!json.getAsJsonObject("structures").getAsJsonArray("definitions").isEmpty(), "structure recipes preserved");
        require(json.has("lighting"), "lighting predicates preserved");
        if (data.biomes().stream().anyMatch(b -> b.unwrapKey().orElseThrow().identifier().toString()
                .equals("retina_test:export_probe"))) {
            var noise = json.getAsJsonArray("noises").get(0).getAsJsonObject();
            require(noise.get("source").getAsString().equals("minecraft:temperature")
                    && noise.get("frequency").getAsDouble() == .001953125
                    && noise.getAsJsonArray("modifiers").equals(JsonParser.parseString("[1.0,0.25,0.5]")),
                    "supplemental pack noise override survives alongside its new biome");
        }
        var original = materials[0];
        materials[0] = Blocks.DIAMOND_BLOCK.defaultBlockState();
        require(data.materials()[0].equals(original), "backend cannot mutate shared material ordering");
        try {
            data.biomes().clear();
            throw new AssertionError("mutable shared biome ordering");
        } catch (UnsupportedOperationException expected) {
            // Snapshot is immutable across backend bindings.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
