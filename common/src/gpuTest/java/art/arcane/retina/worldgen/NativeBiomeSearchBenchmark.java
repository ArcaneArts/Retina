package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Point-search microbenchmark; never a substitute for complete-region timings. */
public final class NativeBiomeSearchBenchmark {
    private static final int WARMUPS = 5, MEASURED = 20, SEARCHES = 32;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            for (boolean nether : new boolean[]{false, true}) {
                for (long seed : new long[]{123456789L, -987654321L}) run(registry, nether, seed);
            }
        }
    }

    private static void run(RegistryAccess registry, boolean nether, long seed) throws Exception {
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD);
        var biomes = MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var dimension = registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getValueOrThrow(nether ? BuiltinDimensionTypes.NETHER : BuiltinDimensionTypes.OVERWORLD);
        try(var fixture=RuntimeDefinitionFixture.source(registry,new GenerationSource(nether?Level.NETHER:Level.OVERWORLD,
                new NoiseBasedChunkGenerator(biomes,settings),dimension),seed,StructureProfile.Context.NONE,PalettedContainerFactory.create(registry))) {
            var generator=fixture.generator();
            try {
                var source = (RetinaBiomeSource) generator.getBiomeSource();
                var query = generator.queries();
                var terrain = NativeTerrain.instance();
                int profile = generator.request(seed, 0, 0).profile();
                String profileHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(generator.profile().diagnosticJson().getBytes(StandardCharsets.UTF_8)));
                String definitionHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(java.nio.file.Files.readAllBytes(fixture.file())));
                long chunksBefore = terrain.timings(profile).chunks();
                long[] requestedPoints = {0};
                source.bindQueries(points -> { requestedPoints[0] += points.size(); return query.biomes(points); });
                for (int batch = 0; batch < WARMUPS + MEASURED; batch++) {
                    var origins = new ArrayList<BlockPos>();
                    for (int i = 0; i < SEARCHES; i++) {
                        int x = (batch * SEARCHES + i) * 97 - 40000;
                        int y = dimension.minY() + 1 + (i * 13) % (dimension.height() - 2);
                        origins.add(new BlockPos(x, y, -x - 17));
                    }
                    List<Pair<BlockPos, Holder<Biome>>> previous = null;
                    for (String mode : List.of("new-points", "cached-points")) {
                        var before = terrain.gpuDiagnostics(profile);
                        long jobsBefore = terrain.timings(profile).gpuJobs();
                        long pointsBefore = requestedPoints[0];
                        var results = new ArrayList<Pair<BlockPos, Holder<Biome>>>();
                        long started = System.nanoTime();
                        for (var origin : origins) results.add(source.findClosestQuery(origin, 1, 1, 1, b -> true,
                                dimension.minY(), dimension.minY() + dimension.height() - 1));
                        long nanos = System.nanoTime() - started;
                        var after = terrain.gpuDiagnostics(profile);
                        if (results.stream().anyMatch(Objects::isNull)) throw new AssertionError("unrestricted search returned null");
                        if (previous != null && !previous.equals(results)) throw new AssertionError("cached search changed results");
                        previous = results;
                        var digest = MessageDigest.getInstance("SHA-256");
                        for (var result : results) digest.update((result.getFirst().toShortString() + "|"
                                + result.getSecond().unwrapKey().orElseThrow().identifier() + "\n").getBytes(StandardCharsets.UTF_8));
                        var row = new JsonObject();
                        row.addProperty("dimension", nether ? "nether" : "overworld");
                        row.addProperty("profile_sha256", profileHash);
                        row.addProperty("definition_sha256",definitionHash);
                        row.addProperty("java_version", System.getProperty("java.version"));
                        row.addProperty("native_library", System.getProperty("retina.native.path"));
                        row.addProperty("seed", seed); row.addProperty("mode", mode);
                        row.addProperty("batch", batch); row.addProperty("warmup", batch < WARMUPS);
                        row.addProperty("searches", SEARCHES); row.addProperty("elapsed_nanos", nanos);
                        row.addProperty("requested_points", requestedPoints[0] - pointsBefore);
                        row.addProperty("gpu_jobs", terrain.timings(profile).gpuJobs() - jobsBefore);
                        row.addProperty("readback_bytes", after.readbackBytes() - before.readbackBytes());
                        row.addProperty("status_before", before.status()); row.addProperty("status_after", after.status());
                        row.addProperty("results_sha256", HexFormat.of().formatHex(digest.digest()));
                        System.out.println("BIOME_SEARCH_BENCH " + row);
                    }
                }
                if (terrain.timings(profile).chunks() != chunksBefore || generator.metrics().snapshot().previewRegions() != 0)
                    throw new AssertionError("point benchmark generated chunks or regions");
            } finally { generator.closePreviews(); }
        }
    }
}
