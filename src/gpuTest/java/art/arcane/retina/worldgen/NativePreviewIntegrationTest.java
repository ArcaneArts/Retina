package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real GPU/MCA integration: DH-style synchronous protochunk calls never publish preview terrain. */
public final class NativePreviewIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = RegistryIntegrationFixtures.load(resources);
        var lookup = registry.lookupOrThrow(Registries.BIOME);
        var ids = List.of("plains", "forest", "birch_forest", "taiga", "old_growth_pine_taiga", "snowy_plains", "desert", "savanna", "badlands", "jungle", "mangrove_swamp", "windswept_hills", "jagged_peaks", "ocean", "warm_ocean", "frozen_ocean");
        List<Holder<Biome>> biomes = ids.stream().map(id -> (Holder<Biome>)lookup.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)))).toList();
        var biomeMap = new IdMapper<Holder<Biome>>(); lookup.listElements().forEach(biomeMap::add);
        Codec<Holder<Biome>> biomeCodec = Codec.STRING.comapFlatMap(id -> lookup.get(ResourceKey.create(Registries.BIOME, Identifier.parse(id)))
                .<DataResult<Holder<Biome>>>map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Unknown biome: " + id)),
                holder -> holder.unwrapKey().orElseThrow().identifier().toString());
        var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        var biomeStrategy = Strategy.createForBiomes(biomeMap);
        var factory = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(),
                PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState()),
                biomeStrategy, biomes.getFirst(), PalettedContainer.codecRO(biomeCodec, biomeStrategy, biomes.getFirst()));
        long seed = 123456789L;
        var generator = new RetinaChunkGenerator(new RetinaBiomeSource(biomes, 256, .55F), -64, 384, 64, 48, .008F, "mca");
        generator.bindWorld(registry, seed, factory);
        var random = RandomState.create(registry.lookupOrThrow(Registries.NOISE), seed, registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value());
        var bounds = LevelHeightAccessor.create(-64, 384);
        var position = new ChunkPos(-32, 0);
        var save = Files.createTempDirectory("retina-preview-save-test-");
        try {
            long regionsBeforeSearch=generator.metrics().snapshot().previewRegions();
            var search=((RetinaBiomeSource)generator.getBiomeSource()).findBiomeHorizontal(0,64,0,512,16,b -> false,
                    net.minecraft.util.RandomSource.create(42),false,random);
            require(search==null && generator.metrics().snapshot().previewRegions()==regionsBeforeSearch,
                    "wide biome searches perform GPU queries without creating temporary MCAs or blocking publication");
            System.out.println("QA_EVT {\"event\":\"biome_search_no_mca_assembly\",\"status\":\"pass\"}");
            var proto = new ProtoChunk(position, UpgradeData.EMPTY, bounds, factory, null);
            var biomeFuture = generator.createBiomes(random, Blender.empty(), null, proto);
            require(biomeFuture.isDone() && biomeFuture.join() == proto, "DH receives a completed biome future");
            var surfaceFuture = generator.buildTerrain(proto, Blender.empty(), random, null, null, null, Set.copyOf(biomes));
            require(surfaceFuture.isDone() && surfaceFuture.join() == proto, "DH terrain is available before buildTerrain returns");
            try (var generated = NativeTerrain.instance().generate(generator.request(seed, position.x(), position.z()))) {
                var bytes = generated.blocks().toArray(ValueLayout.JAVA_SHORT);
                for (int layer = 0; layer < 384; layer++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var at = new net.minecraft.core.BlockPos(position.getMinBlockX() + x, layer - 64, z);
                    require(proto.getBlockState(at).equals(generator.profile().materials()[Short.toUnsignedInt(bytes[layer * 256 + z * 16 + x])]), "temporary MCA surface matches decorated native chunks");
                }
            }
            for (int z : new int[]{0, 7, 15}) for (int x : new int[]{0, 4, 15}) {
                var actual = generator.getBaseColumn(position.getMinBlockX() + x, z, bounds, random);
                var nativeColumn = NativeTerrain.instance().column(generator.request(seed, position.x(), position.z()), z * 16 + x);
                for (int layer = 0; layer < 384; layer++) require(actual.getBlock(layer - 64).equals(generator.profile().materials()[Short.toUnsignedInt(nativeColumn[layer])]), "cached GPU columns preserve undecorated height/base-column semantics");
                int top = generator.getBaseHeight(position.getMinBlockX() + x, z, Heightmap.Types.WORLD_SURFACE, bounds, random);
                require(actual.getBlock(top).isAir() && !actual.getBlock(top - 1).isAir(), "height query uses the same temporary batch");
            }
            require(generator.metrics().snapshot().previewRegions() == 1 && generator.metrics().snapshot().regions() == 0 && generator.metrics().snapshot().total() == 1024, "biomes, terrain and height queries share one temporary region batch");
            try (var files = Files.list(save)) { require(files.findAny().isEmpty(), "surface calls leave the actual save folder empty"); }
            var another = new ProtoChunk(position, UpgradeData.EMPTY, bounds, factory, null);
            generator.buildTerrain(another, Blender.empty(), random, null, null, null, Set.copyOf(biomes)).join();
            var edit = new net.minecraft.core.BlockPos(position.getMinBlockX(), -64, 0);
            another.setBlockState(edit, Blocks.DIAMOND_BLOCK.defaultBlockState());
            require(!proto.getBlockState(edit).is(Blocks.DIAMOND_BLOCK), "separate preview protochunks own their section palettes");
            System.out.println("QA_EVT {\"event\":\"dh_surface_uses_temporary_mca\",\"status\":\"pass\",\"context\":{\"regions\":1,\"chunks\":1024}}");

            var bridge = new RegionStorageBridge() {
                public void retina$configure(RegionCoordinator coordinator) { }
                public Path retina$folder() { return save; }
                public void retina$request(ChunkPos position) { }
                public void retina$closeRegion(ChunkPos position) { }
            };
            Files.createFile(save.resolve("r.-1.0.mca")); // DH probes leave empty RegionFile placeholders.
            var beforeMetrics=generator.metrics().snapshot();
            var beforePromotion=NativeTerrain.instance().timings(generator.profile().nativeId());
            new RegionCoordinator(generator, seed, save).prepare(position, bridge);
            var afterPromotion=NativeTerrain.instance().timings(generator.profile().nativeId());
            require(afterPromotion.chunks()==beforePromotion.chunks() && afterPromotion.gpuJobs()==beforePromotion.gpuJobs()
                    && afterPromotion.workerNanos()==beforePromotion.workerNanos(), "promotion adds neither GPU nor Rust generation time");
            require(afterPromotion.nanos(NativeTimings.IO)>beforePromotion.nanos(NativeTimings.IO), "promotion records actual file I/O");
            require(generator.metrics().snapshot().promotions() == 1 && generator.metrics().snapshot().total() == 1024, "warm preview is promoted without duplicate terrain production");
            require(beforeMetrics.columnCacheMs()>0, "Java column cache writes are measured separately");
            require(generator.metrics().snapshot().regionSamples()==1 && generator.metrics().snapshot().averageRegionMs()==beforeMetrics.averageRegionMs(), "promotion preserves the measured generation window");
            require(Files.isRegularFile(save.resolve("r.-1.0.mca")), "actual Minecraft load publishes the cached region");
            System.out.println("QA_EVT {\"event\":\"temporary_mca_promotion\",\"status\":\"pass\"}");

            var coordinator = new RegionCoordinator(generator, seed, save);
            var next = new ChunkPos(64, 0);
            var following = new ChunkPos(96, 0);
            long before = generator.metrics().snapshot().previewRegions();
            coordinator.request(next); coordinator.request(next); coordinator.request(following);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean parallel = false;
            while (generator.metrics().snapshot().previewRegions() < before + 2 && System.nanoTime() < deadline) {
                parallel |= generator.metrics().snapshot().inFlight() >= 2;
                Thread.sleep(1);
            }
            require(generator.metrics().snapshot().previewRegions() == before + 2 && parallel,
                    "two requested regions prepare concurrently and duplicate loads coalesce");
            var concurrentMetrics=generator.metrics().snapshot();
            require(concurrentMetrics.regionSamples()==3 && concurrentMetrics.regionStages().chunks()==3072, "parallel regions do not leak session counters into each other");
            require(concurrentMetrics.regionStagePercent(NativeTimings.NBT)>0 && concurrentMetrics.regionStagePercent(NativeTimings.COMPRESS)>0, "concurrent job wall shares reach F3");
            require(!Files.exists(save.resolve("r.2.0.mca")) && !Files.exists(save.resolve("r.3.0.mca")),
                    "background assembly leaves live save files untouched until their I/O jobs");
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-pipeline-test", Level.OVERWORLD, "chunk"), save, false)) {
                var editTag = new net.minecraft.nbt.CompoundTag(); editTag.putString("retina_test_marker", "concurrent edit");
                storage.write(next, editTag);
            }
            long chunks = NativeTerrain.instance().timings(generator.profile().nativeId()).chunks();
            coordinator.prepare(next, bridge); coordinator.prepare(following, bridge);
            require(NativeTerrain.instance().timings(generator.profile().nativeId()).chunks() == chunks,
                    "publication consumes completed preparation without regenerating either region");
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-pipeline-test", Level.OVERWORLD, "chunk"), save, false)) {
                require(storage.read(next).getStringOr("retina_test_marker", "").equals("concurrent edit"),
                        "publication merges around a chunk written while preparation was in flight");
                require(storage.read(new ChunkPos(65, 0)) != null && storage.read(following) != null,
                        "both prepared regions load all missing chunks");
            }
            coordinator.close();
            System.out.println("QA_EVT {\"event\":\"requested_region_pipeline_and_concurrent_edit\",\"status\":\"pass\"}");
        } finally { generator.closePreviews(); }

        var request = generator.request(seed, -1, 0);
        var metrics = new GenerationMetrics();
        Path previewFolder;
        try (var cache = new TemporaryRegions(request, "minecraft:plains", generator.profile(), metrics, 2, 1)) {
            previewFolder = cache.folder();
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(16)) {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 32; i++) {
                    int index = i;
                    futures.add(workers.submit(() -> {
                        start.await();
                        var pos = new ChunkPos(-32 + index % 32, index % 16);
                        var tag = cache.read(pos);
                        require(tag.getIntOr("xPos", 99) == pos.x(), "concurrent region readers receive their own chunk");
                        var actual = cache.columns(pos);
                        var r = generator.request(seed, pos.x(), pos.z());
                        var expected = NativeTerrain.instance().sampleColumns(r);
                        require(Arrays.equals(actual.heights(), expected.heights()) && Arrays.equals(actual.packed(), expected.packed()), "bulk region columns match GPU queries");
                        return null;
                    }));
                }
                start.countDown(); for (var future : futures) future.get();
            }
            require(metrics.snapshot().previewRegions() == 1 && metrics.snapshot().previewCacheHits() >= 31, "parallel requests coalesce into one MCA generation");
            var destination = save.resolve("r.-1.0.mca");
            byte[] saved = Files.readAllBytes(destination);
            require(cache.publishIfPresent(new ChunkPos(-1, 0), destination).generated() == 0, "complete saved regions require no promotion");
            require(Arrays.equals(saved, Files.readAllBytes(destination)), "preview never overwrites an existing save");
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-preview-test", Level.OVERWORLD, "chunk"), save, false)) {
                var tag = storage.read(new ChunkPos(-32, 0)); tag.putString("retina_test_marker", "player edit"); storage.write(new ChunkPos(-32, 0), tag);
            }
            saved = Files.readAllBytes(destination);
            require(cache.publishIfPresent(new ChunkPos(-1, 0), destination).generated() == 0 && Arrays.equals(saved, Files.readAllBytes(destination)), "player edits survive attempted preview publication");
            require(!cache.read(new ChunkPos(-32, 0)).contains("retina_test_marker"), "saved edits do not mutate the temporary snapshot");
            var missing = new ChunkPos(-31, 0);
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-preview-test", Level.OVERWORLD, "chunk"), save, false)) {
                storage.write(missing, null);
            }
            saved = Files.readAllBytes(destination);
            var restored = cache.publishIfPresent(missing, destination);
            require(restored.generated() == 1 && restored.preserved() == 1023 && restored.gpuNanos() == 0, "partial saves copy only missing cached records without GPU dispatch");
            var merged = Files.readAllBytes(destination);
            require(Arrays.equals(saved, 8192, saved.length, merged, 8192, saved.length), "all existing compressed chunk records remain byte-for-byte intact");
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-preview-test", Level.OVERWORLD, "chunk"), save, false)) {
                require(storage.read(new ChunkPos(-32, 0)).getStringOr("retina_test_marker", "").equals("player edit"), "partial promotion preserves player edits");
                require(storage.read(missing).equals(cache.read(missing)), "restored record decodes as the exact cached decorated chunk");
            }
            var absent = save.resolve("absent/r.-1.0.mca");
            var installed = cache.publishIfPresent(missing, absent);
            require(installed.generated() == 1024 && installed.preserved() == 0 && installed.gpuNanos() == 0, "absent saves promote the whole cached MCA without GPU dispatch");
            var corrupt = save.resolve("corrupt/r.-1.0.mca");
            Files.createDirectories(corrupt.getParent()); Files.write(corrupt, new byte[]{1});
            boolean rejected = false;
            try { cache.publishIfPresent(missing, corrupt); }
            catch (IllegalStateException error) { rejected = error.getMessage().contains("truncated MCA header"); }
            require(rejected && Arrays.equals(Files.readAllBytes(corrupt), new byte[]{1}), "nonempty truncated saves report corruption and remain unchanged");
            System.out.println("QA_EVT {\"event\":\"temporary_mca_placeholder_and_partial_promotion\",\"status\":\"pass\"}");
            cache.read(new ChunkPos(-64, 0));
            var reloaded = cache.columns(new ChunkPos(-1, 0));
            require(Arrays.equals(reloaded.packed(), NativeTerrain.instance().sampleColumns(request).packed()) && metrics.snapshot().previewRegions() == 2, "cold columns reload from disk without regenerating the MCA");
            cache.read(new ChunkPos(-1, 0)); cache.read(new ChunkPos(0, 0));
            require(cache.size() == 2 && !Files.exists(previewFolder.resolve("r.-2.0.mca")), "LRU closes and deletes evicted MCA files");
            try (var files = Files.list(previewFolder)) { require(files.filter(p -> p.toString().endsWith(".mca")).count() == 2, "temporary file count stays bounded"); }
            System.out.println("QA_EVT {\"event\":\"temporary_mca_parallel_lru_and_save_isolation\",\"status\":\"pass\"}");
        }
        require(!Files.exists(previewFolder), "world close deletes all temporary regions and their directory");

        var raceMetrics = new GenerationMetrics();
        var race = new TemporaryRegions(generator.request(seed, 320, 320), "minecraft:plains", generator.profile(), raceMetrics, 1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var future = workers.submit(() -> race.read(new ChunkPos(320, 320)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (raceMetrics.snapshot().inFlight() == 0 && !future.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
            require(raceMetrics.snapshot().inFlight() == 1, "shutdown race runs during real region generation");
            race.close(); require(future.get() != null, "world close permits a leased read to finish");
            require(!Files.exists(race.folder()), "last active lease cleans up after world close");
        } finally { race.close(); }
        var failures = new GenerationMetrics();
        try (var bad = new TemporaryRegions(new TerrainRequest(seed, 0, 0, -63, 384, 64, 48, .008F, request.profile()), "minecraft:plains", generator.profile(), failures, 1)) {
            boolean failed = false;
            try { bad.read(new ChunkPos(0, 0)); } catch (IllegalStateException error) { failed = true; }
            require(failed && failures.snapshot().failures() == 1 && failures.snapshot().inFlight() == 0 && bad.size() == 0, "native failures release failed preview entries and report the actual error");
        }
        System.out.println("QA_EVT {\"event\":\"temporary_mca_shutdown_and_failure_cleanup\",\"status\":\"pass\"}");
        try (var files = Files.walk(save)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
