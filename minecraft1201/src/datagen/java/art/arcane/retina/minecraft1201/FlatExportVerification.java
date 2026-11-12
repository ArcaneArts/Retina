package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.SimpleBitStorage;
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
import net.minecraft.world.level.levelgen.FlatLevelSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

final class FlatExportVerification {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private FlatExportVerification() { }

    static void verify(Path world, RegistryAccess registries, Registry<LevelStem> dimensions, long seed) throws Exception {
        var stem = dimensions.getHolderOrThrow(LevelStem.OVERWORLD).value();
        if (!(stem.generator() instanceof FlatLevelSource flat)) {
            throw new AssertionError("The selected fixture has no Flat Overworld");
        }
        Path definition = world.resolve("retina.dat");
        byte[] original = Files.readAllBytes(definition);
        Path region = world.resolve("region/r.-1.0.mca");
        Files.createDirectories(region.getParent());
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            var metadata = JsonParser.parseString(nativeWorld.metadata()).getAsJsonObject();
            require(metadata.get("data_version").getAsInt() == dataVersion, "definition DataVersion");
            require(metadata.get("minecraft_version").getAsString().equals("1.20.1"), "definition game version");
            try {
                nativeWorld.bindDimension(OVERWORLD, 4);
                throw new AssertionError("1.20.1 accepted unsupported LZ4");
            } catch (IllegalStateException error) {
                require(error.getMessage().contains("requires zlib"), "1.20.1 codec rejection");
            }
            try {
                nativeWorld.bindDimension("minecraft:the_nether", 2);
                throw new AssertionError("Noise generation lacks an adapter in this fixture");
            } catch (IllegalStateException error) {
                require(error.getMessage().contains("No 1.20.1 definition adapter"), "unsupported noise diagnostic");
            }
            nativeWorld.bindDimension(OVERWORLD, 2);
            require(nativeWorld.request(OVERWORLD, -31, 1).seed() == seed, "definition seed");
            var result = nativeWorld.generateRegion(OVERWORLD, -1, 0, region);
            require(result.generated() == 1024 && result.preserved() == 0, "generated region records");
        }
        var position = new ChunkPos(-31, 1);
        int minY = stem.type().value().minY();
        int height = stem.type().value().height();
        try (var file = new RegionFile(region, region.getParent(), true)) {
            CompoundTag chunk = read(file, position);
            require(chunk.getInt("DataVersion") == dataVersion, "chunk DataVersion");
            require(chunk.getInt("xPos") == position.x && chunk.getInt("zPos") == position.z, "chunk position");
            var status = ChunkStatus.byName(chunk.getString("Status"));
            require(status != null && status.isOrAfter(ChunkStatus.LIGHT), "1.20.1 prelit chunk status");
            require(chunk.getBoolean("isLightOn"), "prelit interior record");
            var source = flat.settings();
            var biomeRegistry = registries.registryOrThrow(Registries.BIOME);
            var biomeCodec = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), Biome.CODEC,
                    PalettedContainer.Strategy.SECTION_BIOMES, source.getBiome());
            var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            BlockState[] layers = new BlockState[height];
            Arrays.fill(layers, Blocks.AIR.defaultBlockState());
            int y = 0;
            for (var layer : source.getLayersInfo()) {
                for (int i = 0; i < layer.getHeight() && y < height; i++, y++) layers[y] = layer.getBlockState();
            }

            checkHeightmaps(chunk, layers);
            int checked = 0;
            boolean skyLight = false;
            for (Tag value : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) value;
                if (section.contains("SkyLight", Tag.TAG_BYTE_ARRAY)) {
                    require(section.getByteArray("SkyLight").length == 2048, "skylight nibble array");
                    skyLight = true;
                }
                int floor = section.getByte("Y") * 16 - minY;
                if (floor < 0 || floor >= height) continue;
                var blocks = BLOCK_STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                        .getOrThrow(false, message -> { throw new AssertionError(message); });
                var biomes = biomeCodec.parse(ops, section.getCompound("biomes"))
                        .getOrThrow(false, message -> { throw new AssertionError(message); });
                for (int dy = 0; dy < 16; dy++) {
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        require(blocks.get(x, dy, z) == layers[floor + dy], "block layer at " + (floor + dy));
                    }
                }
                for (int dy = 0; dy < 4; dy++) {
                    for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                        require(biomes.get(x, dy, z).unwrapKey().equals(source.getBiome().unwrapKey()), "biome palette");
                    }
                }
                if (floor == 0) {
                    blocks.set(0, 0, 0, Blocks.DIAMOND_BLOCK.defaultBlockState());
                    section.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, blocks)
                            .getOrThrow(false, message -> { throw new AssertionError(message); }));
                }
                checked++;
            }
            require(checked == height / 16, "all storage sections decoded");
            require(skyLight == stem.type().value().hasSkyLight(), "dimension skylight presence");
            try (var output = file.getChunkDataOutputStream(position)) {
                NbtIo.write(chunk, output);
            }
            file.flush();
        }
        try (var nativeWorld = NativeWorldDefinition.open(definition)) {
            nativeWorld.bindDimension(OVERWORLD, 2);
            var result = nativeWorld.generateRegion(OVERWORLD, -1, 0, region);
            require(result.generated() == 0 && result.preserved() == 1024, "existing records preserved");
        }
        try (var file = new RegionFile(region, region.getParent(), true)) {
            var chunk = read(file, position);
            var first = chunk.getList("sections", Tag.TAG_COMPOUND).stream().map(tag -> (CompoundTag) tag)
                    .filter(section -> section.getByte("Y") * 16 == minY).findFirst().orElseThrow();
            var blocks = BLOCK_STATES.parse(NbtOps.INSTANCE, first.getCompound("block_states"))
                    .getOrThrow(false, message -> { throw new AssertionError(message); });
            require(blocks.get(0, 0, 0).is(Blocks.DIAMOND_BLOCK), "edit preserved after native reopen");
        }
        require(Arrays.equals(original, Files.readAllBytes(definition)), "saved definition unchanged");
        System.out.println("QA_EVT {\"event\":\"flat_1201_region_roundtrip\",\"status\":\"pass\",\"generated\":1024,"
                + "\"preserved\":1024,\"definition_sha256\":\""
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)) + "\"}");
    }

    private static void checkHeightmaps(CompoundTag chunk, BlockState[] layers) {
        int bits = 32 - Integer.numberOfLeadingZeros(layers.length);
        var maps = chunk.getCompound("Heightmaps");
        for (var kind : Heightmap.Types.values()) {
            int expected = layers.length;
            while (expected > 0 && !kind.isOpaque().test(layers[expected - 1])) expected--;
            var packed = new SimpleBitStorage(bits, 256, maps.getLongArray(kind.getSerializationKey()));
            for (int i = 0; i < 256; i++) require(packed.get(i) == expected, "heightmap " + kind);
        }
    }

    private static CompoundTag read(RegionFile file, ChunkPos position) throws IOException {
        try (var input = file.getChunkDataInputStream(position)) {
            if (input == null) throw new AssertionError("Missing chunk " + position);
            return NbtIo.read(input);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
