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
        var random = level.getChunkSource().randomState();
        for (int z = 0; z < 16; z += 3) {
            for (int x = 0; x < 16; x += 3) {
                int globalX = position.getMinBlockX() + x;
                int globalZ = position.getMinBlockZ() + z;
                int expected = generator.getBaseHeight(globalX, globalZ, Heightmap.Types.WORLD_SURFACE, level, random);
                int actual = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
                var column = generator.getBaseColumn(globalX, globalZ, level, random);
                if (actual != expected
                        || !level.getBlockState(new BlockPos(globalX, actual - 1, globalZ)).equals(column.getBlock(actual - 1))
                        || !level.getBlockState(new BlockPos(globalX, actual, globalZ)).isAir()
                        || !column.getBlock(actual).isAir()) {
                    Retina.LOGGER.error("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"fail\",\"context\":{\"x\":{},\"z\":{},\"gpu\":{},\"minecraft\":{}}}", globalX, globalZ, expected, actual);
                    return;
                }
            }
        }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_height_roundtrip\",\"status\":\"pass\",\"context\":{\"columns\":36,\"chunks\":{}}}", generator.metrics().snapshot().total());
    }
}
