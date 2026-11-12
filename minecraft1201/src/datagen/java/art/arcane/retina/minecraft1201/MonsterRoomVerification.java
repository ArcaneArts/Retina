package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class MonsterRoomVerification {
    private static final String LOOT = "minecraft:chests/simple_dungeon";
    private static final Set<String> MOBS = Set.of("minecraft:zombie", "minecraft:skeleton", "minecraft:spider");
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private record Probe(String name, String biome, String material, int height) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("open", "desert", "stone", 128),
            new Probe("closed", "badlands", "stone", 128),
            new Probe("roofless", "desert", "stone", 47),
            new Probe("protected", "desert", "quartz_block", 128));

    private MonsterRoomVerification() { }

    static void pack(Path packs) throws Exception {
        Path pack = packs.resolve("retina-monster-rooms");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina monster room fixture"}}
                """);
        write(pack.resolve("data/minecraft/tags/blocks/features_cannot_replace.json"), """
                {"replace":false,"values":["minecraft:quartz_block"]}
                """);
        write(data(pack, "worldgen/configured_feature", "entrance"), """
                {"type":"minecraft:block_column","config":{"direction":"up","allowed_placement":{"type":"minecraft:true"},
                 "prioritize_tip":false,"layers":[{"height":2,"provider":{"type":"minecraft:simple_state_provider",
                 "state":{"Name":"minecraft:cave_air"}}}]}}
                """);
        for (int y : new int[]{24, -20}) {
            String level = y > 0 ? "shallow" : "deep";
            placed(pack, "entrance_a_" + level, "retina:room_entrance", y, 2);
            placed(pack, "entrance_b_" + level, "retina:room_entrance", y, 3);
            placed(pack, level, "minecraft:monster_room", y, -1);
        }
        for (String biome : List.of("desert", "badlands")) {
            String entrances = biome.equals("desert")
                    ? "\"retina:room_entrance_a_shallow\",\"retina:room_entrance_b_shallow\","
                    + "\"retina:room_entrance_a_deep\",\"retina:room_entrance_b_deep\"," : "";
            write(pack.resolve("data/minecraft/worldgen/biome/" + biome + ".json"), """
                    {"has_precipitation":false,"temperature":2.0,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],[%s"retina:room_shallow","retina:room_deep"]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(entrances));
        }
        for (var probe : PROBES) {
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:%s","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[{"block":"minecraft:%s","height":%d}]}}}
                    """.formatted(probe.biome, probe.material, probe.height));
        }
    }

    private static void placed(Path pack, String name, String feature, int y, int offset) throws Exception {
        write(data(pack, "worldgen/placed_feature", name), """
                {"feature":"%s","placement":[{"type":"minecraft:random_offset","xz_spread":%d,"y_spread":0},
                 {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":%d}}},
                 {"type":"minecraft:biome"}]}
                """.formatted(feature, offset, y));
    }

    private static Path data(Path pack, String kind, String name) {
        return pack.resolve("data/retina/" + kind + "/room_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var roots = stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject())
                    .filter(v -> v.get("kind").getAsString().equals("monster_room"))
                    .map(v -> v.get("source").getAsString()).collect(Collectors.toSet());
            require(roots.containsAll(Set.of("minecraft:monster_room", "minecraft:monster_room_deep")), "stock placement roots");
            for (var probe : PROBES) {
                String dimension = "retina:room_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "monster_room_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, Probe probe) throws Exception {
        var positions = new HashSet<BlockPos>();
        var mobs = new HashSet<String>();
        var chestFacings = new HashSet<String>();
        var samples = new JsonArray();
        int shallow = 0, deep = 0, chests = 0;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var cp = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(cp)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "target chunk data version");
                    var tags = chunk.getList("block_entities", Tag.TAG_COMPOUND);
                    boolean sampled = false;
                    for (var raw : tags) {
                        var tag = (CompoundTag) raw;
                        var pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                        require(new ChunkPos(pos).equals(cp) && positions.add(pos), "entity owning chunk and identity " + pos);
                        var block = state(chunk, pos);
                        var entity = BlockEntity.loadStatic(pos, block, tag);
                        var sample = new JsonObject();
                        sample.addProperty("x", pos.getX());
                        sample.addProperty("y", pos.getY());
                        sample.addProperty("z", pos.getZ());
                        if (entity instanceof SpawnerBlockEntity spawner) {
                            require(block.is(Blocks.SPAWNER), "spawner material");
                            var nbt = spawner.saveWithFullMetadata();
                            String mob = nbt.getCompound("SpawnData").getCompound("entity").getString("id");
                            require(MOBS.contains(mob) && nbt.getShort("Delay") == 20 && nbt.getShort("SpawnRange") == 4,
                                    "vanilla spawner tag and mob");
                            mobs.add(mob);
                            if (pos.getY() == 24) shallow++;
                            else {
                                require(pos.getY() == -20, "deep room placement");
                                deep++;
                            }
                            sample.addProperty("mob", mob);
                        } else {
                            require(entity instanceof ChestBlockEntity && block.is(Blocks.CHEST), "dungeon chest material");
                            var nbt = entity.saveWithFullMetadata();
                            require(nbt.getString("LootTable").equals(LOOT) && nbt.getLong("LootTableSeed") != 0, "lazy chest loot");
                            chests++;
                            chestFacings.add(block.getValue(BlockStateProperties.HORIZONTAL_FACING).getName());
                            sample.addProperty("seed", nbt.getLong("LootTableSeed"));
                        }
                        if (pos.getX() >= -20 && pos.getX() <= -14 && pos.getZ() >= 28 && pos.getZ() <= 34) {
                            samples.add(sample);
                            sampled = true;
                        }
                    }
                    if (sampled) {
                        try (var direct = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension, cx, cz)))) {
                            require(NbtIo.read(direct).getList("block_entities", Tag.TAG_COMPOUND).equals(tags), "direct chunk metadata");
                        }
                    }
                }
            }
            var p = new BlockPos(-16, 26, 32);
            try (var input = region.getChunkDataInputStream(new ChunkPos(p))) {
                var block = state(NbtIo.read(input), p);
                if (probe.name.equals("open")) require(block.isAir(), "carved room across two chunk seams");
                if (probe.name.equals("protected")) require(block.is(Blocks.QUARTZ_BLOCK), "protected room material");
            }
        }
        if (probe.name.equals("open")) {
            require(shallow == 1024 && deep == 1024 && chests > 100 && mobs.equals(MOBS) && chestFacings.size() >= 3,
                    "room geometry and metadata " + shallow + "/" + deep + "/" + chests + "/" + mobs);
            require(samples.asList().stream().anyMatch(v -> v.getAsJsonObject().has("seed")), "cross-seam chest sample");
        } else {
            require(positions.isEmpty(), "no metadata in " + probe.name);
        }
        var report = new JsonObject();
        report.add("samples", samples);
        report.addProperty("shallow_spawners", shallow);
        report.addProperty("deep_spawners", deep);
        report.addProperty("chests", chests);
        return report;
    }

    private static BlockState state(CompoundTag chunk, BlockPos pos) {
        for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) raw;
            if (section.getByte("Y") != Math.floorDiv(pos.getY(), 16)) continue;
            var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
            return states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Monster room verification failed: " + label);
    }
}
