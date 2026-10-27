package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.Level;
import java.nio.file.*;
import java.util.*;

/** Benchmark profiles use the actual merged dimension, features and template resources. */
public final class NativeProfileExport {
    public static void main(String[] args) throws Exception {
        export(args,Boolean.getBoolean("retina.benchmark.definition") || !Boolean.getBoolean("retina.qa.legacyProfile"));
    }

    /** Publish a fresh retained test input; callers reopen its bytes for all sampling. */
    static Path definition(String prefix,Path pack) throws Exception {
        var build=Path.of("build");Files.createDirectories(build);
        var directory=Files.createTempDirectory(build,prefix);
        export(pack==null?new String[]{directory.toString()}:new String[]{directory.toString(),pack.toString()},true);
        return directory.resolve("retina.dat");
    }

    private static void export(String[] args,boolean binaryDefinition) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var stack = new ArrayList<PackResources>();
        stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        boolean packed = args.length > 1;
        long seed=Long.getLong("retina.benchmark.seed",123456789L);
        if (packed) {
            var location = new PackLocationInfo("benchmark-pack", Component.literal("Benchmark pack"), PackSource.DEFAULT, Optional.empty());
            stack.add((PackResources) new FilePackResources.FileResourcesSupplier(Path.of(args[1])).openMetadata(location));
        }
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, stack)) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var loaded = RegistryDataLoader.load(resources, builtin.listRegistries().toList(), RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            var registry = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtin.registries(), loaded.registries())).freeze();
            var context = new ArrayList<HolderLookup.RegistryLookup<?>>(builtin.listRegistries().toList());
            context.addAll(loaded.listRegistries().toList());
            var dimensions = RegistryDataLoader.load(resources, context, RegistryDataLoader.DIMENSION_REGISTRIES, Runnable::run).join();
            TerrainProfileData profile;
            LevelStem benchmarkStem;
            if (Boolean.getBoolean("retina.benchmark.nether")) {
                var original = dimensions.lookupOrThrow(Registries.LEVEL_STEM).get(LevelStem.NETHER)
                        .map(Holder::value).orElseGet(() -> registry.lookupOrThrow(Registries.WORLD_PRESET)
                                .getValueOrThrow(WorldPresets.NORMAL).createWorldDimensions().dimensions().get(LevelStem.NETHER));
                benchmarkStem=original;
                var definition = new GenerationSource(Level.NETHER, original.generator(), original.type().value());
                var type = definition.dimension();
                profile = TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(original.generator().getBiomeSource()),
                        type.minY(), type.height(), seed, definition.noise().generatorSettings().value(),
                        StructureProfile.Context.of(resources), true, type.hasSkyLight(), definition.enclosedTerrain());
                var json = JsonParser.parseString(profile.json()).getAsJsonObject();
                json.addProperty("dimension", Level.NETHER.identifier().toString());
                json.add("generation", definition.describe());
                profile = new TerrainProfileData(profile.seaLevel(), profile.materials(), profile.biomes(), json.toString());
            } else {
                // Preserve the established Overworld benchmark corpus and datapack behavior.
                var original = packed ? dimensions.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.OVERWORLD) : null;
                benchmarkStem=original!=null?original:registry.lookupOrThrow(Registries.WORLD_PRESET)
                        .getValueOrThrow(WorldPresets.NORMAL).createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
                var preset = JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/data/retina/worldgen/world_preset/gpu.json")))
                        .getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").getAsJsonObject("biome_source");
                var source = packed ? RetinaBiomeSource.fromRegistry(original.generator().getBiomeSource(), 128, .55f)
                        : RetinaBiomeSource.CODEC.codec().parse(registry.createSerializationContext(JsonOps.INSTANCE), preset).getOrThrow();
                var settings = packed && original.generator() instanceof NoiseBasedChunkGenerator noise ? noise.generatorSettings().value()
                        : registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
                profile = TerrainProfileData.export(registry, source, -64, 384, seed, settings, StructureProfile.Context.of(resources));
            }
            var data = JsonParser.parseString(profile.json()).getAsJsonObject();
            if (data.getAsJsonObject("structures").getAsJsonArray("definitions").isEmpty()) throw new AssertionError("benchmark must include structures");
            if (data.getAsJsonArray("decorations").isEmpty()) throw new AssertionError("benchmark must include decorations");
            var output = Path.of(args[0]); Files.createDirectories(output.toAbsolutePath().getParent());
            if(binaryDefinition) {
                String halo=System.getProperty("retina.benchmark.materialHalo");
                if(halo!=null) {
                    if(!halo.equals("true") && !halo.equals("false"))throw new IllegalArgumentException("benchmarkMaterialHalo must be true or false");
                    data.getAsJsonObject("registry_program").addProperty("material_halo",Boolean.parseBoolean(halo));
                }
                String composition=System.getProperty("retina.benchmark.densityComposition");
                if(composition!=null) {
                    if(!composition.equals("true") && !composition.equals("false"))throw new IllegalArgumentException("benchmarkDensityComposition must be true or false");
                    data.getAsJsonObject("registry_program").addProperty("density_composition",Boolean.parseBoolean(composition));
                }
                boolean nether=Boolean.getBoolean("retina.benchmark.nether");
                var parameters=WorldDefinitionCompiler.parameters(benchmarkStem.generator(),benchmarkStem.type().value());
                parameters.putInt("min_y",nether?0:-64);parameters.putInt("height",nether?256:384);
                parameters.putInt("generator_depth",data.get("generator_depth").getAsInt());
                var assembly=new WorldDefinitionAssembler(new net.minecraft.world.level.levelgen.WorldOptions(seed,true,false),
                        resources.listPacks().map(PackResources::packId).toList(),"Retina benchmark fixture");
                assembly.supported((nether?Level.NETHER:Level.OVERWORLD).identifier().toString(),parameters,data,null);
                var file=WorldDefinitionFile.writeNew(output,assembly.build());
                System.out.println("Benchmark definition: "+file+", seed="+seed+", bytes="+Files.size(file));
                return;
            }
            Files.writeString(output, profile.json());
            System.out.println("Benchmark profile: " + output + ", biomes=" + profile.biomes().size()
                    + ", structures=" + data.getAsJsonObject("structures").getAsJsonArray("definitions").size()
                    + ", decorations=" + data.getAsJsonArray("decorations").size());
        }
    }
}
