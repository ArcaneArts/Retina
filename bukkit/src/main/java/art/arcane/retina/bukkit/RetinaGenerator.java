package art.arcane.retina.bukkit;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.*;
import java.util.List;
import java.util.Random;

final class RetinaGenerator extends ChunkGenerator {
    private final WorldRepository worlds;
    private final String selection;
    private final WorldRepository.Selection session;
    private final BiomeProvider provider = new BiomeProvider() {
        @Override public List<Biome> getBiomes(WorldInfo info) {
            var binding = worlds.resolve(info, selection, session);
            return binding.use(() -> binding.dimension.biomes);
        }
        @Override public Biome getBiome(WorldInfo info, int x, int y, int z) {
            var binding = worlds.resolve(info, selection, session);
            return binding.use(() -> binding.dimension.biome(x, y, z));
        }
    };
    RetinaGenerator(WorldRepository worlds, String selection, WorldRepository.Selection session) {
        this.worlds = worlds; this.selection = selection; this.session = session;
    }
    BiomeProvider provider() { return provider; }
    @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo info) { return provider; }

    @Override public void generateNoise(WorldInfo info, Random random, int x, int z, ChunkData target) {
        var binding = worlds.resolve(info, selection, session);
        binding.use(() -> {
            binding.dimension.chunk(x, z).install(target, binding.dimension::block);
            return null;
        });
    }

    @Override public int getBaseHeight(WorldInfo info, Random random, int x, int z, HeightMap type) {
        try {
            var binding = worlds.resolve(info, selection, session);
            return binding.use(() -> binding.dimension.chunk(x >> 4, z >> 4).height(type, x, z));
        } catch (UnsupportedOperationException error) {
            // Bukkit reserves this exception as a request to use the vanilla delegate.
            throw new IllegalStateException("Retina height delivery failed", error);
        }
    }

    @Override public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new BlockPopulator() {
            @Override public void populate(WorldInfo info, Random random, int x, int z, LimitedRegion region) {
                var binding = worlds.resolve(info, selection, session);
                binding.use(() -> {
                    var context = binding.dimension;
                    context.metadata.populate(context.chunk(x, z).root, region, world);
                    return null;
                });
            }
        });
    }

    @Override public Location getFixedSpawnLocation(World world, Random random) {
        var binding = worlds.resolve(world, selection, session);
        int y = binding.use(() -> Math.max(binding.dimension.spawnHeight,
                binding.dimension.chunk(0, 0).height(HeightMap.MOTION_BLOCKING_NO_LEAVES, 0, 0)));
        return new Location(world, .5, Math.min(y, world.getMaxHeight() - 2), .5);
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean isParallelCapable() { return true; }
}
