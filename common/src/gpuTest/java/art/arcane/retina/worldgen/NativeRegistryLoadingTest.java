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
             var workers = Executors.newFixedThreadPool(8)) {
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
            for (String name : List.of("gpu", "gpu_chunk")) {
                var key = ResourceKey.create(Registries.WORLD_PRESET, Identifier.parse("retina:" + name));
                var preset = presets.getValueOrThrow(key);
                var generator = preset.createWorldDimensions().dimensions().get(LevelStem.OVERWORLD).generator();
                require(generator instanceof RetinaChunkGenerator, "loaded actual Retina preset " + name);
                var source = (RetinaBiomeSource) generator.getBiomeSource();
                var expected = source.climateParameters(registry).stream().map(com.mojang.datafixers.util.Pair::getSecond)
                        .collect(java.util.stream.Collectors.toSet());
                require(source.possibleBiomes().equals(expected), "complete registered pool after preset loading " + name);
                require(((RetinaChunkGenerator) generator).mode().equals(name.equals("gpu") ? "mca" : "chunk"), "mode preserved");
                var retina = (RetinaChunkGenerator) generator;
                require(retina.densityComposition(), "new preset enables registered field composition");
                var ops = registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
                var encoded = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, retina).getOrThrow();
                var reopened = (RetinaChunkGenerator) net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
                require(reopened.densityComposition(), "composition survives save/reopen");
                var legacy = encoded.deepCopy().getAsJsonObject();
                legacy.remove("density_composition");
                var oldWorld = (RetinaChunkGenerator) net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops, legacy).getOrThrow();
                require(!oldWorld.densityComposition(), "absent saved option preserves legacy interpolation");
                require(!oldWorld.withDatapackGenerator(referenceGenerator(registry), registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                        .getValueOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD)).densityComposition(),
                        "datapack import preserves legacy selection");
            }
            System.out.println("QA_EVT {\"event\":\"retina_preset_registry_loading\",\"status\":\"pass\",\"context\":{\"pack\":\""
                    + (args.length == 0 ? "vanilla" : "terralith") + "\",\"presets\":2}}");
        }
    }

    private static void registerCodecs() throws Exception {
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
