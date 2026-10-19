package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.flat.*;
import java.nio.file.*;
import java.lang.foreign.ValueLayout;
import java.util.*;

/** Shared preview/MCA transport and real Minecraft references for native flat profiles. */
final class FlatIntegrationTest {
    static void check(RegistryAccess registry, ResourceManager resources) throws Exception {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var originalType = registry.lookupOrThrow(Registries.DIMENSION_TYPE).getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var encodedType = DimensionType.DIRECT_CODEC.encodeStart(ops, originalType).getOrThrow().getAsJsonObject();
        encodedType.addProperty("min_y", 0); encodedType.addProperty("height", 64); encodedType.addProperty("logical_height", 64);
        var type = DimensionType.DIRECT_CODEC.parse(ops, encodedType).getOrThrow();
        var encoded = JsonParser.parseString("""
                {"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:air"},
                {"height":1,"block":"minecraft:glowstone"},{"height":2,"block":"minecraft:glass"},
                {"height":4,"block":"minecraft:water"},{"height":1,"block":"minecraft:short_grass"}],"biome":"minecraft:plains","structure_overrides":[]}
                """).getAsJsonObject();
        var source = new GenerationSource(new FlatLevelSource(FlatLevelGeneratorSettings.CODEC.parse(ops, encoded).getOrThrow()), type);
        var generator = RetinaChunkGenerator.fromSource(source);
        generator.bindWorld(registry, 42);
        var profile = generator.profile();
        var factory = PalettedContainerFactory.create(registry);
        var bounds = LevelHeightAccessor.create(0, 64);
        var random = RandomState.create(registry.lookupOrThrow(Registries.NOISE), 42,
                registry.lookupOrThrow(Registries.NOISE_SETTINGS).getValueOrThrow(NoiseGeneratorSettings.OVERWORLD));
        var position = new ChunkPos(-32, 0);
        var request = generator.request(42, position.x(), position.z());
        var save = Files.createTempDirectory("retina-flat-integration-");
        try (var cache = new TemporaryRegions(request, "minecraft:plains", profile, new GenerationMetrics(), 2, 1)) {
            var tag = cache.read(position);
            var proto = new ProtoChunk(position, UpgradeData.EMPTY, bounds, factory, null);
            generator.createBiomes(random, net.minecraft.world.level.levelgen.blending.Blender.empty(), null, proto).join();
            generator.buildTerrain(proto, net.minecraft.world.level.levelgen.blending.Blender.empty(), random,
                    null, null, null, Set.copyOf(profile.biomes())).join();
            try (var chunk = NativeTerrain.instance().generate(request)) {
                for (int y = 0; y < 64; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var state = profile.materials()[Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT, (y * 256L + z * 16 + x) * 2))];
                    require(proto.getBlockState(new BlockPos(position.getMinBlockX() + x, y, z)).equals(state), "flat preview/native parity");
                }
            }
            for (var section : tag.getListOrEmpty("sections")) {
                var s = (CompoundTag) section;
                if (!s.contains("block_states")) continue;
                var blocks = PalettedContainer.codecRW(BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState())
                        .parse(NbtOps.INSTANCE, s.getCompoundOrEmpty("block_states")).getOrThrow();
                int y0 = s.getByteOr("Y", (byte) 0) * 16;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    require(blocks.get(x, y, z).equals(proto.getBlockState(new BlockPos(position.getMinBlockX() + x, y0 + y, z))), "flat MCA/preview parity");
            }
            for (var map : Heightmap.Types.values()) {
                var bits = new net.minecraft.util.SimpleBitStorage(7, 256, tag.getCompoundOrEmpty("Heightmaps").getLongArray(map.getSerializationKey()).orElseThrow());
                for (int c = 0; c < 256; c++) {
                    int h = 0;
                    for (int y = 63; y >= 0; y--) if (map.isOpaque().test(proto.getBlockState(new BlockPos(position.getMinBlockX() + c % 16, y, c / 16)))) { h = y + 1; break; }
                    require(bits.get(c) == h, "final flat heightmap includes deferred layers");
                }
            }
            var base = cache.baseColumn(position, cache.columns(position), 0);
            var queried = generator.getBaseColumn(position.getMinBlockX(), 0, bounds, random);
            for (int y = 0; y < 64; y++) require(base[y].equals(queried.getBlock(y)), "flat cached/base query parity");
            require(generator.getBaseHeight(position.getMinBlockX(), 0, Heightmap.Types.WORLD_SURFACE, bounds, random) == 10, "base height retains water and excludes deferred grass");
            var info = new RegionStorageInfo("flat-test", Level.OVERWORLD, "chunk");
            var destination = save.resolve("r.-1.0.mca");
            require(cache.publishIfPresent(position, destination).generated() == 1024, "flat whole preview promotion");
            var missing = new ChunkPos(-31, 0);
            try (var storage = new RegionFileStorage(info, save, false)) {
                var edit = storage.read(position); edit.putString("player_edit", "retained"); storage.write(position, edit); storage.write(missing, null);
            }
            var report = cache.publishIfPresent(missing, destination);
            require(report.generated() == 1 && report.preserved() == 1023 && report.gpuNanos() == 0, "flat partial promotion without regeneration");
            try (var storage = new RegionFileStorage(info, save, false)) {
                require(storage.read(position).getStringOr("player_edit", "").equals("retained"), "flat saved edit protected");
                require(storage.read(missing).equals(cache.read(missing)), "flat promoted record exactly matches preview");
            }
            var bad = save.resolve("bad.mca"); Files.write(bad, new byte[]{1, 2, 3});
            try { NativeTerrain.instance().generateRegion(request, bad, net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(), "minecraft:plains"); throw new AssertionError("failure was hidden"); }
            catch (IllegalStateException expected) { require(expected.getMessage().contains("truncated MCA"), "native errors propagate"); }
            NativeLightingIntegrationTest.flat(factory, profile);
        } finally {
            generator.closePreviews();
            try (var files = Files.walk(save)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
        // Flat overrides distinguish default sets, no sets, one set, and the global off switch.
        for (int option = 0; option < 4; option++) {
            var options = encoded.deepCopy(); options.remove("structure_overrides");
            if (option == 1) options.add("structure_overrides", new JsonArray());
            if (option == 2) options.add("structure_overrides", JsonParser.parseString("[\"minecraft:villages\"]"));
            var selected = new GenerationSource(new FlatLevelSource(FlatLevelGeneratorSettings.CODEC.parse(ops, options).getOrThrow()), type);
            var data = FlatTerrainProfile.export(registry, selected, 42, option == 3 ? StructureProfile.Context.NONE : StructureProfile.Context.of(resources), true);
            var sets = JsonParser.parseString(data.json()).getAsJsonObject().getAsJsonObject("structures").getAsJsonArray("sets");
            require((option == 1 || option == 3) ? sets.isEmpty() : !sets.isEmpty(), "flat effective structure selection " + option);
            if (option == 2) require(sets.size() == 1, "flat one-set override");
        }
        // Dimension clipping must use the selected bounds even when layers extend above them.
        encoded.add("layers", JsonParser.parseString("[{\"height\":80,\"block\":\"minecraft:stone\"}]"));
        var clipped = FlatTerrainProfile.export(registry, new GenerationSource(new FlatLevelSource(FlatLevelGeneratorSettings.CODEC.parse(ops, encoded).getOrThrow()), type), 42, StructureProfile.Context.NONE, true);
        require(JsonParser.parseString(clipped.json()).getAsJsonObject().getAsJsonObject("flat").getAsJsonArray("base_layers").size() == 64, "dimension clips layers");
        var nativeClip = BiomeTerrainProfile.register(clipped);
        try (var chunk = NativeTerrain.instance().generate(new TerrainRequest(42, 0, 0, 0, 64, 64, 0, .008F, nativeClip.nativeId()))) {
            for (short id : chunk.blocks().toArray(ValueLayout.JAVA_SHORT)) require(nativeClip.materials()[Short.toUnsignedInt(id)].is(Blocks.STONE), "clipped native layers");
        }
        System.out.println("QA_EVT {\"event\":\"flat_preview_mca_bounds_structures\",\"status\":\"pass\"}");
        var singleStem = registry.lookupOrThrow(Registries.WORLD_PRESET).getValueOrThrow(
                ResourceKey.create(Registries.WORLD_PRESET, Identifier.withDefaultNamespace("single_biome_surface")))
                .createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
        var single = (RetinaChunkGenerator) RetinaGenerators.adapt(Level.OVERWORLD, singleStem, singleStem.generator());
        try {
            single.bindWorld(registry, 42);
            require(single.profile().biomes().size() == 1, "single-biome native profile stays single");
            for (short biome : NativeTerrain.instance().sampleBiomes(single.request(42, -31, 42)))
                require(biome == 0, "single-biome native quart samples contain no extra cave biomes");
        } finally { single.closePreviews(); }
        encodedType.addProperty("height", 32); encodedType.addProperty("logical_height", 32);
        var shallow = DimensionType.DIRECT_CODEC.parse(ops, encodedType).getOrThrow();
        var bounded = RetinaChunkGenerator.fromSource(new GenerationSource(singleStem.generator(), shallow));
        try {
            bounded.bindWorld(registry, 42);
            require(bounded.getMinY() == 0 && bounded.getGenDepth() == 32 && bounded.getSeaLevel() == 63,
                    "custom noise dimension retains bounds and source sea level outside them");
            require(NativeTerrain.instance().column(bounded.request(42, 0, 0), 0).length == 32,
                    "actual bounded noise generation succeeds");
        } finally { bounded.closePreviews(); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
