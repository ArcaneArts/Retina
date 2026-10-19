package art.arcane.retina.worldgen;

import com.google.gson.JsonParser;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import java.lang.reflect.*;
import java.lang.foreign.ValueLayout;
import java.util.*;

/** Actual Minecraft feature algorithms run with the same controlled random stream. */
final class FlatFeatureTest {
    static void check(RegistryAccess registry) throws Exception {
        int cases = 0, placed = 0;
        for (String name : List.of("overworld", "the_void")) {
            var preset = registry.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                    .getValueOrThrow(ResourceKey.create(Registries.FLAT_LEVEL_GENERATOR_PRESET, Identifier.withDefaultNamespace(name)));
            var type = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);
            var source = new GenerationSource(new FlatLevelSource(preset.settings()), type);
            var data = FlatTerrainProfile.export(registry, source, 42, StructureProfile.Context.NONE, true);
            var profile = BiomeTerrainProfile.register(data);
            var request = new TerrainRequest(42, 0, 0, type.minY(), type.height(), 64, 0, .008F, profile.nativeId());
            var recipes = JsonParser.parseString(data.json()).getAsJsonObject().getAsJsonArray("decorations");
            var base = NativeTerrain.instance().column(request, 0);
            if (name.equals("overworld")) {
                int changed = 0;
                try (var cache = new TemporaryRegions(request, "minecraft:plains", profile, new GenerationMetrics(), 2, 1)) {
                    var codec = PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
                    for (var pos : List.of(new ChunkPos(0, 0), new ChunkPos(7, 8), new ChunkPos(16, 16), new ChunkPos(31, 31))) {
                        var tag = cache.read(pos);
                        try (var chunk = NativeTerrain.instance().generate(new TerrainRequest(42, pos.x(), pos.z(), type.minY(), type.height(), 64, 0, .008F, profile.nativeId()))) {
                            for (var value : tag.getListOrEmpty("sections")) {
                                var section = (net.minecraft.nbt.CompoundTag) value;
                                if (!section.contains("block_states")) continue;
                                int y0 = section.getByteOr("Y", (byte) 0) * 16 - type.minY();
                                var states = codec.parse(net.minecraft.nbt.NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
                                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                    int id = Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, ((y0 + y) * 256L + z * 16 + x) * 2));
                                    require(states.get(x, y, z).equals(profile.materials()[id]), "decorated flat chunk/MCA parity");
                                    if (id != Short.toUnsignedInt(base[y0 + y])) changed++;
                                }
                            }
                        }
                    }
                }
                require(changed > 0, "enabled flat features actually change generated terrain");
            }
            for (int id = 0; id < recipes.size(); id++) {
                var recipe = recipes.get(id).getAsJsonObject();
                if (!List.of("flat_lake", "void_platform").contains(recipe.get("kind").getAsString())) continue;
                String featureName = recipe.get("source").getAsString();
                Feature feature = registry.lookupOrThrow(Registries.PLACED_FEATURE)
                        .getValueOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(featureName))).feature().value();
                for (long seed = 0; seed < 32; seed++) {
                    var world = new World(registry, profile.materials(), base, request);
                    int[] at = {8, name.equals("the_void") ? type.minY() : type.minY() + preset.settings().getLayers().size(), 8};
                    feature.place(world.level, source.generator(), new NativeBlockFeatureIntegrationTest.Stream(seed, false), new BlockPos(at[0], at[1], at[2]));
                    var blocks = NativeTerrain.instance().decorationFeature(request, id, at, seed);
                    var actual = new HashMap<BlockPos, BlockState>();
                    for (int i = 0; i < blocks.length; i += 4) actual.put(new BlockPos(blocks[i], blocks[i + 1], blocks[i + 2]), profile.materials()[blocks[i + 3]]);
                    if (!world.changed.equals(actual)) {
                        var all = new HashSet<>(world.changed.keySet()); all.addAll(actual.keySet());
                        throw new AssertionError(featureName + " seed=" + seed + " expected=" + world.changed.size() + " actual=" + actual.size() + " differences="
                                + all.stream().filter(p -> !Objects.equals(world.changed.get(p), actual.get(p))).limit(5).map(p -> p + ":" + world.changed.get(p) + " -> " + actual.get(p)).toList());
                    }
                    cases++; if (!actual.isEmpty()) placed++;
                }
            }
            if (name.equals("the_void")) {
                for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) {
                    try (var chunk = NativeTerrain.instance().generate(new TerrainRequest(42, cx, cz, type.minY(), type.height(), 64, 0, .008F, profile.nativeId()))) {
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            int wx = cx * 16 + x, wz = cz * 16 + z;
                            var expected = Math.max(Math.abs(wx - 8), Math.abs(wz - 8)) > 16 ? Blocks.AIR : wx == 8 && wz == 8 ? Blocks.COBBLESTONE : Blocks.STONE;
                            var block = profile.materials()[Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (3 * 256L + z * 16 + x) * 2))];
                            require(block.is(expected), "void platform chunk placement/extent");
                        }
                    }
                }
            }
        }
        require(cases >= 64 && placed >= 32, "flat optional features were exercised");
        System.out.println("QA_EVT {\"event\":\"flat_features_minecraft_reference\",\"status\":\"pass\",\"context\":{\"cases\":" + cases + ",\"placed\":" + placed + "}}");
    }

    private static final class World implements InvocationHandler {
        final Map<BlockPos, BlockState> changed = new HashMap<>();
        final Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();
        final BlockState[] palette;
        final short[] column;
        final TerrainRequest request;
        final PalettedContainerFactory factory;
        final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, this);
        World(RegistryAccess registry, BlockState[] palette, short[] column, TerrainRequest request) {
            this.palette = palette; this.column = column; this.request = request; this.factory = PalettedContainerFactory.create(registry);
        }
        BlockState get(BlockPos p) {
            int y = p.getY() - request.minY();
            return changed.getOrDefault(p, y < 0 || y >= column.length ? Blocks.AIR.defaultBlockState() : palette[Short.toUnsignedInt(column[y])]);
        }
        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            var args = arguments == null ? new Object[0] : arguments;
            return switch (method.getName()) {
                case "getBlockState" -> get((BlockPos) args[0]);
                case "getFluidState" -> get((BlockPos) args[0]).getFluidState();
                case "setBlock" -> { changed.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]); yield true; }
                case "getMinY" -> request.minY();
                case "getHeight" -> request.height();
                case "getChunk" -> { var p = args[0] instanceof BlockPos pos ? ChunkPos.containing(pos) : new ChunkPos((int) args[0], (int) args[1]); yield chunks.computeIfAbsent(p, pos -> new ProtoChunk(pos, UpgradeData.EMPTY, level, factory, null)); }
                case "scheduleTick" -> null;
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException("Flat Minecraft reference called " + method);
                }
            };
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
