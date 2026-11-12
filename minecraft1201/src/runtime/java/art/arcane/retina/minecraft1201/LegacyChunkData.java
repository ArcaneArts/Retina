package art.arcane.retina.minecraft1201;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.EnumMap;

/** Decodes the native storage layout with the same 1.20.1 codecs as the game. */
final class LegacyChunkData {
    private final CompoundTag root;
    private final CompoundTag[] sections;
    private final Decoder decoder;
    private final EnumMap<Heightmap.Types, SimpleBitStorage> heights = new EnumMap<>(Heightmap.Types.class);
    private final int minY;

    private LegacyChunkData(CompoundTag root, CompoundTag[] sections, Decoder decoder, int minY, int height) {
        this.root = root;
        this.sections = sections;
        this.decoder = decoder;
        this.minY = minY;
        var heightmaps = root.getCompound("Heightmaps");
        int bits = Mth.ceillog2(height + 1);
        for (var type : Heightmap.Types.values()) {
            heights.put(type, new SimpleBitStorage(bits, 256, heightmaps.getLongArray(type.getSerializationKey())));
        }
    }

    int height(Heightmap.Types type, int x, int z) {
        return minY + heights.get(type).get((z & 15) * 16 + (x & 15));
    }

    BlockState[] column(int x, int z, LevelHeightAccessor bounds) {
        var column = new BlockState[bounds.getHeight()];
        Arrays.fill(column, Blocks.AIR.defaultBlockState());
        for (int i = 0; i < sections.length; i++) {
            int bottom = minY + i * 16;
            int start = Math.max(bottom, bounds.getMinBuildHeight());
            int end = Math.min(bottom + 16, bounds.getMaxBuildHeight());
            if (start >= end) continue;
            var states = decoder.states(sections[i]);
            for (int y = start; y < end; y++)
                column[y - bounds.getMinBuildHeight()] = states.get(x & 15, y & 15, z & 15);
        }
        return column;
    }

    void install(ChunkAccess chunk) {
        var target = chunk.getSections();
        if (chunk.getMinBuildHeight() != minY || target.length != sections.length)
            throw new IllegalStateException("Retina chunk height differs from " + chunk.getPos());
        // Decode a new palette for the live chunk. The 1.20.1 copy API can
        // retain the original palette's resize callback.
        for (int i = 0; i < sections.length; i++) {
            target[i] = decoder.section(sections[i]);
        }
        heights.forEach((type, values) -> chunk.setHeightmap(type, values.getRaw().clone()));
        LegacyGenerationTicks.install(chunk, root);
        for (var raw : root.getList("block_entities", Tag.TAG_COMPOUND))
            chunk.setBlockEntityNbt(((CompoundTag) raw).copy());
        if (chunk instanceof ProtoChunk proto)
            for (var raw : root.getList("entities", Tag.TAG_COMPOUND))
                proto.addEntity(((CompoundTag) raw).copy());
    }

    void installStarts(ChunkAccess chunk, StructurePieceSerializationContext context, long seed) {
        var starts = root.getCompound("structures").getCompound("starts");
        for (String id : starts.getAllKeys()) {
            var start = StructureStart.loadStaticStart(context, starts.getCompound(id), seed);
            if (start == null || !start.isValid()) {
                throw new IllegalStateException("Cannot bind saved Retina structure " + id + " in " + chunk.getPos());
            }
            chunk.setStartForStructure(start.getStructure(), start);
        }
    }

    void installReferences(ChunkAccess chunk, RegistryAccess registry) {
        var references = root.getCompound("structures").getCompound("References");
        var structures = registry.registryOrThrow(Registries.STRUCTURE);
        for (String id : references.getAllKeys()) {
            var structure = structures.getOptional(new ResourceLocation(id))
                    .orElseThrow(() -> new IllegalStateException("Cannot bind saved Retina structure reference " + id));
            for (long start : references.getLongArray(id)) chunk.addReferenceForStructure(structure, start);
        }
    }

    static final class Decoder {
        private static final Codec<PalettedContainer<BlockState>> BLOCKS = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
        private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
        private final RegistryOps<Tag> ops;

        Decoder(RegistryAccess registry, Holder<Biome> fallback) {
            ops = RegistryOps.create(NbtOps.INSTANCE, registry);
            biomes = PalettedContainer.codecRO(registry.registryOrThrow(Registries.BIOME).asHolderIdMap(),
                    Biome.CODEC, PalettedContainer.Strategy.SECTION_BIOMES, fallback);
        }

        LegacyChunkData read(byte[] bytes, int x, int z, int minY, int height) {
            CompoundTag root;
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                root = NbtIo.read(input);
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot decode Retina chunk " + x + "," + z, error);
            }
            if (root.getInt("DataVersion") != SharedConstants.getCurrentVersion().getDataVersion().getVersion()
                    || root.getInt("xPos") != x || root.getInt("zPos") != z)
                throw new IllegalStateException("Unexpected Retina chunk version or position for " + x + "," + z);
            var sections = new CompoundTag[height / 16];
            for (var raw : root.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) raw;
                int index = section.getByte("Y") - (minY >> 4);
                if (index < 0 || index >= sections.length) continue;
                sections[index] = section;
            }
            for (var section : sections) if (section == null)
                throw new IllegalStateException("Missing Retina storage section for " + x + "," + z);
            return new LegacyChunkData(root, sections, this, minY, height);
        }

        private PalettedContainer<BlockState> states(CompoundTag section) {
            return require(BLOCKS.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
        }

        private LevelChunkSection section(CompoundTag section) {
            return new LevelChunkSection(states(section), require(biomes.parse(ops, section.getCompound("biomes"))));
        }

        private static <T> T require(DataResult<T> result) {
            return result.getOrThrow(false, message -> { throw new IllegalStateException("Cannot bind Retina palette: " + message); });
        }
    }
}
