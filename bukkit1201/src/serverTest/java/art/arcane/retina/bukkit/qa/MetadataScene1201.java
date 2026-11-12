package art.arcane.retina.bukkit.qa;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.EndGateway;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Objects;

final class MetadataScene1201 extends MetadataFixtureScene {
    private static final String DATA = """
        {block_entities:[
          {id:"minecraft:end_gateway",x:2,y:65,z:2,Age:2401L,ExactTeleport:1b,exit_portal:[I;8,80,8]},
          {id:"minecraft:chest",x:4,y:65,z:2,Items:[{Slot:0b,id:"minecraft:diamond",Count:7b,tag:{display:{Name:"{\\"text\\":\\"Retina fixture\\"}"},retina_fixture:{exact_seed:9223372036854775791L}}}]},
          {id:"minecraft:chest",x:6,y:65,z:2,LootTable:"minecraft:chests/simple_dungeon",LootTableSeed:-9223372036854775767L},
          {id:"minecraft:mob_spawner",x:10,y:65,z:2,Delay:77s,MinSpawnDelay:60s,MaxSpawnDelay:600s,SpawnCount:2s,MaxNearbyEntities:5s,RequiredPlayerRange:12s,SpawnRange:3s,
           SpawnData:{entity:{id:"minecraft:pig"}}}
        ],entities:[{id:"minecraft:armor_stand",Pos:[8.5d,66d,8.5d],Rotation:[33f,0f],NoGravity:1b,Invulnerable:1b}]}
        """;

    MetadataScene1201(Plugin retina) throws ReflectiveOperationException {
        super(retina, DATA);
    }

    static void verifyBlocks(World world) {
        var gateway = (EndGateway) world.getBlockAt(2, 65, 2).getState();
        require(gateway.isExactTeleport() && gateway.getAge() >= 2401 && gateway.getExitLocation() != null
                && gateway.getExitLocation().getBlockY() == 80, "gateway");
        var chest = (Chest) world.getBlockAt(4, 65, 2).getState();
        var item = Objects.requireNonNull(chest.getBlockInventory().getItem(0));
        require(item.getType() == Material.DIAMOND && item.getAmount() == 7
                && item.getItemMeta().getAsString().contains("9223372036854775791"), "inventory tag and full-width value");
        var loot = (Lootable) world.getBlockAt(6, 65, 2).getState();
        require(loot.getLootTable() != null && loot.getLootTable().getKey().toString().equals("minecraft:chests/simple_dungeon")
                && loot.getSeed() == Long.MIN_VALUE + 41, "loot table and seed");
        var spawner = (CreatureSpawner) world.getBlockAt(10, 65, 2).getState();
        require(spawner.getSpawnedType() == EntityType.PIG && spawner.getDelay() == 77
                && spawner.getMinSpawnDelay() == 60 && spawner.getMaxSpawnDelay() == 600 && spawner.getSpawnCount() == 2
                && spawner.getRequiredPlayerRange() == 12 && spawner.getSpawnRange() == 3 && spawner.getMaxNearbyEntities() == 5,
                "spawner");
    }

    static void verifyEntities(World world) {
        require(Arrays.stream(world.getChunkAt(0, 0).getEntities()).anyMatch(entity -> entity instanceof ArmorStand stand
                && stand.isInvulnerable() && !stand.hasGravity() && Math.abs(stand.getLocation().getYaw() - 33) < 0.01),
                "entity type, rotation and flags");
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalStateException("Metadata fixture differs: " + field);
    }
}
