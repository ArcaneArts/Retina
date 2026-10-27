package art.arcane.retina.worldgen;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.biome.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

/** Real world-preset loading must not dereference another registry while it is pending. */
public final class NativeRegistryLoadingTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerCodecs();

        var pending = new MappedRegistry<MultiNoiseBiomeSourceParameterList>(
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Lifecycle.stable());
        var holder = pending.createRegistrationLookup().getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var reference = MultiNoiseBiomeSource.createFromPreset(holder);
        // Deterministically reproduce the exact load-order condition in the client log.
        var deferred = new RetinaBiomeSource(List.of(), 128, .55F, Optional.of(reference), true);
        var imported = RetinaBiomeSource.fromRegistry(reference, 128, .55F);
        require(!holder.isBound(), "constructing Retina must leave the pending holder unresolved");
        new RetinaChunkGenerator(deferred, -64, 384, 64, 48, .008F, "mca");

        var stack = new ArrayList<PackResources>();
        stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        var location = new PackLocationInfo("retina-test", Component.literal("Retina test"), PackSource.DEFAULT, Optional.empty());
        stack.add(new PathPackResources(location, Path.of("common/src/main/resources")));
        if (args.length > 0) stack.add((PackResources) new FilePackResources.FileResourcesSupplier(Path.of(args[0])).openMetadata(location));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, stack);
             var workers = Executors.newFixedThreadPool(2)) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var loaded = RegistryDataLoader.load(resources, builtin.listRegistries().toList(),
                    RegistryDataLoader.WORLD_REGISTRIES, workers).join();
            var registry = new RegistryAccess.ImmutableRegistryAccess(
                    java.util.stream.Stream.concat(builtin.registries(), loaded.registries())).freeze();
            var parameters = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getValueOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            pending.register(MultiNoiseBiomeSourceParameterLists.OVERWORLD, parameters, RegistrationInfo.BUILT_IN);
            pending.freeze();
            require(deferred.possibleBiomes().equals(reference.possibleBiomes()), "deferred pool resolves after binding");
            require(imported.possibleBiomes().equals(reference.possibleBiomes()), "imported pool resolves after binding");
            var presets = registry.lookupOrThrow(Registries.WORLD_PRESET);
            checkAutomaticActivation(registry, presets);
            FlatProfileTest.check(registry, false);
            var choices = com.google.gson.JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json")))
                    .getAsJsonObject().getAsJsonArray("values").asList().stream().map(com.google.gson.JsonElement::getAsString).toList();
            require(choices.isEmpty(), "Retina adds no world type; source presets remain selectable");
            var labels = com.google.gson.JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/assets/retina/lang/en_us.json"))).getAsJsonObject();
            require(labels.get("generator.retina.gpu").getAsString().equals("Retina"), "selector label is Retina");
            for (String name : List.of("gpu", "gpu_chunk", "bend")) {
                var key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("retina:" + name));
                var preset = presets.getValueOrThrow(key);
                var generator = preset.createWorldDimensions().dimensions().get(LevelStem.OVERWORLD).generator();
                require(generator instanceof RetinaChunkGenerator, "loaded actual Retina preset " + name);
                var source = (RetinaBiomeSource) generator.getBiomeSource();
                var expected = source.climateParameters(registry).stream().map(com.mojang.datafixers.util.Pair::getSecond)
                        .collect(java.util.stream.Collectors.toSet());
                require(source.possibleBiomes().equals(expected), "complete registered pool after preset loading " + name);
                require(((RetinaChunkGenerator) generator).mode().equals(name.equals("gpu_chunk") ? "chunk" : "mca"), "mode preserved");
                var retina = (RetinaChunkGenerator) generator;
                require(retina.densityComposition(), "new preset enables registered field composition");
                var ops = registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                var encoded = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, retina).getOrThrow();
                var reopened = (RetinaChunkGenerator) net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
                require(reopened.mode().equals(retina.mode()), "backend mode survives save/reopen");
                require(reopened.densityComposition(), "composition survives save/reopen");
                var retired = encoded.deepCopy().getAsJsonObject();
                retired.addProperty("mode", "bend");
                var migrated = (RetinaChunkGenerator) net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, retired).getOrThrow();
                require(migrated.mode().equals("mca") && migrated.regionMode(), "retired backend decodes into Rust MCA");
                var resaved = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, migrated).getOrThrow();
                require(!resaved.getAsJsonObject().has("mode") || resaved.getAsJsonObject().get("mode").getAsString().equals("mca"),
                        "saving writes the canonical Rust mode");
                require(migrated.withDatapackGenerator(referenceGenerator(registry), registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getValueOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)).mode().equals("mca"),
                        "datapack import keeps the migrated Rust generator");
                var legacy = encoded.deepCopy().getAsJsonObject();
                legacy.remove("density_composition");
                var oldWorld = (RetinaChunkGenerator) net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, legacy).getOrThrow();
                require(!oldWorld.densityComposition(), "absent saved option preserves legacy interpolation");
                require(!oldWorld.withDatapackGenerator(referenceGenerator(registry), registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getValueOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)).densityComposition(),
                        "datapack import preserves legacy selection");
            }
            System.out.println("QA_EVT {\"event\":\"retina_preset_registry_loading\",\"status\":\"pass\",\"context\":{\"pack\":\""
                    + (args.length == 0 ? "vanilla" : "terralith") + "\",\"presets\":3}}");
        }
    }

    private static void checkAutomaticActivation(RegistryAccess registry,
            Registry<net.minecraft.world.level.levelgen.presets.WorldPreset> presets) {
        var ops = registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var profiles = new HashMap<String, String>();
        for (String name : List.of("normal", "large_biomes", "amplified", "single_biome_surface")) {
            var key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace(name));
            var dimensions = presets.getValueOrThrow(key).createWorldDimensions().dimensions();
            var stem = dimensions.get(LevelStem.OVERWORLD);
            var original = stem.generator();
            var saved = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, original).getOrThrow();
            var runtime = RetinaGenerators.adapt(net.minecraft.world.level.Level.OVERWORLD, stem, original);
            require(runtime instanceof RetinaChunkGenerator, "automatic adapter: " + name);
            var retina = (RetinaChunkGenerator) runtime;
            require(RetinaGenerators.adapt(net.minecraft.world.level.Level.OVERWORLD, stem, retina) == retina, "idempotent activation");
            require(stem.generator() == original, "source LevelStem is immutable");
            require(net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, original).getOrThrow().equals(saved), "source settings remain serializable unchanged");
            var reopened = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
            require(RetinaGenerators.adapt(net.minecraft.world.level.Level.OVERWORLD, new LevelStem(stem.type(), reopened), reopened) instanceof RetinaChunkGenerator, "automatic activation after source save/reopen");
            var noise = (net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator) original;
            require(retina.selectedSource().noise().generatorSettings() == noise.generatorSettings(), "retain exact selected noise settings");
            require(retina.getMinY() == stem.type().value().minY() && retina.getGenDepth() == stem.type().value().height(), "dimension bounds");
            var source = (RetinaBiomeSource) retina.getBiomeSource();
            var exported = TerrainProfileData.export(registry, source, retina.getMinY(), retina.getGenDepth(), 42,
                    noise.generatorSettings().value(), StructureProfile.Context.NONE, true, stem.type().value().hasSkyLight());
            require(new HashSet<>(exported.biomes()).equals(original.getBiomeSource().possibleBiomes()), "exact selected biome pool: " + name);
            var json = com.google.gson.JsonParser.parseString(exported.json()).getAsJsonObject();
            profiles.put(name, json.get("registry_program").toString());
            if (name.equals("single_biome_surface")) {
                require(exported.biomes().size() == 1, "single biome stays single at every depth");
                require(source.climateParameters(registry).isEmpty(), "fixed source has no borrowed Overworld climate");
                require(json.getAsJsonArray("climate_targets").isEmpty(), "fixed native profile has no climate targets");
            }
            for (var dimension : List.of(LevelStem.NETHER, LevelStem.END)) {
                var other = dimensions.get(dimension);
                var level = ResourceKey.create(Registries.DIMENSION, dimension.identifier());
                var adapted=RetinaGenerators.adapt(level, other, other.generator());
                if(dimension.equals(LevelStem.END)) {
                    require(adapted instanceof RetinaChunkGenerator,"End automatically activates");
                    var end=(RetinaChunkGenerator)adapted;
                    require(end.dimension().equals(net.minecraft.world.level.Level.END)
                            && end.selectedSource().generator()==other.generator(),"End retains source and dimension identity");
                    require(EndTerrainSupport.unsupportedReason(registry,end.selectedSource())==null,"loaded standard End expressions supported");
                }
                else {
                    require(adapted instanceof RetinaChunkGenerator,"Nether automatically activates");
                    var nether=(RetinaChunkGenerator)adapted;
                    require(nether.dimension().equals(net.minecraft.world.level.Level.NETHER) && nether.selectedSource().generator()==other.generator(),"Nether retains source and dimension identity");
                    var savedNether=net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops,other.generator()).getOrThrow();
                    var restored=net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops,savedNether).getOrThrow();
                    require(RetinaGenerators.adapt(level,new LevelStem(other.type(),restored),restored) instanceof RetinaChunkGenerator,"Nether reactivates on reopen");
                }
            }
        }
        require(!profiles.get("normal").equals(profiles.get("large_biomes")), "large-biome noise differs from normal");
        require(!profiles.get("normal").equals(profiles.get("amplified")), "amplified density differs from normal");
        var debug = presets.getValueOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace("debug_all_block_states")))
                .createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
        require(RetinaGenerators.adapt(net.minecraft.world.level.Level.OVERWORLD, debug, debug.generator()) == debug.generator(), "debug generator unchanged");
        var flat = presets.getValueOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace("flat")))
                .createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
        var custom = new net.minecraft.world.level.levelgen.FlatLevelSource(((net.minecraft.world.level.levelgen.FlatLevelSource) flat.generator()).settings()) { };
        require(RetinaGenerators.adapt(net.minecraft.world.level.Level.OVERWORLD, new LevelStem(flat.type(), custom), custom) == custom,
                "unimplemented custom generator subclass retains its behavior");
        System.out.println("QA_EVT {\"event\":\"automatic_worldgen_profiles\",\"status\":\"pass\"}");
    }

    static void registerCodecs() throws Exception {
        // Standalone test JVM has no Fabric registry-unfreeze lifecycle.
        var frozen = MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        var bind = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
        bind.setAccessible(true);
        register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.parse("retina:gpu"), RetinaChunkGenerator.CODEC, frozen, bind);
        register(BuiltInRegistries.BIOME_SOURCE, Identifier.parse("retina:voronoi"), RetinaBiomeSource.CODEC, frozen, bind);
    }

    private static net.minecraft.world.level.chunk.ChunkGenerator referenceGenerator(RegistryAccess registry) {
        return new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(referenceSource(registry), registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD));
    }

    private static BiomeSource referenceSource(RegistryAccess registry) {
        return MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
    }

    private static <T> void register(Registry<T> registry, Identifier id, T value,
                                     java.lang.reflect.Field frozen, java.lang.reflect.Method bind) throws Exception {
        frozen.setBoolean(registry, false);
        Registry.register(registry, id, value);
        bind.invoke(registry.get(id).orElseThrow(), value);
        frozen.setBoolean(registry, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
