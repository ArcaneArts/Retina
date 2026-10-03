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
        var importedCell=com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject().getAsJsonObject("registry_program").getAsJsonArray("terrain_cell");
        require(importedCell.get(0).getAsInt()==4 && importedCell.get(1).getAsInt()==8, "vanilla registered interpolation cells are exported as 4x8x4");
        Files.writeString(java.nio.file.Path.of("build/registry-biome-profile.json"), profile.json());
        var nativeTerrain = NativeTerrain.instance();
        require(nativeTerrain.registerProfile(profile.json()) == profile.nativeId(), "identical profiles reuse the GPU upload");
        RegistryGpuProgramIntegrationChecks.checkSurfaceInterpolation(profile);
        RegistryGpuProgramIntegrationChecks.checkNoiseSurfacePreservation(profile);
        var seenBiomes = new HashSet<Integer>();
        var seenMaterials = new HashSet<Integer>();
        var decorations = com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject().getAsJsonArray("decorations");
        require(decorations.size() > 20, "exported actual registered vegetation recipes");
        var exportedKinds = new HashSet<String>();
        for (var recipe : decorations) exportedKinds.add(recipe.getAsJsonObject().get("kind").getAsString());
        require(exportedKinds.containsAll(List.of("tree", "plant")), "exported trees and plants");
        checkJungle(profile, nativeTerrain);
        var decorationCounts = new java.util.concurrent.atomic.AtomicLongArray(5);
        var chunks = new HashMap<ChunkPos, NativeTerrain.Columns>();
        var chunkBlocks = new HashMap<ChunkPos, short[]>();
        try (var workers = Executors.newFixedThreadPool(16)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 128; i++) {
                final int index = i;
                jobs.add(workers.submit(() -> {
                    var r = request(profile, (index % 16 - 8) * 37, (index / 16 - 4) * 41);
                    try (var data = nativeTerrain.generate(r)) {
                        var query = nativeTerrain.sampleColumns(r);
                        require(Arrays.equals(query.heights(), data.heights()) && Arrays.equals(query.packed(), data.columns().packed()), "concurrent queries agree with generation");
                        var bytes = data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);
                        for (int blockIndex = 0; blockIndex < bytes.length; blockIndex++) {
                            var state = profile.materials()[Short.toUnsignedInt(bytes[blockIndex])];
                            if (state.is(net.minecraft.tags.BlockTags.LOGS)) decorationCounts.incrementAndGet(0);
                            if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                                decorationCounts.incrementAndGet(1);
                                require(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DISTANCE) <= 6, "leaves have a supported log distance");
                                require(!state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT), "leaves can decay after chopping");
                            }
                            if (state.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock) {
                                decorationCounts.incrementAndGet(2);
                                if (state.is(net.minecraft.tags.BlockTags.FLOWERS)) decorationCounts.incrementAndGet(3);
                                if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
                                    decorationCounts.incrementAndGet(4);
                                    var half = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
                                    int otherIndex = blockIndex + (half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? 256 : -256);
                                    require(otherIndex >= 0 && otherIndex < bytes.length, "plant pair fits world bounds");
                                    var other = profile.materials()[Short.toUnsignedInt(bytes[otherIndex])];
                                    require(other.is(state.getBlock()) && other.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) != half, "both halves of tall plants match");
                                }
                            }
                        }
                        synchronized (seenBiomes) {
                            for (int packed : query.packed()) seenBiomes.add(packed & 65535);
                            for (short material : bytes) seenMaterials.add(Short.toUnsignedInt(material));
                        }
                    }
                }));
            }
            for (var job : jobs) job.get();
        }
        require(seenBiomes.size() >= 6, "multiple climate biomes are reachable: " + seenBiomes);
        require(seenMaterials.contains(index(profile, Blocks.WATER.defaultBlockState())), "oceans contain registered water");
        require(seenMaterials.contains(index(profile, Blocks.SAND.defaultBlockState())), "desert/warm ocean surfaces contain registered sand");
        require(seenMaterials.contains(index(profile, Blocks.SNOW_BLOCK.defaultBlockState())) || seenMaterials.contains(index(profile, Blocks.SNOW.defaultBlockState())), "cold land contains snow");
        System.out.println("QA_EVT {\"event\":\"parallel_registry_gpu_biomes\",\"status\":\"pass\",\"context\":{\"chunks\":128,\"biomes\":" + seenBiomes.size() + ",\"materials\":" + seenMaterials.size() + "}}");

        for (int i = 0; i < 5; i++) require(decorationCounts.get(i) > 0, "generated registry decoration kind " + i);
        System.out.println("QA_EVT {\"event\":\"registry_rust_decorations\",\"status\":\"pass\",\"context\":{\"recipes\":" + decorations.size() + ",\"logs\":" + decorationCounts.get(0) + ",\"leaves\":" + decorationCounts.get(1) + ",\"plants\":" + decorationCounts.get(2) + ",\"flowers\":" + decorationCounts.get(3) + ",\"double_plant_halves\":" + decorationCounts.get(4) + "}}");

        var changedNoise = com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
        var temperatureNoise = changedNoise.getAsJsonArray("noises").get(0).getAsJsonObject();
        temperatureNoise.addProperty("frequency", temperatureNoise.get("frequency").getAsDouble() * 2);
        for(var n : changedNoise.getAsJsonObject("registry_program").getAsJsonArray("noises")) {var noise=n.getAsJsonObject();noise.addProperty("frequency",noise.get("frequency").getAsDouble()*2);}
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

        // Sample isolated chunk queries first, then generate larger 3x3 halo fields.
        // The same global density cells must give identical heights in either layout.
        // Density-first interpolation need not equal a blend of extracted corner heights.
        var noLakes=com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
        for(var b:noLakes.getAsJsonArray("biomes")) b.getAsJsonObject().add("lakes",com.google.gson.JsonParser.parseString("[0,0]"));
        int smoothId=nativeTerrain.registerProfile(noLakes.toString()), farStep=0, interpolationError=0, comparedColumns=0;
        for(int origin:new int[]{-65536,-4096,4096,65536,1000000}) {
            var tiles=new HashMap<ChunkPos,int[]>(); int center=Math.floorDiv(origin,16);
            for(int dz=-2;dz<=3;dz++)for(int dx=-2;dx<=3;dx++) {
                var r=request(profile,center+dx,center+dz);
                tiles.put(new ChunkPos(r.chunkX(),r.chunkZ()),nativeTerrain.sampleColumns(new TerrainRequest(r.seed(),r.chunkX(),r.chunkZ(),r.minY(),r.height(),r.baseHeight(),r.amplitude(),r.frequency(),smoothId)).heights());
            }
            for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int bx=(center+dx)*16+x,bz=(center+dz)*16+z;
                int actual=height(tiles,bx,bz);
                farStep=Math.max(farStep,Math.abs(actual-height(tiles,bx+1,bz)));
                farStep=Math.max(farStep,Math.abs(actual-height(tiles,bx,bz+1)));
            }
            for(var offset:List.of(new ChunkPos(-2,-2),new ChunkPos(0,0),new ChunkPos(2,2),new ChunkPos(-2,2),new ChunkPos(2,-2))) {
                var r=request(profile,center+offset.x(),center+offset.z());
                try(var generated=nativeTerrain.generate(new TerrainRequest(r.seed(),r.chunkX(),r.chunkZ(),r.minY(),r.height(),r.baseHeight(),r.amplitude(),r.frequency(),smoothId))) {
                    int[] isolated=tiles.get(new ChunkPos(r.chunkX(),r.chunkZ())), halo=generated.heights();
                    for(int c=0;c<256;c++) {
                        interpolationError=Math.max(interpolationError,Math.abs(isolated[c]-halo[c]));
                        comparedColumns++;
                    }
                }
            }
        }
        require(interpolationError==0, "isolated and halo density interpolation agree at distant chunk borders: "+interpolationError);
        System.out.println("QA_EVT {\"event\":\"distant_gpu_density_interpolation\",\"status\":\"pass\",\"context\":{\"columns\":"+comparedColumns+",\"largest_step\":"+farStep+",\"interpolation_error\":"+interpolationError+"}}");

        // Capture the per-chunk path before the one-descriptor region dispatch.
        for (int z : new int[]{0, 15, 31}) for (int x : new int[]{-32, -17, -1}) {
            var pos = new ChunkPos(x, z);
            try (var data = nativeTerrain.generate(request(profile, x, z))) {
                chunks.put(pos, data.columns());
                chunkBlocks.put(pos, data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT));
            }
        }
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
            require(report.stages().chunks()==1024 && report.stages().gpuJobs()==1, "MCA report isolates one cave GPU submission");
            if(report.stages().gpuMeasured())for(int stage:new int[]{NativeTimings.HEIGHT,NativeTimings.CAVE_DENSITY,NativeTimings.CAVE_MASK})
                require(report.stages().nanos(stage)>0, "region keeps real device timestamps: "+stage);
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
                        var bytes = data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);
                        if (chunkBlocks.containsKey(pos)) require(Arrays.equals(bytes, chunkBlocks.get(pos)), "decorations agree before and after region dispatch at " + pos);
                        var sections = tag.getListOrEmpty("sections");
                        var biomeSamples = nativeTerrain.sampleBiomes(r);
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
                                String expected = profile.biomes().get(Short.toUnsignedInt(biomeSamples[sectionIndex * 64 + i])).unwrapKey().orElseThrow().identifier().toString();
                                require(actual.equals(expected), "MCA biome sample agrees with GPU");
                                regionBiomes.add(actual);
                            }
                            for (int y = 0; y < 16; y++) for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                                int layer = sectionIndex * 16 + y;
                                int c = localZ * 16 + localX;
                                var state = blocks.get(localX, y, localZ);
                                require(state.equals(profile.materials()[Short.toUnsignedInt(bytes[layer * 256 + c])]), "MCA block states match per-chunk Rust assembly at " + pos + "/" + localX + "," + (layer - 64) + "," + localZ + ": mca=" + state + ", chunk=" + profile.materials()[Short.toUnsignedInt(bytes[layer * 256 + c])]);
                                for (var type : Heightmap.Types.values()) {
                                    if (type.isOpaque().test(state)) actualHeights[type.ordinal()][c] = layer + 1;
                                }
                            }
                        }
                        for (var type : Heightmap.Types.values()) {
                            var packed = new SimpleBitStorage(9, 256, tag.getCompoundOrEmpty("Heightmaps").getLongArray(type.getSerializationKey()).orElseThrow());
                            for (int c = 0; c < 256; c++) require(packed.get(c) == actualHeights[type.ordinal()][c], "stored " + type + " heightmap matches actual materials at " + pos + "/" + c + ": stored " + packed.get(c) + ", actual " + actualHeights[type.ordinal()][c] + ", GPU " + query.heights()[c] + ", packed " + query.packed()[c]);
                        }
                        for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                            int global = (z * 16 + localZ) * 512 + (x + 32) * 16 + localX;
                            allHeights[global] = query.heights()[localZ * 16 + localX];
                            allSurfaces[global] = query.materials()[localZ * 16 + localX] & 65535;
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
            // The topmost solid interval can change at a cliff or cave mouth;
            // a continuous 3D field does not impose a fixed height-difference bound.
            double edgeFraction = surfaceEdges / (2.0 * 511 * 512);
            require(edgeFraction < 0.12 && isolatedSurfaces < 512 * 512 / 100, "surface transitions form coherent regions without per-block scatter");
            System.out.printf(Locale.ROOT, "QA_EVT {\"event\":\"coherent_gpu_surface_borders\",\"status\":\"pass\",\"context\":{\"edge_fraction\":%.5f,\"isolated_columns\":%d}}%n", edgeFraction, isolatedSurfaces);
            // A distinct profile ID bypasses the region's column cache. Compare
            // independent chunk dispatches at every block of the last region column,
            // then sample its neighboring chunk to record the actual boundary slope.
            int independentId=nativeTerrain.registerProfile(profile.json()+"\n");
            int boundaryStep=0;
            for (int z = 0; z < 32; z++) {
                var r=request(profile,-1,z);
                var inside=nativeTerrain.sampleColumns(new TerrainRequest(r.seed(),r.chunkX(),r.chunkZ(),r.minY(),r.height(),r.baseHeight(),r.amplitude(),r.frequency(),independentId));
                var neighbor = nativeTerrain.sampleColumns(request(profile, 0, z));
                for (int localZ = 0; localZ < 16; localZ++) {
                    for(int localX=0;localX<16;localX++)require(allHeights[(z*16+localZ)*512+496+localX]==inside.heights()[localZ*16+localX], "independent chunk interpolation agrees with region boundary");
                    boundaryStep=Math.max(boundaryStep,Math.abs(allHeights[(z * 16 + localZ) * 512 + 511] - neighbor.heights()[localZ * 16]));
                }
            }
            System.out.println("QA_EVT {\"event\":\"independent_gpu_region_boundary\",\"status\":\"pass\",\"context\":{\"columns\":8192,\"largest_step\":"+boundaryStep+"}}");
            System.out.printf(Locale.ROOT, "QA_EVT {\"event\":\"minecraft_biome_mca_decode\",\"status\":\"pass\",\"context\":{\"chunks\":1024,\"biomes\":%d,\"largest_height_step\":%d,\"generation_ms\":%.3f,\"gpu_ms\":%.3f,\"assembly_ms\":%.3f,\"write_ms\":%.3f,\"bytes\":%d}}%n", regionBiomes.size(), largestStep, generationMs, report.gpuNanos()/1e6, report.assemblyNanos()/1e6, report.writeNanos()/1e6, report.bytes());
        } finally {
            try (var paths = Files.walk(directory)) { for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    private static void checkJungle(BiomeTerrainProfile profile, NativeTerrain nativeTerrain) throws Exception {
        var json = com.google.gson.JsonParser.parseString(profile.json()).getAsJsonObject();
        var jungle = json.getAsJsonArray("biomes").asList().stream().map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(b -> b.get("id").getAsString().equals("minecraft:jungle")).findFirst().orElseThrow();
        var shapes = new HashSet<String>(); var decorators = new HashSet<String>();
        for (var id : jungle.getAsJsonArray("decorations")) {
            var recipe = json.getAsJsonArray("decorations").get(id.getAsInt()).getAsJsonObject();
            if (!recipe.get("kind").getAsString().equals("tree")) continue;
            shapes.add(recipe.get("trunk_shape").getAsString() + "/" + recipe.get("foliage_shape").getAsString());
            for (var decorator : recipe.getAsJsonArray("decorators")) decorators.add(decorator.getAsJsonObject().get("kind").getAsString());
        }
        require(shapes.size() == 4 && decorators.containsAll(List.of("trunk_vine", "leaf_vine", "cocoa")), "jungle exports all four vanilla tree shapes and their decorators");
        json.remove("registry_program");
        var onlyJungle = new com.google.gson.JsonArray(); onlyJungle.add(jungle.deepCopy()); json.add("biomes", onlyJungle);
        int nativeId = nativeTerrain.registerProfile(json.toString());
        var blocks = new HashMap<ChunkPos, short[]>();
        java.util.function.Function<ChunkPos, short[]> load = pos -> blocks.computeIfAbsent(pos, p -> {
            try (var data = nativeTerrain.generate(new TerrainRequest(123456789L, p.x(), p.z(), -64, 384, 64, 48, .008F, nativeId))) {
                return data.blocks().toArray(java.lang.foreign.ValueLayout.JAVA_SHORT);
            }
        });
        long[] counts = new long[6];
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            var pos = new ChunkPos(x, z); var bytes = load.apply(pos);
            for (int i = 0; i < bytes.length; i++) {
                var state = profile.materials()[Short.toUnsignedInt(bytes[i])];
                if (state.is(Blocks.JUNGLE_LOG)) counts[0]++;
                if (state.is(Blocks.OAK_LOG)) counts[1]++;
                if (state.is(Blocks.JUNGLE_LEAVES)) counts[2]++;
                if (state.is(Blocks.OAK_LEAVES)) counts[3]++;
                if (state.is(Blocks.VINE)) counts[4]++;
                if (state.is(Blocks.COCOA)) {
                    counts[5]++;
                    var direction = state.getValue(net.minecraft.world.level.block.CocoaBlock.FACING);
                    int bx = pos.getMinBlockX() + i % 16 + direction.getStepX();
                    int bz = pos.getMinBlockZ() + (i % 256) / 16 + direction.getStepZ();
                    var support = load.apply(new ChunkPos(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16)));
                    var supportState = profile.materials()[Short.toUnsignedInt(support[i / 256 * 256 + Math.floorMod(bz, 16) * 16 + Math.floorMod(bx, 16)])];
                    require(supportState.is(net.minecraft.tags.BlockTags.JUNGLE_LOGS), "cocoa faces a real jungle log, including chunk borders");
                }
            }
        }
        for (int i = 0; i < counts.length; i++) require(counts[i] > 0, "jungle produces tree/decorator category " + i);
        var directory = Files.createTempDirectory("retina-jungle-mca-");
        try {
            var request = new TerrainRequest(123456789L, 0, 0, -64, 384, 64, 48, .008F, nativeId);
            nativeTerrain.generateRegion(request, directory.resolve("r.0.0.mca"), SharedConstants.getCurrentVersion().dataVersion().version(), "minecraft:jungle");
            var codec = PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
            try (var storage = new RegionFileStorage(new RegionStorageInfo("retina-jungle-test", Level.OVERWORLD, "chunk"), directory, false)) {
                for (var pos : List.of(new ChunkPos(0, 0), new ChunkPos(3, 3))) {
                    var expected = load.apply(pos); var sections = storage.read(pos).getListOrEmpty("sections");
                    for (int section = 0; section < 24; section++) {
                        var decoded = codec.parse(NbtOps.INSTANCE, sections.getCompound(section).orElseThrow().getCompoundOrEmpty("block_states")).getOrThrow();
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                            require(decoded.get(x, y, z).equals(profile.materials()[Short.toUnsignedInt(expected[(section * 16 + y) * 256 + z * 16 + x])]), "jungle trees, vines and cocoa match between chunk and region assembly at " + pos);
                    }
                }
            }
        } finally { try (var paths = Files.walk(directory)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
        System.out.println("QA_EVT {\"event\":\"registry_jungle_diversity_and_decorators\",\"status\":\"pass\",\"context\":{\"tree_shapes\":" + shapes.size() + ",\"vines\":" + counts[4] + ",\"cocoa\":" + counts[5] + "}}");
    }
    private static int height(Map<ChunkPos,int[]> tiles,int x,int z) {
        return tiles.get(new ChunkPos(Math.floorDiv(x,16),Math.floorDiv(z,16)))[Math.floorMod(z,16)*16+Math.floorMod(x,16)];
    }
    private static TerrainRequest request(BiomeTerrainProfile profile, int x, int z) { return new TerrainRequest(123456789L, x, z, -64, 384, 64, 48, 0.008F, profile.nativeId()); }
    private static int index(BiomeTerrainProfile profile, BlockState state) { return Arrays.asList(profile.materials()).indexOf(state); }
    private static void require(boolean condition, String description) { if (!condition) throw new AssertionError(description); }
}
