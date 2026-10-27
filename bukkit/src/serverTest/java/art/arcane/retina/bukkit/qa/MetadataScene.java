package art.arcane.retina.bukkit.qa;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.generator.*;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;

/** Controlled API delivery fixture. Reflection is limited to Retina's own private dispatcher. */
final class MetadataScene extends ChunkGenerator {
    private static final String DATA = """
        {block_entities:[
          {id:"minecraft:end_gateway",x:2,y:65,z:2,Age:2401L,ExactTeleport:1b,exit_portal:[I;8,80,8]},
          {id:"minecraft:chest",x:4,y:65,z:2,Items:[{Slot:0b,id:"minecraft:diamond",count:7,components:{"minecraft:custom_name":"Retina fixture","minecraft:custom_data":{exact_seed:9223372036854775791L}}}]},
          {id:"minecraft:chest",x:6,y:65,z:2,LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:-9223372036854775767L},
          {id:"minecraft:mob_spawner",x:10,y:65,z:2,Delay:77s,MinSpawnDelay:60s,MaxSpawnDelay:600s,SpawnCount:2s,MaxNearbyEntities:5s,RequiredPlayerRange:12s,SpawnRange:3s,
           SpawnData:{entity:{id:"minecraft:pig"}},SpawnPotentials:[{weight:3,data:{entity:{id:"minecraft:pig"},custom_spawn_rules:{block_light_limit:[I;2,12],sky_light_limit:[I;3,13]}}}]}
        ],entities:[{id:"minecraft:armor_stand",Pos:[8.5d,66d,8.5d],Rotation:[33f,0f],NoGravity:1b,Invulnerable:1b,Invisible:1b,Small:1b,Marker:1b}]}
        """;
    private final Object delivery, data;
    private final Method populate;

    MetadataScene(Plugin retina) throws ReflectiveOperationException {
        var loader = retina.getClass().getClassLoader();
        var strings = loader.loadClass("art.arcane.retina.internal.kyori.adventure.nbt.TagStringIO");
        var compound = loader.loadClass("art.arcane.retina.internal.kyori.adventure.nbt.CompoundBinaryTag");
        var codec = strings.getMethod("tagStringIO").invoke(null);
        data = strings.getMethod("asCompound", String.class).invoke(codec, DATA);
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

    static void verify(World world) {
        var gateway = (EndGateway) world.getBlockAt(2, 65, 2).getState();
        require(gateway.isExactTeleport() && gateway.getAge() >= 2401 && gateway.getExitLocation() != null
                && gateway.getExitLocation().getBlockY() == 80, "gateway");
        var chest = (Chest) world.getBlockAt(4, 65, 2).getState();
        var item = Objects.requireNonNull(chest.getBlockInventory().getItem(0));
        require(item.getType() == Material.DIAMOND && item.getAmount() == 7
                && item.getItemMeta().getAsString().contains("9223372036854775791"), "inventory components and full-width value");
        var loot = (Lootable) world.getBlockAt(6, 65, 2).getState();
        require(loot.getLootTable() != null && loot.getLootTable().getKey().toString().equals("minecraft:chests/simple_dungeon")
                && loot.getSeed() == Long.MIN_VALUE + 41, "loot table and seed");
        var spawner = (CreatureSpawner) world.getBlockAt(10, 65, 2).getState();
        require(spawner.getSpawnedType() == org.bukkit.entity.EntityType.PIG && spawner.getDelay() == 77
                && spawner.getMinSpawnDelay() == 60 && spawner.getMaxSpawnDelay() == 600 && spawner.getSpawnCount() == 2
                && spawner.getRequiredPlayerRange() == 12 && spawner.getSpawnRange() == 3 && spawner.getMaxNearbyEntities() == 5
                && spawner.getPotentialSpawns().size() == 1 && spawner.getPotentialSpawns().getFirst().getSpawnWeight() == 3
                && spawner.getPotentialSpawns().getFirst().getSpawnRule() != null
                // CraftBukkit 26.3's getter returns max for minBlockLight; persisted NBT checks the true value.
                && spawner.getPotentialSpawns().getFirst().getSpawnRule().getMaxBlockLight() == 12
                && spawner.getPotentialSpawns().getFirst().getSpawnRule().getMinSkyLight() == 3
                && spawner.getPotentialSpawns().getFirst().getSpawnRule().getMaxSkyLight() == 13, "spawner");
    }
    static void verifyEntities(World world) {
        // Chunk#getEntities waits for the server's separate entity-storage load.
        require(Arrays.stream(world.getChunkAt(0, 0).getEntities()).anyMatch(e -> e instanceof ArmorStand stand && stand.isMarker() && stand.isSmall()
                && stand.isInvisible() && stand.isInvulnerable() && !stand.hasGravity()), "entity attributes");
    }
    private static void require(boolean value, String field) { if (!value) throw new IllegalStateException("Metadata fixture differs: " + field); }
}
