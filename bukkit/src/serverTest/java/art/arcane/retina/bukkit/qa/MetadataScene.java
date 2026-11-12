package art.arcane.retina.bukkit.qa;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Controlled API delivery fixture. Reflection is limited to Retina's own private dispatcher. */
final class MetadataScene extends MetadataFixtureScene {
    private static final String DATA = """
        {block_entities:[
          {id:"minecraft:end_gateway",x:2,y:65,z:2,Age:2401L,ExactTeleport:1b,exit_portal:[I;8,80,8]},
          {id:"minecraft:chest",x:4,y:65,z:2,Items:[{Slot:0b,id:"minecraft:diamond",count:7,components:{"minecraft:custom_name":"Retina fixture","minecraft:custom_data":{exact_seed:9223372036854775791L}}}]},
          {id:"minecraft:chest",x:6,y:65,z:2,LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:-9223372036854775767L},
          {id:"minecraft:mob_spawner",x:10,y:65,z:2,Delay:77s,MinSpawnDelay:60s,MaxSpawnDelay:600s,SpawnCount:2s,MaxNearbyEntities:5s,RequiredPlayerRange:12s,SpawnRange:3s,
           SpawnData:{entity:{id:"minecraft:pig"}},SpawnPotentials:[{weight:3,data:{entity:{id:"minecraft:pig"},custom_spawn_rules:{block_light_limit:[I;2,12],sky_light_limit:[I;3,13]}}}]}
        ],entities:[{id:"minecraft:armor_stand",Pos:[8.5d,66d,8.5d],Rotation:[33f,0f],NoGravity:1b,Invulnerable:1b,Invisible:1b,Small:1b,Marker:1b}]}
        """;
    MetadataScene(Plugin retina) throws ReflectiveOperationException {
        super(retina, DATA);
    }

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
