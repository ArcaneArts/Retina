package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

final class NoiseExportVerification {
    private NoiseExportVerification() { }

    static void verify(Path world, RegistryAccess registries, Registry<LevelStem> dimensions) throws Exception {
        verify(world, registries, dimensions.getHolderOrThrow(LevelStem.OVERWORLD).value(),
                LevelStem.OVERWORLD.location().toString(), "region");
        verify(world, registries, dimensions.getHolderOrThrow(LevelStem.NETHER).value(),
                LevelStem.NETHER.location().toString(), "DIM-1/region");
    }

    private static void verify(Path world, RegistryAccess registries, LevelStem stem, String dimension, String directory)
            throws Exception {
        var definition = world.resolve("retina.dat");
        var region = world.resolve(directory).resolve("r.-1.0.mca");
        Files.createDirectories(region.getParent());
        long start = System.nanoTime();
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            nativeWorld.bindDimension(dimension, 2);
            var receipt = nativeWorld.generateRegion(dimension, -1, 0, region);
            require(receipt.generated() == 1024 && receipt.preserved() == 0, "new region records");
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        var expectedBiomes = stem.generator().getBiomeSource().possibleBiomes();
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        var biomeCodec = PalettedContainer.codecRO(registries.registryOrThrow(Registries.BIOME).asHolderIdMap(),
                Biome.CODEC, PalettedContainer.Strategy.SECTION_BIOMES, expectedBiomes.iterator().next());
        var blockCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        var blocks = new HashSet<BlockState>();
        var biomes = new TreeSet<String>();
        var type = stem.type().value();
        try (var file = new RegionFile(region, region.getParent(), true)) {
            for (var position : new ChunkPos[]{new ChunkPos(-31, 1), new ChunkPos(-16, 16), new ChunkPos(-1, 31)}) {
                CompoundTag chunk;
                try (var input = file.getChunkDataInputStream(position)) {
                    require(input != null, "chunk " + position);
                    chunk = NbtIo.read(input);
                }
                require(chunk.getInt("DataVersion") == SharedConstants.getCurrentVersion().getDataVersion().getVersion(), "DataVersion");
                require(chunk.getInt("xPos") == position.x && chunk.getInt("zPos") == position.z, "chunk position");
                require(ChunkStatus.byName(chunk.getString("Status")) != null, "registered chunk status");
                for (var kind : Heightmap.Types.values()) {
                    require(chunk.getCompound("Heightmaps").getLongArray(kind.getSerializationKey()).length > 0,
                            "heightmap " + kind);
                }
                int checked = 0;
                for (Tag tag : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) tag;
                    int y = section.getByte("Y") * 16;
                    if (y < type.minY() || y >= type.minY() + type.height()) continue;
                    decode(blockCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states"))).getAll(blocks::add);
                    decode(biomeCodec.parse(ops, section.getCompound("biomes"))).getAll(biome -> {
                        require(expectedBiomes.contains(biome), "biome from effective source");
                        biomes.add(biome.unwrapKey().orElseThrow().location().toString());
                    });
                    checked++;
                }
                require(checked == type.height() / 16, "storage section count");
            }
        }
        require(blocks.contains(Blocks.BEDROCK.defaultBlockState()), "surface rules emitted bedrock");
        require(blocks.stream().anyMatch(BlockState::isAir), "air");
        require(blocks.size() >= 3, "terrain block diversity");
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            nativeWorld.bindDimension(dimension, 2);
            var receipt = nativeWorld.generateRegion(dimension, -1, 0, region);
            require(receipt.generated() == 0 && receipt.preserved() == 1024, "region reopen");
        }

        var event = new JsonObject();
        event.addProperty("event", "noise_1201_region_roundtrip");
        event.addProperty("status", "pass");
        event.addProperty("dimension", dimension);
        event.addProperty("region", "-1,0");
        event.addProperty("generated", 1024);
        event.addProperty("preserved", 1024);
        event.addProperty("milliseconds", elapsed);
        event.addProperty("block_states", blocks.size());
        var blockIds = new TreeSet<String>();
        blocks.forEach(state -> blockIds.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
        var blockList = new JsonArray();
        blockIds.forEach(blockList::add);
        event.add("blocks", blockList);
        var biomeList = new JsonArray();
        biomes.forEach(biomeList::add);
        event.add("biomes", biomeList);
        System.out.println("QA_EVT " + event);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T decode(com.mojang.serialization.DataResult<T> result) {
        return result.getOrThrow(false, message -> { throw new AssertionError(message); });
    }
}
