package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

final class NoiseExportVerification {
    private NoiseExportVerification() { }

    static void verify(Path world, RegistryAccess registries, Registry<LevelStem> dimensions, long seed) throws Exception {
        verify(world, registries, dimensions, LevelStem.OVERWORLD, "region", -1, 0, seed);
        verify(world, registries, dimensions, LevelStem.NETHER, "DIM-1/region", -1, 0, seed);
        verify(world, registries, dimensions, LevelStem.END, "DIM1/region", -1, 0, seed);
        verify(world, registries, dimensions, LevelStem.END, "DIM1/region", -3, -1, seed);
    }

    private static void verify(Path world, RegistryAccess registries, Registry<LevelStem> dimensions,
                               ResourceKey<LevelStem> key, String directory, int rx, int rz,
                               long seed) throws Exception {
        var stem = dimensions.getHolderOrThrow(key).value();
        String dimension = key.location().toString();
        boolean end = key.equals(LevelStem.END);
        var type = stem.type().value();
        var heights = LevelHeightAccessor.create(type.minY(), type.height());
        RandomState reference = null;
        if (end) {
            var settings = ((NoiseBasedChunkGenerator) stem.generator()).generatorSettings().value();
            reference = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
        }
        var definition = world.resolve("retina.dat");
        var region = world.resolve(directory).resolve("r." + rx + "." + rz + ".mca");
        Files.createDirectories(region.getParent());
        int oreRecipes;
        int carverEntries;
        long start;
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            var profile = JsonParser.parseString(nativeWorld.diagnosticProfile(dimension)).getAsJsonObject();
            oreRecipes = profile.has("ores") ? profile.getAsJsonArray("ores").size() : 0;
            carverEntries = profile.getAsJsonArray("biomes").asList().stream()
                    .map(value -> value.getAsJsonObject().getAsJsonArray("carvers"))
                    .filter(java.util.Objects::nonNull).mapToInt(JsonArray::size).sum();
            start = System.nanoTime();
            nativeWorld.bindDimension(dimension, 2);
            var receipt = nativeWorld.generateRegion(dimension, rx, rz, region);
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
        try (var file = new RegionFile(region, region.getParent(), true)) {
            int startX = rx * 32, startZ = rz * 32;
            for (var position : new ChunkPos[]{new ChunkPos(startX + 1, startZ + 1),
                    new ChunkPos(startX + 6, startZ + 23), new ChunkPos(startX + 31, startZ + 1)}) {
                int x = position.getMinBlockX() + 3;
                int z = position.getMinBlockZ() + 5;
                var expectedColumn = end ? stem.generator().getBaseColumn(x, z, heights, reference) : null;
                var expectedBiome = end ? stem.generator().getBiomeSource().getNoiseBiome(
                        QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z), reference.sampler()) : null;
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
                    var decodedBlocks = decode(blockCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                    var decodedBiomes = decode(biomeCodec.parse(ops, section.getCompound("biomes")));
                    decodedBlocks.getAll(blocks::add);
                    decodedBiomes.getAll(biome -> {
                        require(expectedBiomes.contains(biome), "biome from effective source");
                        biomes.add(biome.unwrapKey().orElseThrow().location().toString());
                    });
                    if (end) {
                        for (int dy = 0; dy < 16; dy++) {
                            require(decodedBlocks.get(3, dy, 5) == expectedColumn.getBlock(y + dy),
                                    "vanilla End column " + x + "," + (y + dy) + "," + z);
                        }
                        require(decodedBiomes.get(0, 0, 1).equals(expectedBiome), "vanilla End biome " + position);
                    }
                    checked++;
                }
                require(checked == type.height() / 16, "storage section count");
            }
        }
        require(blocks.stream().anyMatch(BlockState::isAir), "air");
        if (!end) {
            require(blocks.contains(Blocks.BEDROCK.defaultBlockState()), "surface rules emitted bedrock");
            require(blocks.size() >= 3, "terrain block diversity");
        }
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            nativeWorld.bindDimension(dimension, 2);
            var receipt = nativeWorld.generateRegion(dimension, rx, rz, region);
            require(receipt.generated() == 0 && receipt.preserved() == 1024, "region reopen");
        }

        var event = new JsonObject();
        event.addProperty("event", "noise_1201_region_roundtrip");
        event.addProperty("status", "pass");
        event.addProperty("dimension", dimension);
        event.addProperty("region", rx + "," + rz);
        event.addProperty("ore_recipes", oreRecipes);
        event.addProperty("carver_entries", carverEntries);
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
