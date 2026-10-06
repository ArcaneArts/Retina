package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import java.nio.file.*;
import java.util.*;

/** Loaded registry data and actual GPU output, including both new world presets. */
public final class NativeShoreIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin).forEach(Registry.PendingTags::apply);
            var loaded = RegistryDataLoader.load(resources, builtin.listRegistries().toList(),
                    RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
            var registry = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtin.registries(), loaded.registries())).freeze();
            for (String mode : List.of("gpu", "gpu_chunk")) {
                var preset = JsonParser.parseString(Files.readString(Path.of("common/src/main/resources/data/retina/worldgen/world_preset/" + mode + ".json")))
                        .getAsJsonObject().getAsJsonObject("dimensions").getAsJsonObject("minecraft:overworld").getAsJsonObject("generator").getAsJsonObject("biome_source");
                var source = RetinaBiomeSource.CODEC.codec().parse(registry.createSerializationContext(JsonOps.INSTANCE), preset).getOrThrow();
                require(source.hasRegistrySource(), "preset imports the registered climate intervals: " + mode);
                var expected = source.climateParameters(registry).stream().map(com.mojang.datafixers.util.Pair::getSecond).collect(java.util.stream.Collectors.toSet());
                require(source.possibleBiomes().equals(expected), "new preset includes every registered Overworld biome: " + mode);
                require(source.possibleBiomes().stream().noneMatch(b -> b.is(net.minecraft.tags.BiomeTags.IS_NETHER) || b.is(net.minecraft.tags.BiomeTags.IS_END)), "default pool stays in the Overworld");
                var ops = registry.createSerializationContext(JsonOps.INSTANCE);
                var saved = RetinaBiomeSource.CODEC.codec().encodeStart(ops, source).getOrThrow();
                require(RetinaBiomeSource.CODEC.codec().parse(ops, saved).getOrThrow().possibleBiomes().equals(expected), "registry pool survives save/reopen");
                var older = preset.deepCopy(); older.remove("use_registry_biomes");
                require(RetinaBiomeSource.CODEC.codec().parse(ops, older).getOrThrow().possibleBiomes().size() == older.getAsJsonArray("biomes").size(), "older explicit biome pools stay unchanged");
                if (mode.equals("gpu_chunk")) continue;
                var profile = BiomeTerrainProfile.load(registry, source, -64, 384, 123456789L);
                Files.writeString(Path.of("build/shore-vanilla-profile.json"), profile.json());
                RegistryShoreIntegrationChecks.checkCoasts(profile, source.climateParameters(registry));
                RegistryClimateIntegrationChecks.check(profile);
                RegistryShoreIntegrationChecks.checkWaterCondition(profile);
                RegistryShoreIntegrationChecks.checkSediments(profile, false);
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
