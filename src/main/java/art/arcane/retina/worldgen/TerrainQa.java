package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Opt-in checks against a real loaded Minecraft chunk in a fresh QA world. */
final class TerrainQa {
    private static final Set<RetinaChunkGenerator> CHECKED = Collections.newSetFromMap(new WeakHashMap<>());

    static void check(ServerPlayer player, RetinaChunkGenerator generator) {
        if (!Boolean.getBoolean("retina.qa") || !CHECKED.add(generator)) return;
        ServerLevel level = player.level();
        var position = player.chunkPosition();
        var chunk = level.getChunk(position.x(), position.z());
        var request = generator.request(level.getSeed(), position.x(), position.z());
        var profile = generator.profile();
        var materials = profile == null ? new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()} : profile.materials();
        try (var generated = NativeTerrain.instance().generate(request)) {
            var blocks = generated.blocks();
            int plants = 0, logs = 0, leaves = 0, caveAir = 0, ores = 0, geologySamples = 0;
            for (int z = 0; z < 16; z += 3) for (int x = 0; x < 16; x += 3) {
                int globalX = position.getMinBlockX() + x, globalZ = position.getMinBlockZ() + z;
                for (var type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES}) {
                    int expected = request.minY();
                    for (int y = request.height() - 1; y >= 0; y--) {
                        var state = materials[Byte.toUnsignedInt(blocks.get(java.lang.foreign.ValueLayout.JAVA_BYTE, (long)y * 256 + z * 16 + x))];
                        if (type.isOpaque().test(state)) { expected += y + 1; break; }
                    }
                    int actual = chunk.getHeight(type, x, z) + 1;
                    if (actual != expected) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"fail\",\"context\":{\"x\":{},\"z\":{},\"expected\":{},\"minecraft\":{},\"type\":\"{}\"}}", globalX, globalZ, expected, actual, type);
                        return;
                    }
                }
            }
            for (int y = request.minY(); y < request.minY() + request.height(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var pos = new BlockPos(position.getMinBlockX() + x, y, position.getMinBlockZ() + z);
                var state = chunk.getBlockState(pos);
                if (profile != null && y < generated.heights()[z * 16 + x] - 8) {
                    var expected = materials[Byte.toUnsignedInt(blocks.get(java.lang.foreign.ValueLayout.JAVA_BYTE, (long)(y - request.minY()) * 256 + z * 16 + x))];
                    geologySamples++;
                    if (!state.is(expected.getBlock())) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_geology_roundtrip\",\"status\":\"fail\",\"context\":{\"x\":{},\"y\":{},\"z\":{},\"expected\":\"{}\",\"actual\":\"{}\"}}", pos.getX(), y, pos.getZ(), expected, state);
                        return;
                    }
                    if (state.isAir()) caveAir++;
                    if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) ores++;
                }
                if (state.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                if (state.is(net.minecraft.tags.BlockTags.LEAVES)) leaves++;
                if (state.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock) plants++;
                if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock) {
                    var half = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
                    var other = chunk.getBlockState(half == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? pos.above() : pos.below());
                    if (!other.is(state.getBlock()) || other.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF) == half) {
                        Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_plant_pairs\",\"status\":\"fail\"}"); return;
                    }
                }
            }
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_geology_roundtrip\",\"status\":\"pass\",\"context\":{\"blocks\":{},\"cave_air\":{},\"ores\":{}}}", geologySamples, caveAir, ores);
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"pass\",\"context\":{\"columns\":36,\"heightmaps\":4,\"logs\":{},\"leaves\":{},\"plants\":{}}}", logs, leaves, plants);
        }
    }
}
