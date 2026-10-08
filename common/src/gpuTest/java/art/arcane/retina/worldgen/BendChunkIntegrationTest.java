package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Minecraft decodes chunks encoded, compressed and assembled exclusively in Bend. */
public final class BendChunkIntegrationTest {
    private static BlockState[] states() {
        return new BlockState[]{
            Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.WATER.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.DISTANCE, 1)
                    .setValue(BlockStateProperties.PERSISTENT, false),
            Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X),
            Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST)
        };
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var resources = new net.minecraft.server.packs.resources.FallbackResourceManager(
                net.minecraft.server.packs.PackType.SERVER_DATA, "minecraft");
        resources.push(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources());
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,
                net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY))
                .forEach(net.minecraft.core.Registry.PendingTags::apply);
        var registry = RegistryIntegrationFixtures.load(resources);
        var lookup = registry.lookupOrThrow(Registries.BIOME);
        var biomeMap = new IdMapper<Holder<Biome>>();
        lookup.listElements().forEach(biomeMap::add);
        Codec<Holder<Biome>> biomeCodec = Codec.STRING.comapFlatMap(id -> lookup.get(
                ResourceKey.create(Registries.BIOME, Identifier.parse(id)))
                .<DataResult<Holder<Biome>>>map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "Unknown biome: " + id)),
                holder -> holder.unwrapKey().orElseThrow().identifier().toString());
        var plains = lookup.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("plains")));
        var blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        var biomeStrategy = Strategy.createForBiomes(biomeMap);
        var blockCodec = PalettedContainer.codecRW(BlockState.CODEC, blockStrategy, Blocks.AIR.defaultBlockState());
        var factory = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(), blockCodec,
                biomeStrategy, plains, PalettedContainer.codecRO(biomeCodec, biomeStrategy, plains));
        var folder = Files.createTempDirectory("retina-bend-minecraft-");
        try {
            var prefix = folder.resolve("fixture");
            int version = SharedConstants.getCurrentVersion().dataVersion().version();
            var executable = Path.of(args.length == 0 ? "build/bend/chunk-fixtures" : args[0]).toAbsolutePath();
            var log = folder.resolve("bend.log");
            var process = new ProcessBuilder(executable.toString(), prefix.toString(), Integer.toString(version),
                    "--threads", "2").redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new AssertionError("Bend fixture timed out: " + Files.readString(log));
            }
            require(process.exitValue() == 0, "Bend fixture: " + Files.readString(log));
            Files.move(Path.of(prefix + ".mca"), folder.resolve("r.-2.1.mca"));
            var info = new RegionStorageInfo("retina-bend", Level.OVERWORLD, "chunk");
            for (int reopen = 0; reopen < 2; reopen++) {
                try (var storage = new RegionFileStorage(info, folder, false)) {
                    for (int x : new int[]{-33, -34}) {
                        var tag = storage.read(new ChunkPos(x, 63));
                        require(tag != null && tag.getIntOr("DataVersion", -1) == version, "actual current data version");
                        check(tag, x == -34, factory, blockCodec);
                    }
                }
            }
            System.out.println("QA_EVT {\"event\":\"bend_minecraft_chunk_serialization\",\"status\":\"pass\","
                    + "\"context\":{\"chunks\":2,\"reopens\":2,\"blocks_per_read\":196608,\"data_version\":" + version + "}}");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void check(CompoundTag tag, boolean lit, PalettedContainerFactory factory,
            Codec<PalettedContainer<BlockState>> codec) {
        var parsed = SerializableChunkData.parse(LevelHeightAccessor.create(-64, 384), factory, tag);
        require(parsed != null && parsed.lightCorrect() == lit, "Minecraft accepts light completion flag");
        require(parsed.chunkStatus() == (lit ? ChunkStatus.LIGHT : ChunkStatus.FEATURES), "Minecraft chunk status");
        require(parsed.sectionData().size() == (lit ? 26 : 24), "light-only padding sections");
        require(tag.getIntOr("xPos", 0) == (lit ? -34 : -33) && tag.getIntOr("zPos", 0) == 63
                && tag.getIntOr("yPos", 0) == -4, "negative chunk/section coordinates");
        var expectedStates = states();
        var heights = new int[Heightmap.Types.values().length][256];
        var sections = tag.getListOrEmpty("sections");
        for (int index = 0; index < sections.size(); index++) {
            var section = sections.getCompound(index).orElseThrow();
            int y = section.getByteOr("Y", (byte) 0);
            require(y == index - (lit ? 5 : 4), "ordered section Y");
            if (lit) {
                var blockLight = section.getByteArray("BlockLight").orElseThrow();
                var skyLight = section.getByteArray("SkyLight").orElseThrow();
                require(blockLight.length == 2048 && skyLight.length == 2048, "nibble array sizes");
                for (int i = 0; i < 2048; i++) require(blockLight[i] == 0 && skyLight[i] == -1, "supplied light bytes preserved");
            }
            if (y == -5 || y == 20) continue;
            int s = y + 4;
            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
            var biomeTag = section.getCompoundOrEmpty("biomes");
            var biomes = biomeTag.getListOrEmpty("palette");
            var indices = biomes.size() == 1 ? null : new SimpleBitStorage(
                    32 - Integer.numberOfLeadingZeros(biomes.size() - 1), 64, biomeTag.getLongArray("data").orElseThrow());
            for (int i = 0; i < 64; i++) {
                var actual = biomes.getString(indices == null ? 0 : indices.get(i)).orElseThrow();
                var expected = List.of("minecraft:plains", "minecraft:forest", "minecraft:lush_caves")
                        .get(lit || s % 3 == 0 ? 0 : (i + s) % 3);
                require(actual.equals(expected), "quart biome order and palette");
            }
            for (int i = 0; i < 4096; i++) {
                int layer = s * 16 + i / 256, column = i % 256;
                var actual = blocks.get(i % 16, i / 256, (i % 256) / 16);
                require(actual.equals(expectedStates[lit ? 0 : block(layer, column)]), "block properties and local index at " + s + "/" + i);
                for (var type : Heightmap.Types.values()) if (type.isOpaque().test(actual)) heights[type.ordinal()][column] = layer + 1;
            }
        }
        for (var type : Heightmap.Types.values()) {
            var packed = new SimpleBitStorage(9, 256, tag.getCompoundOrEmpty("Heightmaps")
                    .getLongArray(type.getSerializationKey()).orElseThrow());
            for (int c = 0; c < 256; c++) require(packed.get(c) == heights[type.ordinal()][c], "actual registered " + type + " predicate");
        }
        if (!lit) {
            var chest = tag.getListOrEmpty("block_entities").getCompound(0).orElseThrow();
            require(chest.getLongOr("LootTableSeed", 0) == Long.MIN_VALUE + 1, "full 64-bit loot seed");
            require(chest.getIntOr("x", 0) == -527 && chest.getIntOr("y", 0) == -34
                    && chest.getIntOr("z", 0) == 1021, "block entity coordinates");
            require(chest.getStringOr("LootTable", "").equals("minecraft:chests/village/village_plains_house"), "loot metadata");
        }
    }

    private static int block(int layer, int column) {
        int h = 20 + column % 11;
        if (layer == 383 && column == 255) return 6;
        if (layer == 30 && column == 209) return 7;
        if (layer == h + 5 && column % 7 == 0) return 5;
        if (layer == h + 3 && column % 13 == 0) return 6;
        if (layer < h - 3) return 1;
        if (layer < h) return 2;
        if (layer == h) return 3;
        return layer < 27 ? 4 : 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
