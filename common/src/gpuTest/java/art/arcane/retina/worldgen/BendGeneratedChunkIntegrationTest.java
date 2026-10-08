package art.arcane.retina.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.zip.InflaterInputStream;

/** Actual Minecraft readers validate generated Bend base/material chunks.
 * No Rust or Java generation/encoding is used. This is component decoding, not
 * a running-world save/reopen or completed final-generation acceptance test.
 */
public final class BendGeneratedChunkIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var folder = Path.of(args.length == 0 ? "build/bend/generated-chunks" : args[0]);
        var names = Set.copyOf(Arrays.asList((args.length < 2
                ? "ramp,islands,solid,empty,non_power_cells,vanilla" : args[1]).split(",")));
        var stack = new ArrayList<PackResources>();
        stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        for (int i = 2; i < args.length; i++) stack.add((PackResources) new FilePackResources.FileResourcesSupplier(Path.of(args[i]))
                .openMetadata(new PackLocationInfo("bend-chunk-test-" + i, Component.literal("Bend chunk test"),
                        PackSource.DEFAULT, Optional.empty())));
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, stack)) {
            var builtin = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            var lookup = registry.lookupOrThrow(Registries.BIOME);
            var map = new IdMapper<Holder<Biome>>();
            lookup.listElements().forEach(map::add);
            Codec<Holder<Biome>> biomeCodec = Codec.STRING.comapFlatMap(id -> lookup.get(
                    ResourceKey.create(Registries.BIOME, Identifier.parse(id)))
                    .<DataResult<Holder<Biome>>>map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown biome: " + id)),
                    holder -> holder.unwrapKey().orElseThrow().identifier().toString());
            var plains = lookup.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("plains")));
            var blocks = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            var biomes = Strategy.createForBiomes(map);
            var blockCodec = PalettedContainer.codecRW(BlockState.CODEC, blocks, Blocks.AIR.defaultBlockState());
            var factory = new PalettedContainerFactory(blocks, Blocks.AIR.defaultBlockState(), blockCodec,
                    biomes, plains, PalettedContainer.codecRO(biomeCodec, biomes, plains));
            int chunks = 0; long verified = 0;
            try (var paths = Files.walk(folder)) {
                for (var path : paths.filter(p -> p.toString().endsWith(".nbt") && names.contains(p.getParent().getFileName().toString())).sorted().toList()) {
                    // Open the actual generated NBT and independent zlib stream
                    // twice. These are decoding/reopen checks, not world edits.
                    for (int reopen = 0; reopen < 2; reopen++) {
                        CompoundTag raw;
                        try (var input = new DataInputStream(Files.newInputStream(path))) {
                            raw = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                            require(input.read() == -1, "NBT trailing bytes");
                        }
                        var compressed = path.resolveSibling(path.getFileName().toString().replace(".nbt", ".z"));
                        try (var input = new DataInputStream(new InflaterInputStream(Files.newInputStream(compressed)))) {
                            var decoded = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                            require(input.read() == -1 && raw.equals(decoded), "pure Bend zlib roundtrip through Java decoder");
                        }
                        verified += check(raw, factory, blockCodec, biomeCodec);
                    }
                    chunks++;
                }
            }
            require(chunks > 0, "generated chunks found");
            System.out.println("QA_EVT {\"event\":\"bend_generated_chunk_decoding\",\"status\":\"pass\",\"context\":{\"chunks\":"
                    + chunks + ",\"decodes_per_chunk\":2,\"blocks_verified\":" + verified + "}}");
        }
    }

    private static long check(CompoundTag tag, PalettedContainerFactory factory, Codec<PalettedContainer<BlockState>> codec, Codec<Holder<Biome>> biomeCodec) {
        require(tag.getIntOr("DataVersion", -1) == SharedConstants.getCurrentVersion().dataVersion().version(), "running game DataVersion");
        var sections = tag.getListOrEmpty("sections");
        int minSection = tag.getIntOr("yPos", Integer.MAX_VALUE);
        int height = sections.size() * 16;
        var parsed = SerializableChunkData.parse(LevelHeightAccessor.create(minSection * 16, height), factory, tag);
        require(parsed != null && !parsed.lightCorrect() && parsed.chunkStatus() == ChunkStatus.FEATURES, "unlit component status");
        require(parsed.sectionData().size() == sections.size(), "section count");
        var expected = new int[Heightmap.Types.values().length][256];
        for (int s = 0; s < sections.size(); s++) {
            var section = sections.getCompound(s).orElseThrow();
            require(section.getByteOr("Y", (byte) 127) == minSection + s, "ordered signed section Y");
            require(!section.contains("SkyLight") && !section.contains("BlockLight"), "no claimed prelighting");
            var blocks = codec.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).getOrThrow();
            // Parsing against the loaded biome registry catches unknown IDs.
            for (var value : section.getCompoundOrEmpty("biomes").getListOrEmpty("palette"))
                biomeCodec.parse(NbtOps.INSTANCE, value).getOrThrow();
            for (int i = 0; i < 4096; i++) {
                var actual = blocks.get(i % 16, i / 256, (i % 256) / 16);
                for (var type : Heightmap.Types.values()) if (type.isOpaque().test(actual))
                    expected[type.ordinal()][i % 256] = s * 16 + i / 256 + 1;
            }
        }
        int bits = 32 - Integer.numberOfLeadingZeros(height);
        for (var type : Heightmap.Types.values()) {
            var packed = new SimpleBitStorage(bits, 256, tag.getCompoundOrEmpty("Heightmaps")
                    .getLongArray(type.getSerializationKey()).orElseThrow());
            for (int c = 0; c < 256; c++) require(packed.get(c) == expected[type.ordinal()][c], "registered heightmap " + type + " at " + c);
        }
        return (long) height * 256;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
