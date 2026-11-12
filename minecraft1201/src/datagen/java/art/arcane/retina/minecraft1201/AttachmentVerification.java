package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class AttachmentVerification {
    private static final String DIMENSION = "retina:attachment_probe";
    private static final int RX = -1;
    private static final int RZ = 0;
    private static final Set<String> PLANTS = Set.of("minecraft:vine", "minecraft:glow_lichen", "minecraft:sculk_vein");
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private AttachmentVerification() { }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var profile = JsonParser.parseString(definition.diagnosticProfile(DIMENSION)).getAsJsonObject();
            var sources = new HashSet<String>();
            for (var raw : profile.getAsJsonArray("decorations")) {
                var recipe = raw.getAsJsonObject();
                if (recipe.get("kind").getAsString().equals("attachment_growth")) sources.add(recipe.get("source").getAsString());
            }
            require(sources.equals(Set.of("retina:lichen_probe", "retina:vein_probe", "retina:vine_probe")), "loaded attachment roots");
            var directory = Files.createDirectories(world.resolve("dimensions/retina/attachment_probe/region"));
            var region = directory.resolve("r." + RX + "." + RZ + ".mca");
            definition.bindDimension(DIMENSION, 2);
            long started = System.nanoTime();
            require(definition.generateRegion(DIMENSION, RX, RZ, region).generated() == 1024, "fresh attachment region");
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            var result = inspect(region);
            require(definition.generateRegion(DIMENSION, RX, RZ, region).preserved() == 1024, "attachment region reopen");
            result.addProperty("event", "attachments_1201");
            result.addProperty("status", "pass");
            result.addProperty("milliseconds", elapsed);
            System.out.println("QA_EVT " + result);
        }
    }

    private static JsonObject inspect(Path region) throws Exception {
        int vines = 0, lichen = 0, veins = 0, wet = 0, manyFaces = 0, checked = 0, crossChunk = 0;
        try (var file = new RegionFile(region, region.getParent(), true)) {
            var cache = new LinkedHashMap<Long, PalettedContainer<BlockState>>(64, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, PalettedContainer<BlockState>> eldest) {
                    return size() > 64;
                }
            };
            for (int slot = 0; slot < 1024; slot++) {
                var chunk = new ChunkPos(RX * 32 + (slot & 31), RZ * 32 + (slot >> 5));
                var data = read(file, chunk);
                for (var value : data.getList("sections", Tag.TAG_COMPOUND)) {
                    var section = (CompoundTag) value;
                    var states = section.getCompound("block_states");
                    if (states.getList("palette", Tag.TAG_COMPOUND).stream()
                            .noneMatch(state -> PLANTS.contains(((CompoundTag) state).getString("Name")))) continue;
                    int sy = section.getByte("Y");
                    var blocks = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, states));
                    cache.put(SectionPos.asLong(chunk.x, sy, chunk.z), blocks);
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var state = blocks.get(x, y, z);
                        boolean vine = state.is(Blocks.VINE);
                        boolean glow = state.is(Blocks.GLOW_LICHEN);
                        boolean vein = state.is(Blocks.SCULK_VEIN);
                        if (!vine && !glow && !vein) continue;
                        if (vine) vines++;
                        if (glow) lichen++;
                        if (vein) veins++;
                        if (!vine && state.getValue(BlockStateProperties.WATERLOGGED)) wet++;
                        var pos = new BlockPos(chunk.getMinBlockX() + x, sy * 16 + y, chunk.getMinBlockZ() + z);
                        int faces = 0;
                        for (var face : Direction.values()) {
                            if (vine && face == Direction.DOWN) continue;
                            var property = vine ? VineBlock.getPropertyForFace(face) : MultifaceBlock.getFaceProperty(face);
                            if (!state.getValue(property)) continue;
                            faces++;
                            if (glow) require(face.getAxis().isHorizontal(), "fixture glow lichen is wall-only " + pos);
                            var neighbor = pos.relative(face);
                            var supportChunk = new ChunkPos(neighbor);
                            if (supportChunk.getRegionX() != RX || supportChunk.getRegionZ() != RZ) continue;
                            var support = stateAt(file, cache, supportChunk, neighbor);
                            require(MultifaceBlock.canAttachTo(EmptyBlockGetter.INSTANCE, face, neighbor, support), "unsupported attachment face " + pos);
                            if (glow) require(support.is(Blocks.STONE) || support.is(Blocks.DEEPSLATE), "fixture lichen support tag " + pos);
                            checked++;
                            if (!supportChunk.equals(chunk)) crossChunk++;
                        }
                        require(faces > 0, "invalid attachment faces " + pos);
                        if (vein && faces > 1) manyFaces++;
                    }
                }
            }
        }
        require(vines > 0 && lichen > 0 && veins > 0, "all attachment types");
        require(wet > 0 && manyFaces > 0 && crossChunk > 0, "waterlogging, spread and cross-chunk support");
        var result = new JsonObject();
        result.addProperty("vines", vines);
        result.addProperty("glow_lichen", lichen);
        result.addProperty("sculk_veins", veins);
        result.addProperty("waterlogged", wet);
        result.addProperty("multiface_veins", manyFaces);
        result.addProperty("checked_supports", checked);
        result.addProperty("cross_chunk_supports", crossChunk);
        return result;
    }

    private static BlockState stateAt(RegionFile file, Map<Long, PalettedContainer<BlockState>> cache, ChunkPos chunk,
                                      BlockPos pos) throws Exception {
        int sy = SectionPos.blockToSectionCoord(pos.getY());
        long key = SectionPos.asLong(chunk.x, sy, chunk.z);
        var blocks = cache.get(key);
        if (blocks == null) {
            var data = read(file, chunk);
            for (var value : data.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) value;
                if (section.getByte("Y") != sy) continue;
                blocks = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                cache.put(key, blocks);
                break;
            }
        }
        return blocks == null ? Blocks.AIR.defaultBlockState() : blocks.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private static CompoundTag read(RegionFile file, ChunkPos chunk) throws Exception {
        try (var input = file.getChunkDataInputStream(chunk)) {
            require(input != null, "attachment chunk " + chunk);
            return NbtIo.read(input);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
