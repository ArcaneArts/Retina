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
import java.nio.file.*;
import java.util.*;

/** Benchmark profiles use the actual merged dimension, features and template resources. */
public final class NativeProfileExport {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var stack = new ArrayList<PackResources>();
        stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        boolean packed = args.length > 1;
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
            var original = packed ? dimensions.lookupOrThrow(Registries.LEVEL_STEM).getValueOrThrow(LevelStem.OVERWORLD) : null;
            var preset = JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/data/retina/worldgen/world_preset/gpu.json")))
                    .getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").getAsJsonObject("biome_source");
            var source = packed ? RetinaBiomeSource.fromRegistry(original.generator().getBiomeSource(), 128, .55f)
                    : RetinaBiomeSource.CODEC.codec().parse(registry.createSerializationContext(JsonOps.INSTANCE), preset).getOrThrow();
            var settings = packed && original.generator() instanceof NoiseBasedChunkGenerator noise ? noise.generatorSettings().value()
                    : registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            var profile = BiomeTerrainProfile.load(registry, source, -64, 384, 123456789L, settings, StructureProfile.Context.of(resources));
            var data = JsonParser.parseString(profile.json()).getAsJsonObject();
            if (data.getAsJsonObject("structures").getAsJsonArray("definitions").isEmpty()) throw new AssertionError("benchmark must include structures");
            if (data.getAsJsonArray("decorations").isEmpty()) throw new AssertionError("benchmark must include decorations");
            var output = Path.of(args[0]); Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, profile.json());
            System.out.println("Benchmark profile: " + output + ", biomes=" + profile.biomes().size()
                    + ", structures=" + data.getAsJsonObject("structures").getAsJsonArray("definitions").size()
                    + ", decorations=" + data.getAsJsonArray("decorations").size());
        }
    }
}
