package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.ChunkPos;
import java.nio.file.Path;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/** Minecraft owns chunk lifecycle; Rust owns every terrain block decision. */
public final class RetinaChunkGenerator extends ChunkGenerator {
    public static final MapCodec<RetinaChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(RetinaChunkGenerator::getBiomeSource),
            Codec.INT.optionalFieldOf("min_y", -64).forGetter(generator -> generator.minY),
            Codec.intRange(16, 4096).optionalFieldOf("height", 384).forGetter(generator -> generator.height),
            Codec.floatRange(-4096, 4096).optionalFieldOf("base_height", 64.0F).forGetter(generator -> generator.baseHeight),
            Codec.floatRange(0, 4096).optionalFieldOf("amplitude", 48.0F).forGetter(generator -> generator.amplitude),
            Codec.floatRange(0.000001F, 1).optionalFieldOf("frequency", 0.008F).forGetter(generator -> generator.frequency),
            Codec.STRING.validate(mode -> mode.equals("mca") || mode.equals("chunk")
                    ? DataResult.success(mode) : DataResult.error(() -> "Retina mode must be mca or chunk"))
                    .optionalFieldOf("mode", "mca").forGetter(generator -> generator.mode)
    ).apply(instance, RetinaChunkGenerator::new));

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors())),
            Thread.ofPlatform().daemon().name("retina-chunk-", 0).factory());
    private final int minY;
    private final int height;
    private final float baseHeight;
    private final float amplitude;
    private final float frequency;
    private final String mode;
    private volatile TemporaryRegions previews;
    private PalettedContainerFactory containerFactory;
    private volatile BiomeTerrainProfile profile;
    private long worldSeed;
    private final GenerationMetrics metrics = new GenerationMetrics();
    private final AtomicBoolean firstChunk = new AtomicBoolean();
    private final Map<HeightKey, NativeTerrain.Columns> heightCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<HeightKey, NativeTerrain.Columns> eldest) {
            return size() > 2048;
        }
    });

    public RetinaChunkGenerator(BiomeSource biomes, int minY, int height,
                                float baseHeight, float amplitude, float frequency, String mode) {
        super(biomes);
        if (minY % 16 != 0 || height % 16 != 0) {
            throw new IllegalArgumentException("Retina generation bounds must align with 16-block sections");
        }
        Math.addExact(minY, height);
        this.minY = minY;
        this.height = height;
        this.baseHeight = baseHeight;
        this.amplitude = amplitude;
        this.frequency = frequency;
        this.mode = mode;
        if (!(biomes instanceof FixedBiomeSource) && !(biomes instanceof RetinaBiomeSource)) {
            throw new IllegalArgumentException("Retina requires a fixed or Retina GPU biome source");
        }
    }

    public void bindWorld(RegistryAccess registry, long seed) {
        bindWorld(registry, seed, PalettedContainerFactory.create(registry));
    }

    void bindWorld(HolderLookup.Provider registry, long seed, PalettedContainerFactory factory) {
        closePreviews();
        heightCache.clear();
        biomeCache.clear();
        containerFactory = factory;
        worldSeed = seed;
        if (getBiomeSource() instanceof RetinaBiomeSource biomes) {
            profile = BiomeTerrainProfile.load(registry, biomes, minY, height, seed);
            biomes.bind((x, y, z) -> biomeAt(x * 4, y * 4, z * 4));
        }
        if (regionMode()) previews = new TemporaryRegions(request(seed, 0, 0), regionBiome(), profile, metrics, TemporaryRegions.MAX_REGIONS);
    }

    public void closePreviews() {
        var cache = previews;
        if (cache != null) cache.close();
    }

    NativeTerrain.RegionReport publishPreview(ChunkPos position, Path destination) {
        var cache = previews;
        return cache == null ? null : cache.publishIfPresent(position, destination);
    }

    public BiomeTerrainProfile profile() { return profile; }

    public Holder<Biome> biomeAt(int x, int z) {
        var source = (RetinaBiomeSource) getBiomeSource();
        var columns = columns(worldSeed, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return source.biomes().get(columns.biome(Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)));
    }

    private final Map<HeightKey, byte[]> biomeCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<HeightKey, byte[]> eldest) { return size() > 2048; }
    });

    public Holder<Biome> biomeAt(int x, int y, int z) {
        var data = biomeSamples(worldSeed, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        int layer = Math.clamp(Math.floorDiv(y - minY, 4), 0, height / 4 - 1);
        int index = layer * 16 + Math.floorMod(Math.floorDiv(z, 4), 4) * 4 + Math.floorMod(Math.floorDiv(x, 4), 4);
        return profile.biomes().get(Byte.toUnsignedInt(data[index]));
    }

    private byte[] biomeSamples(long seed, int x, int z) {
        var key = new HeightKey(seed, x, z);
        var data = biomeCache.get(key);
        if (data == null) {
            var cache = previews;
            data = cache == null ? NativeTerrain.instance().sampleBiomes(request(seed, x, z)) : cache.biomes(new ChunkPos(x, z));
            biomeCache.put(key, data);
        }
        return data;
    }

    private NativeTerrain.Columns columns(long seed, int chunkX, int chunkZ) {
        var key = new HeightKey(seed, chunkX, chunkZ);
        var columns = heightCache.get(key);
        if (columns == null) {
            var cache = previews;
            columns = cache == null ? NativeTerrain.instance().sampleColumns(request(seed, chunkX, chunkZ))
                    : cache.columns(new ChunkPos(chunkX, chunkZ));
            heightCache.put(key, columns);
        }
        return columns;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState random, Blender blender, StructureManager structures, ChunkAccess chunk) {
        if (profile == null) {
            if (!regionMode()) return super.createBiomes(random, blender, structures, chunk);
            var fixed = getBiomeSource().possibleBiomes().iterator().next();
            chunk.fillBiomesFromNoise((x, y, z) -> fixed);
            return CompletableFuture.completedFuture(chunk);
        }
        java.util.function.Supplier<ChunkAccess> fill = () -> {
            var position = chunk.getPos();
            var samples = biomeSamples(seed(random), position.x(), position.z());
            chunk.fillBiomesFromNoise((x, y, z) -> profile.biomes().get(Byte.toUnsignedInt(samples[
                    Math.clamp(y - Math.floorDiv(minY, 4), 0, height / 4 - 1) * 16 + Math.floorMod(z, 4) * 4 + Math.floorMod(x, 4)])));
            return chunk;
        };
        // DH runs its own thread pool and checks/consumes completed generation futures.
        return regionMode() ? CompletableFuture.completedFuture(fill.get()) : CompletableFuture.supplyAsync(fill, WORKERS);
    }

    public boolean regionMode() { return mode.equals("mca"); }

    public String mode() { return mode; }

    public String regionBiome() {
        return getBiomeSource().possibleBiomes().iterator().next().unwrapKey().orElseThrow().identifier().toString();
    }

    public GenerationMetrics metrics() {
        return metrics;
    }

    public String backend() {
        return NativeTerrain.instance().backend();
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender,
            RandomState randomState, StructureManager structures, BiomeManager biomes,
            WorldGenRegion carverRegion, Set<Holder<Biome>> possibleBiomes) {
        var cache = previews;
        if (regionMode() && cache != null) {
            long started = System.nanoTime();
            var saved = SerializableChunkData.parse(chunk, containerFactory, cache.read(chunk.getPos()));
            if (saved == null) throw new IllegalStateException("Temporary MCA has no chunk status: " + chunk.getPos());
            for (var section : saved.sectionData()) {
                if (section.chunkSection() != null) chunk.getSections()[chunk.getSectionIndexFromSectionY(section.y())] = section.chunkSection();
            }
            saved.heightmaps().forEach(chunk::setHeightmap);
            if (Boolean.getBoolean("retina.qa") && firstChunk.compareAndSet(false, true)) Retina.LOGGER.info(
                    "QA_EVT {\"event\":\"minecraft_temporary_mca_chunk\",\"status\":\"pass\",\"context\":{\"x\":{},\"z\":{},\"ms\":{}}}",
                    chunk.getPos().x(), chunk.getPos().z(), (System.nanoTime() - started) / 1e6);
            return CompletableFuture.completedFuture(chunk);
        }
        long requestedAt = System.nanoTime();
        var position = chunk.getPos();
        var request = request(seed(randomState), position.x(), position.z());
        metrics.begin();
        return CompletableFuture.supplyAsync(() -> {
            try {
                long nativeStart = System.nanoTime();
                try (var data = NativeTerrain.instance().generate(request)) {
                    long nativeFinished = System.nanoTime();
                    heightCache.put(new HeightKey(request.seed(), request.chunkX(), request.chunkZ()), data.columns());
                    convert(chunk, request, data);
                    long finished = System.nanoTime();
                    metrics.completed(finished - requestedAt, nativeFinished - nativeStart, finished - nativeFinished);
                    if (Boolean.getBoolean("retina.qa") && firstChunk.compareAndSet(false, true)) {
                        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_gpu_chunk\",\"status\":\"pass\",\"context\":{\"x\":{},\"z\":{},\"ms\":{}}}",
                                position.x(), position.z(), (finished - requestedAt) / 1_000_000.0);
                    }
                }
                return chunk;
            } catch (Throwable error) {
                metrics.failed();
                throw error;
            }
        }, WORKERS);
    }

    private void convert(ChunkAccess chunk, TerrainRequest request, NativeTerrain.ChunkData data) {
        var materials = profile == null ? new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()} : profile.materials();
        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            int sectionMinY = chunk.getMinY() + sectionIndex * 16;
            section.acquire();
            try {
                for (int y = 0; y < 16; y++) {
                    int nativeY = sectionMinY + y - request.minY();
                    if (nativeY < 0 || nativeY >= request.height()) continue;
                    long offset = nativeY * 256L;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            byte material = data.blocks().get(JAVA_BYTE, offset + z * 16L + x);
                            section.setBlockState(x, y, z, materials[Byte.toUnsignedInt(material)], false);
                        }
                    }
                }
            } finally {
                section.release();
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }

    public TerrainRequest request(long seed, int chunkX, int chunkZ) {
        return new TerrainRequest(seed, chunkX, chunkZ, minY, height, baseHeight, amplitude, frequency, profile == null ? 0 : profile.nativeId());
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor bounds, RandomState state) {
        var column = getBaseColumn(x, z, bounds, state);
        for (int y = bounds.getMinY() + bounds.getHeight() - 1; y >= bounds.getMinY(); y--) {
            if (type.isOpaque().test(column.getBlock(y))) return y + 1;
        }
        return bounds.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor bounds, RandomState state) {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        var cache = previews;
        int index = Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16);
        BlockState[] preview = cache == null ? null : cache.baseColumn(columns(seed(state), chunkX, chunkZ), index);
        var data = preview == null ? NativeTerrain.instance().column(request(seed(state), chunkX, chunkZ), index) : null;
        var palette = profile == null ? new BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()} : profile.materials();
        var states = new BlockState[bounds.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int nativeY = bounds.getMinY() + i - minY;
            states[i] = nativeY >= 0 && nativeY < height ? (preview == null ? palette[Byte.toUnsignedInt(data[nativeY])] : preview[nativeY]) : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(bounds.getMinY(), states);
    }

    @SuppressWarnings("deprecation")
    private static long seed(RandomState state) {
        return state.seed();
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState state, long seed) {
        return ChunkGeneratorStructureState.createForFlat(state, seed, getOrigin(state), getBiomeSource(), Stream.empty());
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) { }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) { }

    @Override
    public int getGenDepth() { return height; }

    @Override
    public int getMinY() { return minY; }

    @Override
    public int getSeaLevel() { return profile == null ? minY : profile.seaLevel(); }

    @Override
    public int getSpawnHeight(LevelHeightAccessor bounds) {
        return Math.min(bounds.getMinY() + bounds.getHeight() - 1, (int) Math.ceil(baseHeight + amplitude) + 1);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState state, BlockPos pos, SamplerContext context) {
        var stats = metrics.snapshot();
        lines.add(String.format(java.util.Locale.ROOT, "Retina: %.1f chunks/s, %.2f ms/chunk", stats.chunksPerSecond(), stats.msPerChunk()));
    }

    private record HeightKey(long seed, int x, int z) { }
}
