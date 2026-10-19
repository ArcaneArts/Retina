package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Actual OreFeature bulk-section writes and ScatteredOreFeature world writes share one reference world. */
final class NetherOreTest {
    static void check(RegistryAccess registry, TerrainProfileData exported, NoiseBasedChunkGenerator generator) throws Exception {
        var original = JsonParser.parseString(exported.json()).getAsJsonObject();
        var features = new ArrayList<AbstractOreFeature>(); var names = new ArrayList<String>();
        for (var entry : original.getAsJsonArray("decorations")) {
            var recipe = entry.getAsJsonObject();
            if (!recipe.has("nether") || !recipe.getAsJsonObject("nether").get("kind").getAsString().equals("ore")) continue;
            String name = recipe.get("source").getAsString(); names.add(name);
            features.add((AbstractOreFeature)NativeBlockFeatureIntegrationTest.resolve(registry, name));
        }
        require(features.size() == 10 && original.getAsJsonArray("ores").isEmpty(), "all loaded ores use the ordered stream exactly once");
        var host = new BlockMatchTest(Blocks.NETHERRACK);
        for (boolean scattered : List.of(false, true)) for (int size : new int[]{0, 1, 8, 33, 64}) for (float discard : new float[]{0, .5f, 1}) {
            var targets = List.of(new BlockReplacement(host, Blocks.NETHER_QUARTZ_ORE.defaultBlockState()));
            features.add(scattered ? new ScatteredOreFeature(targets, size, discard) : new OreFeature(targets, size, discard));
            names.add("custom/" + scattered + "/" + size + "/" + discard);
        }
        var rules = List.of(
                new TagMatchTest(BlockTags.BASE_STONE_NETHER),
                RuleTest.allOf(host, new HeightMatchTest(63, 64)),
                RuleTest.anyOf(new HeightMatchTest(64, 63), host),
                RuleTest.not(new BlockStateMatchTest(Blocks.LAVA.defaultBlockState())),
                RuleTest.allOf(new HeightMatchTest(Integer.MIN_VALUE, Integer.MAX_VALUE), new RandomBlockMatchTest(Blocks.NETHERRACK, .5f)),
                RuleTest.anyOf(new RandomBlockStateMatchTest(Blocks.NETHERRACK.defaultBlockState(), .25f), new RandomBlockMatchTest(Blocks.NETHERRACK, .5f)));
        for (int i = 0; i < rules.size(); i++) for (boolean scattered : List.of(false, true)) {
            // A target that matched but failed its exposure roll must not prevent later targets from trying.
            var targets = List.of(new BlockReplacement(rules.get(i), Blocks.NETHER_QUARTZ_ORE.defaultBlockState()),
                    new BlockReplacement(host, Blocks.NETHER_GOLD_ORE.defaultBlockState()));
            features.add(scattered ? new ScatteredOreFeature(targets, 33, .5f) : new OreFeature(targets, 33, .5f));
            names.add("rules/" + i + "/" + scattered);
        }
        var settings = new BiomeGenerationSettings.PlainBuilder();
        for (var feature : features) settings.addFeature(7, Holder.direct(new PlacedFeature(Holder.direct(feature), List.of())));
        var generation = settings.build();
        var palette = new LinkedHashMap<BlockState, Integer>(); for (var state : exported.materials()) palette.put(state, palette.size());
        var profiles = new JsonArray(); profiles.add(new JsonObject());
        var recipes = DecorationProfile.export(registry, exported.biomes().subList(0, 1), profiles, palette, original,
                Collections.newSetFromMap(new IdentityHashMap<>()), b -> generation);
        require(recipes.size() == features.size(), "all loaded and custom ore fixtures exported");
        DecorationProfile.finishPlacements(recipes, palette, GenerationBounds.noise(generator.generatorSettings().value().noiseSettings(), 0, 256), 32);
        original.add("decorations", recipes); original.add("ores", new JsonArray());
        for (var biome : original.getAsJsonArray("biomes")) {
            var b = biome.getAsJsonObject();
            for (String key : List.of("decorations", "ores", "carvers")) b.add(key, new JsonArray());
            b.remove("cave_features");
        }
        DecorationProfile.materialFlags(original, palette);
        var serialized = new JsonArray(); var carveable = new JsonArray();
        for (var state : palette.keySet()) { serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()); carveable.add(false); }
        original.add("materials", serialized); original.add("carveable", carveable); original.add("lighting", LightingProfile.export(palette.keySet(), false));
        var states = palette.keySet().toArray(BlockState[]::new);
        int cases = 0, accepted = 0; long writes = 0; var successes = new TreeMap<String, Integer>();
        for (var gap : List.of(Blocks.NETHERRACK.defaultBlockState(), Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            var data = original.deepCopy(); var compiler = new RegistryGpuProgram(registry, 0, 128, 32);
            var density = compiler.new Program(); density.roots.add(density.constant(1)); density.roots.add(density.constant(128));
            var material = compiler.new Program(); int y = material.node(29, 1, 0, 0);
            material.roots.add(material.node(23, y, material.constant(palette.get(gap) + 1), material.constant(data.get("stone").getAsInt() + 1), 63, 64));
            var gpu = data.getAsJsonObject("registry_program"); var programs = gpu.getAsJsonArray("programs");
            programs.set(1, density.finish()); programs.set(2, density.finish()); gpu.add("interpolations", new JsonArray());
            for (int i = 0; i < exported.biomes().size(); i++) programs.set(3 + i, material.finish());
            int profile = NativeTerrain.instance().registerProfile(data.toString());
            var request = new TerrainRequest(42, -2, -2, 0, 256, 64, 48, .008f, profile);
            var fixture = new Fixture(registry, request, states, gap);
            for (int id = 0; id < features.size(); id++) for (int height : new int[]{0, 1, 32, 63, 64, 127, 128, 255}) for (int seed = 0; seed < 4; seed++) {
                var at = new BlockPos(-17, height, -17); var world = new World(fixture);
                features.get(id).place(world.level, generator, new NativeBlockFeatureIntegrationTest.Stream(seed, false), at);
                var output = NativeTerrain.instance().decorationFeature(request, id, new int[]{at.getX(), at.getY(), at.getZ()}, seed);
                var actual = new HashMap<BlockPos, BlockState>();
                for (int i = 0; i < output.length; i += 4) actual.put(new BlockPos(output[i], output[i + 1], output[i + 2]), states[output[i + 3]]);
                actual.entrySet().removeIf(e -> e.getValue().equals(fixture.expected(e.getKey())));
                var expected = world.changed(at);
                require(actual.equals(expected), "ore parity " + names.get(id) + " gap=" + gap + " y=" + height + " seed=" + seed + ": "
                        + NativeBlockFeatureIntegrationTest.differences(expected, actual));
                if (!actual.isEmpty()) { accepted++; successes.merge(names.get(id), 1, Integer::sum); }
                cases++; writes += actual.size();
            }
        }
        require(accepted > 1000 && successes.keySet().containsAll(names.subList(0, 10)), "every loaded ore has accepted reference cases");
        var stats = new JsonObject(); stats.addProperty("cases", cases); stats.addProperty("accepted", accepted); stats.addProperty("writes", writes);
        stats.addProperty("configs", features.size()); stats.add("successes", new Gson().toJsonTree(successes));
        Files.writeString(Path.of("build/nether/ore-reference-stats.json"), stats.toString());
        System.out.println("QA_EVT {\"event\":\"nether_ores_reference\",\"status\":\"pass\",\"context\":" + stats + "}");
    }

    private static final class Fixture {
        final TerrainRequest request; final BlockState[] palette; final BlockState gap;
        final PalettedContainerFactory containers; final LevelHeightAccessor bounds = LevelHeightAccessor.create(0, 256);
        final Map<ChunkPos, LevelChunkSection[]> prototypes = new HashMap<>();
        Fixture(RegistryAccess registry, TerrainRequest request, BlockState[] palette, BlockState gap) {
            this.request = request; this.palette = palette; this.gap = gap; containers = PalettedContainerFactory.create(registry);
        }
        BlockState expected(BlockPos p) { return p.getY() < 0 || p.getY() >= 128 ? Blocks.AIR.defaultBlockState() : p.getY() == 63 ? gap : Blocks.NETHERRACK.defaultBlockState(); }
        LevelChunkSection[] prototype(ChunkPos pos) {
            return prototypes.computeIfAbsent(pos, cp -> {
                var r = new TerrainRequest(42, cp.x(), cp.z(), 0, 256, 64, 48, .008f, request.profile());
                var sections = new LevelChunkSection[16]; Arrays.setAll(sections, i -> new LevelChunkSection(containers));
                try (var chunk = NativeTerrain.instance().generate(r)) {
                    var actual = chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                    for (int y = 0; y < 256; y++) for (int c = 0; c < 256; c++) {
                        var state = expected(new BlockPos(cp.x() * 16 + c % 16, y, cp.z() * 16 + c / 16));
                        require(palette[Short.toUnsignedInt(actual[y * 256 + c])].equals(state), "independently checked ore substrate");
                        sections[y / 16].setBlockState(c % 16, y % 16, c / 16, state, false);
                    }
                }
                return sections;
            });
        }
    }

    private static final class World implements InvocationHandler {
        final Fixture fixture; final Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
        final WorldGenLevel level = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, this);
        World(Fixture fixture) { this.fixture = fixture; }
        ProtoChunk chunk(ChunkPos pos) {
            return chunks.computeIfAbsent(pos, cp -> new ProtoChunk(cp, UpgradeData.EMPTY,
                    Arrays.stream(fixture.prototype(cp)).map(LevelChunkSection::copy).toArray(LevelChunkSection[]::new),
                    new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), fixture.bounds, fixture.containers, null));
        }
        BlockState get(BlockPos pos) { return fixture.bounds.isOutsideBuildHeight(pos) ? Blocks.AIR.defaultBlockState() : chunk(ChunkPos.containing(pos)).getBlockState(pos); }
        Map<BlockPos, BlockState> changed(BlockPos origin) {
            var result = new HashMap<BlockPos, BlockState>(); var p = new BlockPos.MutableBlockPos();
            // Maximum registered ore reach is 13 blocks (size 64); scan both sides of that bound too.
            for (int x = origin.getX() - 14; x <= origin.getX() + 14; x++) for (int z = origin.getZ() - 14; z <= origin.getZ() + 14; z++) {
                var cp = new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)); var chunk = chunks.get(cp); if (chunk == null) continue;
                for (int y = Math.max(0, origin.getY() - 14); y <= Math.min(255, origin.getY() + 14); y++) {
                    p.set(x, y, z); var state = chunk.getBlockState(p);
                    if (!state.equals(fixture.expected(p))) result.put(p.immutable(), state);
                }
            }
            return result;
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            var args = arguments == null ? new Object[0] : arguments;
            return switch (method.getName()) {
                case "getBlockState" -> get((BlockPos)args[0]);
                case "getChunk" -> chunk(new ChunkPos((int)args[0], (int)args[1]));
                case "ensureCanWrite" -> !fixture.bounds.isOutsideBuildHeight((BlockPos)args[0]);
                case "setBlock" -> { var p = (BlockPos)args[0]; if (fixture.bounds.isOutsideBuildHeight(p)) yield false; chunk(ChunkPos.containing(p)).setBlockState(p, (BlockState)args[1], 2); yield true; }
                case "getHeight" -> {
                    if (args.length == 0) yield 256;
                    var type = (Heightmap.Types)args[0]; int y = 256;
                    while (y > 0 && !type.isOpaque().test(get(new BlockPos((int)args[1], y - 1, (int)args[2])))) y--;
                    yield y;
                }
                case "getMinY" -> 0;
                case "getSeed" -> 42L;
                case "toString" -> "NetherOreReferenceWorld";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> { if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args); throw new UnsupportedOperationException("Ore reference called " + method); }
            };
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
