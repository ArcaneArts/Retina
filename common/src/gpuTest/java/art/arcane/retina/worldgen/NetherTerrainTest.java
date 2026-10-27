package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Actual GPU Nether terrain, including independent column queries and negative region coordinates. */
public final class NetherTerrainTest {
    private static final long SEED = 84723612;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); NativeRegistryLoadingTest.registerCodecs();
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER).value();
            var biomes = MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            var exported = export(registry, biomes, settings, 0, 256);
            var json = JsonParser.parseString(exported.json()).getAsJsonObject();
            require(json.getAsJsonArray("cave_noises").isEmpty(), "no borrowed Overworld cave noise");
            for (int i : new int[]{2, 3}) require(json.getAsJsonArray("noises").get(i).getAsJsonObject().get("amplitude").getAsInt() == 0,
                    "constant climate channels have no borrowed noise");
            var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomes,
                    registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER));
            try(var saved=fixture(registry,generator,exported,SEED)) {
                var terrain = NativeTerrain.instance();
                long air = 0, lava = 0, solid = 0, multi = 0;
                var seenBiomes = new HashSet<Integer>();
                int[] queries = new int[16 * 16 * 3 * 3];
                int point = 0;
                for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) for (int y : new int[]{0, 64, 255}) {
                    // Avoid probing only integer Perlin corners at the Nether's power-of-two wavelengths.
                    queries[point++] = x * 487 + 137; queries[point++] = y; queries[point++] = z * 509 + 83;
                }
                var queried = terrain.queryPoints(saved.request(0,0), queries, false);
                for (int i = 0; i < queried.length; i += 3) {
                    require(queried[i] == queried[i + 1] && queried[i] == queried[i + 2], "vanilla Nether climate is height-independent");
                    seenBiomes.add(queried[i]);
                }
                require(seenBiomes.size() == 5, "all five source climate biomes occur in sampled domain: " + seenBiomes);
                var slices = new BufferedImage(16 * 8, 256, BufferedImage.TYPE_INT_RGB);
                for (int i = 0; i < 12; i++) {
                    int x = i < 8 ? i - 4 : new int[]{-32, -1, 31, 32}[i - 8];
                    int z = i < 8 ? 0 : new int[]{0, 31, -32, 32}[i - 8];
                    var request = saved.request(x,z);
                    try (var chunk = terrain.generate(request)) {
                        short[] blocks = chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                        long[] counts = checkVanilla(blocks, exported.materials());
                        air += counts[0]; lava += counts[1]; solid += counts[2]; multi += counts[3];
                        for (int c : new int[]{0, 136, 255}) {
                            short[] column = terrain.column(request, c);
                            for (int y = 0; y < 256; y++) require(column[y] == blocks[y * 256 + c], "independent base-column parity at " + x + "," + z + ":" + y);
                            require(chunk.heights()[c] == 128, "height query sees the bedrock roof");
                        }
                        for (short biome : terrain.sampleBiomes(request)) {
                            int id = Short.toUnsignedInt(biome);
                            require(id < exported.biomes().size(), "valid source biome palette"); seenBiomes.add(id);
                        }
                        if (i < 8) for (int y = 0; y < 256; y++) for (int lx = 0; lx < 16; lx++)
                            slices.setRGB(i * 16 + lx, 255 - y, color(exported.materials()[Short.toUnsignedInt(blocks[y * 256 + 8 * 16 + lx])]));
                        try (var repeat = terrain.generate(request)) {
                            require(Arrays.equals(blocks, repeat.blocks().toArray(ValueLayout.JAVA_SHORT)), "deterministic repeat generation");
                        }
                    }
                }
                require(air > 1000 && lava > 100 && solid > 1000 && multi > 10, "Nether has caverns, lava seas, solid terrain and multiple vertical surfaces: " + List.of(air, lava, solid, multi));
                for (var key : List.of(Biomes.NETHER_WASTES, Biomes.CRIMSON_FOREST, Biomes.WARPED_FOREST, Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS)) {
                    var fixed = new FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(key));
                    var data = export(registry, fixed, settings, 0, 256);
                    try(var selected=fixture(registry,new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(fixed,
                            net.minecraft.core.Holder.direct(settings)),data,SEED)) {
                        var palette = data.materials();
                        var observed = new HashSet<net.minecraft.world.level.block.Block>();
                        for (int x : new int[]{-32, -1, 12, 32}) {
                            var request = selected.request(x,0);
                            try (var chunk = terrain.generate(request)) {
                                short[] blocks = chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                                checkVanilla(blocks, palette);
                                for (short material : blocks) observed.add(palette[Short.toUnsignedInt(material)].getBlock());
                            }
                            for (short id : terrain.sampleBiomes(request)) require(id == 0, "fixed Nether biome remains fixed at every height");
                        }
                        var expected = key.equals(Biomes.CRIMSON_FOREST) ? Blocks.CRIMSON_NYLIUM
                                : key.equals(Biomes.WARPED_FOREST) ? Blocks.WARPED_NYLIUM
                                : key.equals(Biomes.SOUL_SAND_VALLEY) ? Blocks.SOUL_SAND
                                : key.equals(Biomes.BASALT_DELTAS) ? Blocks.BASALT : Blocks.NETHERRACK;
                        require(observed.contains(expected), "source biome surface material occurs for " + key + ": " + expected);
                    }
                }
                Files.createDirectories(Path.of("build/nether"));
                Files.copy(saved.file(),Path.of("build/nether/retina.dat"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ImageIO.write(slices, "png", Path.of("build/nether/terrain-slice.png").toFile());
                var stats = new JsonObject(); stats.addProperty("air", air); stats.addProperty("lava", lava); stats.addProperty("solid", solid);
                stats.addProperty("multi_surface_columns", multi); stats.addProperty("sampled_biomes", seenBiomes.size());
                Files.writeString(Path.of("build/nether/terrain-stats.json"), stats.toString());
                System.out.println("QA_EVT {\"event\":\"nether_gpu_terrain\",\"status\":\"pass\",\"context\":" + stats + "}");
                NetherBoundsTest.check(registry);
                NetherCarverTest.check(registry);
                NetherVerticalBiomeTest.check(registry, exported);
                if (Arrays.asList(args).contains("--region")) NetherRegionTest.check(exported, saved);
            }
        }
    }

    static TerrainProfileData export(RegistryAccess registry, BiomeSource biomes, NoiseGeneratorSettings settings, int minY, int height) {
        return export(registry,biomes,settings,minY,height,SEED);
    }

    static TerrainProfileData export(RegistryAccess registry, BiomeSource biomes, NoiseGeneratorSettings settings, int minY, int height,long seed) {
        var data = TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(biomes), minY, height, seed, settings,
                StructureProfile.Context.NONE, true, false, true);
        var json = JsonParser.parseString(data.json()).getAsJsonObject();
        // Terrain-only fixture: decoration/structures are implemented in later goal cycles.
        json.add("decorations", new JsonArray()); json.add("ores", new JsonArray());
        for (var entry : json.getAsJsonArray("biomes")) {
            entry.getAsJsonObject().add("ores", new JsonArray());
            entry.getAsJsonObject().add("decorations", new JsonArray());
        }
        return new TerrainProfileData(data.seaLevel(), data.materials(), data.biomes(), json.toString());
    }

    static DefinitionFixture fixture(RegistryAccess registry,net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator,
                                     TerrainProfileData data,long seed) throws java.io.IOException {
        var type=registry.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.NETHER).value();
        return DefinitionFixture.create(new GenerationSource(net.minecraft.world.level.Level.NETHER,generator,type),data,seed);
    }

    static DefinitionFixture fixture(JsonObject data,long seed) throws java.io.IOException {
        return fixture(data,seed,null);
    }

    static DefinitionFixture fixture(JsonObject data,long seed,NativeWorldDefinition.ProgramExecution execution) throws java.io.IOException {
        return DefinitionFixture.create("minecraft:the_nether",data,
                new TerrainRequest(seed,0,0,0,256,64,48,.008f,0),0,256,false,true,execution);
    }

    private static long[] checkVanilla(short[] blocks, BlockState[] palette) {
        long air = 0, lava = 0, solid = 0, multi = 0;
        for (int c = 0; c < 256; c++) {
            int transitions = 0; boolean previous = true;
            for (int y = 0; y < 256; y++) {
                var state = palette[Short.toUnsignedInt(blocks[y * 256 + c])];
                if (y == 0 || y == 127) require(state.is(Blocks.BEDROCK), "bedrock boundary at " + y + ": " + state);
                if (y >= 128) require(state.isAir(), "empty generated space above roof at " + y + ": " + state);
                else {
                    boolean now = !state.isAir() && state.getFluidState().isEmpty();
                    if (now != previous) transitions++; previous = now;
                    if (state.isAir()) air++; else if (state.is(Blocks.LAVA)) {lava++; require(y < 32, "terrain lava below sea level");} else solid++;
                    require(!state.is(Blocks.WATER) && !state.is(Blocks.ICE) && !state.is(Blocks.GRASS_BLOCK)
                            && !state.is(Blocks.DEEPSLATE), "no unintended Overworld materials: " + state);
                }
            }
            if (transitions >= 4) multi++;
        }
        return new long[]{air, lava, solid, multi};
    }

    private static int color(BlockState state) {
        if (state.isAir()) return 0x11131a;
        if (state.is(Blocks.LAVA)) return 0xff7518;
        if (state.is(Blocks.BEDROCK)) return 0x777777;
        if (state.is(Blocks.WARPED_NYLIUM)) return 0x1a9a88;
        if (state.is(Blocks.CRIMSON_NYLIUM)) return 0xe24242;
        if (state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL)) return 0x725743;
        if (state.is(Blocks.BASALT) || state.is(Blocks.BLACKSTONE)) return 0x46414c;
        return 0x83382e;
    }

    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
