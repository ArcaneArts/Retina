package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

/** Actual Metal/Vulkan/DX12 output decoded with Minecraft's palettes and predicates. */
public final class NativeBiomeIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = VanillaRegistries.createWorldLookup();
        var biomeRegistry = registry.lookupOrThrow(Registries.BIOME);
        var ids = List.of("plains", "forest", "birch_forest", "taiga", "old_growth_pine_taiga", "snowy_plains", "desert", "savanna", "badlands", "jungle", "mangrove_swamp", "windswept_hills", "jagged_peaks", "ocean", "warm_ocean", "frozen_ocean");
        List<Holder<Biome>> biomes = ids.stream().map(id -> (Holder<Biome>) biomeRegistry.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(id)))).toList();
        var profile = BiomeTerrainProfile.load(registry, new RetinaBiomeSource(biomes, 256, 0.55F), -64, 384);
        Files.writeString(java.nio.file.Path.of("build/registry-biome-profile.json"), profile.json());
        var nativeTerrain = NativeTerrain.instance();
        require(nativeTerrain.registerProfile(profile.json()) == profile.nativeId(), "identical profiles reuse the GPU upload");
        var seenBiomes = new HashSet<Integer>();
        var seenMaterials = new HashSet<Integer>();
        var chunks = new HashMap<ChunkPos, NativeTerrain.Columns>();
        try (var workers = Executors.newFixedThreadPool(16)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 128; i++) {
                final int index = i;
                jobs.add(workers.submit(() -> {
                    var r = request(profile, (index % 16 - 8) * 37, (index / 16 - 4) * 41);
                    try (var data = nativeTerrain.generate(r)) {
                        var query = nativeTerrain.sampleColumns(r);
                        require(Arrays.equals(query.heights(), data.heights()) && Arrays.equals(query.packed(), data.columns().packed()), "concurrent queries agree with generation");
                        var bytes = data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                        synchronized (seenBiomes) {
                            for (int packed : query.packed()) seenBiomes.add(packed & 255);
                            for (byte material : bytes) seenMaterials.add(Byte.toUnsignedInt(material));
                        }
                    }
                }));
            }
            for (var job : jobs) job.get();
        }
        require(seenBiomes.size() >= 6, "multiple climate biomes are reachable: " + seenBiomes);
        require(seenMaterials.contains(index(profile, Blocks.WATER.defaultBlockState())), "oceans contain registered water");
        require(seenMaterials.contains(index(profile, Blocks.SAND.defaultBlockState())), "desert/warm ocean surfaces contain registered sand");
        require(seenMaterials.contains(index(profile, Blocks.SNOW_BLOCK.defaultBlockState())), "cold land contains snow");
        System.out.println("QA_EVT {\"event\":\"parallel_registry_gpu_biomes\",\"status\":\"pass\",\"context\":{\"chunks\":128,\"biomes\":" + seenBiomes.size() + ",\"materials\":" + seenMaterials.size() + "}}");

        var changedNoise = com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
        var temperatureNoise = changedNoise.getAsJsonArray("noises").get(0).getAsJsonObject();
        temperatureNoise.addProperty("frequency", temperatureNoise.get("frequency").getAsDouble() * 2);
        int changedProfile = nativeTerrain.registerProfile(changedNoise.toString());
        boolean noiseChangesBiomes = false;
        for (int i = 1; i <= 16; i++) {
            var original = request(profile, i * 37, -i * 41);
            var changed = new TerrainRequest(original.seed(), original.chunkX(), original.chunkZ(), original.minY(), original.height(), original.baseHeight(), original.amplitude(), original.frequency(), changedProfile);
            var before = nativeTerrain.sampleColumns(original);
            var after = nativeTerrain.sampleColumns(changed);
            for (int c = 0; c < 256; c++) noiseChangesBiomes |= before.biome(c) != after.biome(c);
        }
        require(noiseChangesBiomes, "registry climate noise settings affect GPU biome selection and profiles have distinct caches");
        System.out.println("QA_EVT {\"event\":\"registry_noise_gpu_effect\",\"status\":\"pass\"}");

        int farStep = 0;
        for (int origin : new int[]{-65536, -4096, 4096, 65536, 1000000}) {
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                var values = nativeTerrain.sampleColumns(request(profile, Math.floorDiv(origin, 16) + dx, Math.floorDiv(origin, 16) + dz)).heights();
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (x > 0) farStep = Math.max(farStep, Math.abs(values[z * 16 + x] - values[z * 16 + x - 1]));
                    if (z > 0) farStep = Math.max(farStep, Math.abs(values[z * 16 + x] - values[(z - 1) * 16 + x]));
                }
            }
        }
        require(farStep <= 12, "height interpolation stays smooth at distant world coordinates: " + farStep);
        System.out.println("QA_EVT {\"event\":\"distant_gpu_height_interpolation\",\"status\":\"pass\",\"context\":{\"largest_step\":" + farStep + "}}");

        // Capture the per-chunk path before the one-descriptor region dispatch.
        for (int z : new int[]{0, 15, 31}) for (int x : new int[]{-32, -17, -1}) chunks.put(new ChunkPos(x, z), nativeTerrain.sampleColumns(request(profile, x, z)));
        var directory = Files.createTempDirectory("retina-biomes-mca-");
        var path = directory.resolve("r.-1.0.mca");
        Codec<PalettedContainer<BlockState>> codec = PalettedContainer.codecRW(BlockState.CODEC,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
        var info = new RegionStorageInfo("retina-biome-test", Level.OVERWORLD, "chunk");
        try {
            long start = System.nanoTime();
            var report = nativeTerrain.generateRegion(request(profile, -1, 0), path, SharedConstants.getCurrentVersion().dataVersion().version(), "minecraft:plains");
            double generationMs = (System.nanoTime() - start) / 1_000_000.0;
            require(report.generated() == 1024, "entire biome MCA is generated");
            var allHeights = new int[512 * 512];
            var allSurfaces = new int[512 * 512];
            var regionBiomes = new HashSet<String>();
            try (var storage = new RegionFileStorage(info, directory, false)) {
                for (int z = 0; z < 32; z++) for (int x = -32; x < 0; x++) {
                    var pos = new ChunkPos(x, z);
                    var r = request(profile, x, z);
                    var tag = storage.read(pos);
                    require(tag != null, "Minecraft reads every generated slot");
                    var query = nativeTerrain.sampleColumns(r);
                    if (chunks.containsKey(pos)) {
                        require(Arrays.equals(chunks.get(pos).heights(), query.heights()) && Arrays.equals(chunks.get(pos).packed(), query.packed()), "region and chunk query agreement");
                    }
                    try (var data = nativeTerrain.generate(r)) {
                        var bytes = data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                        var sections = tag.getListOrEmpty("sections");
                        var actualHeights = new int[Heightmap.Types.values().length][256];
                        for (int sectionIndex = 0; sectionIndex < 24; sectionIndex++) {
                            var section = sections.getCompound(sectionIndex).orElseThrow();
                            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                            var biomeTag = section.getCompoundOrEmpty("biomes");
                            var palette = biomeTag.getListOrEmpty("palette");
                            int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
                            var packedBiomes = palette.size() == 1 ? null : new SimpleBitStorage(bits, 64, biomeTag.getLongArray("data").orElseThrow());
                            for (int i = 0; i < 64; i++) {
                                String actual = palette.getString(packedBiomes == null ? 0 : packedBiomes.get(i)).orElseThrow();
                                String expected = biomes.get(query.biome(((i % 16) / 4) * 64 + (i % 4) * 4)).unwrapKey().orElseThrow().identifier().toString();
                                require(actual.equals(expected), "MCA biome sample agrees with GPU");
                                regionBiomes.add(actual);
                            }
                            for (int y = 0; y < 16; y++) for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                                int layer = sectionIndex * 16 + y;
                                int c = localZ * 16 + localX;
                                var state = blocks.get(localX, y, localZ);
                                require(state.equals(profile.materials()[Byte.toUnsignedInt(bytes[layer * 256 + c])]), "MCA block states match per-chunk Rust assembly, including properties");
                                for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING}) {
                                    if (type.isOpaque().test(state)) actualHeights[type.ordinal()][c] = layer + 1;
                                }
                            }
                        }
                        for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING}) {
                            var packed = new SimpleBitStorage(9, 256, tag.getCompoundOrEmpty("Heightmaps").getLongArray(type.getSerializationKey()).orElseThrow());
                            for (int c = 0; c < 256; c++) require(packed.get(c) == actualHeights[type.ordinal()][c], "stored " + type + " heightmap matches actual materials at " + pos + "/" + c + ": stored " + packed.get(c) + ", actual " + actualHeights[type.ordinal()][c] + ", GPU " + query.heights()[c] + ", packed " + query.packed()[c]);
                        }
                        for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                            int global = (z * 16 + localZ) * 512 + (x + 32) * 16 + localX;
                            allHeights[global] = query.heights()[localZ * 16 + localX];
                            allSurfaces[global] = (query.packed()[localZ * 16 + localX] >>> 8) & 255;
                        }
                    }
                }
            }
            int largestStep = 0;
            int surfaceEdges = 0;
            int isolatedSurfaces = 0;
            for (int z = 0; z < 512; z++) for (int x = 0; x < 512; x++) {
                int h = allHeights[z * 512 + x];
                int c = z * 512 + x;
                if (x > 0 && allSurfaces[c] != allSurfaces[c - 1]) surfaceEdges++;
                if (z > 0 && allSurfaces[c] != allSurfaces[c - 512]) surfaceEdges++;
                if (x > 0 && x < 511 && z > 0 && z < 511 && allSurfaces[c] != allSurfaces[c - 1]
                        && allSurfaces[c] != allSurfaces[c + 1] && allSurfaces[c] != allSurfaces[c - 512] && allSurfaces[c] != allSurfaces[c + 512]) isolatedSurfaces++;
                if (x > 0) largestStep = Math.max(largestStep, Math.abs(h - allHeights[z * 512 + x - 1]));
                if (z > 0) largestStep = Math.max(largestStep, Math.abs(h - allHeights[(z - 1) * 512 + x]));
            }
            require(largestStep <= 12, "GPU interpolation has no abrupt biome/cell/chunk jumps: " + largestStep);
            double edgeFraction = surfaceEdges / (2.0 * 511 * 512);
            require(edgeFraction < 0.12 && isolatedSurfaces < 512 * 512 / 100, "surface transitions form coherent regions without per-block scatter");
            System.out.printf(Locale.ROOT, "QA_EVT {\"event\":\"coherent_gpu_surface_borders\",\"status\":\"pass\",\"context\":{\"edge_fraction\":%.5f,\"isolated_columns\":%d}}%n", edgeFraction, isolatedSurfaces);
            // Region boundary compared with the independent chunk dispatch on its far side.
            for (int z = 0; z < 32; z++) {
                var neighbor = nativeTerrain.sampleColumns(request(profile, 0, z));
                for (int localZ = 0; localZ < 16; localZ++) require(Math.abs(allHeights[(z * 16 + localZ) * 512 + 511] - neighbor.heights()[localZ * 16]) <= 12, "continuous height across region boundary");
            }
            System.out.printf(Locale.ROOT, "QA_EVT {\"event\":\"minecraft_biome_mca_decode\",\"status\":\"pass\",\"context\":{\"chunks\":1024,\"biomes\":%d,\"largest_height_step\":%d,\"generation_ms\":%.3f,\"gpu_ms\":%.3f,\"assembly_ms\":%.3f,\"write_ms\":%.3f,\"bytes\":%d}}%n", regionBiomes.size(), largestStep, generationMs, report.gpuNanos()/1e6, report.assemblyNanos()/1e6, report.writeNanos()/1e6, report.bytes());
        } finally {
            try (var paths = Files.walk(directory)) { for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    private static TerrainRequest request(BiomeTerrainProfile profile, int x, int z) { return new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, 0.008F, profile.nativeId()); }
    private static int index(BiomeTerrainProfile profile, BlockState state) { return Arrays.asList(profile.materials()).indexOf(state); }
    private static void require(boolean condition, String description) { if (!condition) throw new AssertionError(description); }
}
