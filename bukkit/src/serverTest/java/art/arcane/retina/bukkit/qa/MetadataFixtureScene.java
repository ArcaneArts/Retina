package art.arcane.retina.bukkit.qa;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.*;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;

/** Drives Retina's metadata dispatcher in a public-API population callback. */
class MetadataFixtureScene extends ChunkGenerator {
    private final Object delivery, data;
    private final Method populate;

    MetadataFixtureScene(Plugin retina, String fixture) throws ReflectiveOperationException {
        var loader = retina.getClass().getClassLoader();
        var strings = loader.loadClass("art.arcane.retina.internal.kyori.adventure.nbt.TagStringIO");
        var compound = loader.loadClass("art.arcane.retina.internal.kyori.adventure.nbt.CompoundBinaryTag");
        var codec = strings.getMethod("tagStringIO").invoke(null);
        data = strings.getMethod("asCompound", String.class).invoke(codec, fixture);
        var dispatcher = loader.loadClass("art.arcane.retina.bukkit.MetadataDelivery");
        var constructor = dispatcher.getDeclaredConstructor(java.util.logging.Logger.class, boolean.class); constructor.setAccessible(true);
        delivery = constructor.newInstance(retina.getLogger(), false);
        var validate = dispatcher.getDeclaredMethod("validate", compound); validate.setAccessible(true); validate.invoke(delivery, data);
        populate = dispatcher.getDeclaredMethod("populate", compound, LimitedRegion.class, World.class); populate.setAccessible(true);
    }

    @Override public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override public org.bukkit.block.Biome getBiome(WorldInfo world, int x, int y, int z) { return org.bukkit.block.Biome.THE_END; }
            @Override public List<org.bukkit.block.Biome> getBiomes(WorldInfo world) { return List.of(org.bukkit.block.Biome.THE_END); }
        };
    }
    @Override public void generateNoise(WorldInfo info, Random random, int x, int z, ChunkData data) {
        data.setRegion(0, 64, 0, 16, 65, 16, Material.STONE);
        if (x == 0 && z == 0) {
            data.setBlock(2, 65, 2, Material.END_GATEWAY);
            data.setBlock(4, 65, 2, Material.CHEST);
            data.setBlock(6, 65, 2, Material.CHEST);
            data.setBlock(10, 65, 2, Material.SPAWNER);
        }
    }
    @Override public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new BlockPopulator() {
            @Override public void populate(WorldInfo info, Random random, int x, int z, LimitedRegion region) {
                if (x != 0 || z != 0) return;
                try { populate.invoke(delivery, data, region, world); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("Metadata fixture delivery failed", error.getCause()); }
            }
        });
    }
    @Override public int getBaseHeight(WorldInfo info, Random random, int x, int z, HeightMap type) { return 65; }
    @Override public Location getFixedSpawnLocation(World world, Random random) { return new Location(world, 8.5, 66, 8.5); }
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }

}
