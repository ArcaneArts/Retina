package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SculkPatchVerification {
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Map<Block, String> TILES = Map.of(Blocks.SCULK_SENSOR, "minecraft:sculk_sensor",
            Blocks.SCULK_CATALYST, "minecraft:sculk_catalyst", Blocks.SCULK_SHRIEKER, "minecraft:sculk_shrieker");
    private static final Set<Block> OUTPUT = Set.of(Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_SENSOR,
            Blocks.SCULK_CATALYST, Blocks.SCULK_SHRIEKER);
    private record Probe(String name, String layers) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("dry", """
                    {"block":"minecraft:stone","height":128},{"block":"minecraft:air","height":12}
                    """),
            new Probe("wet", """
                    {"block":"minecraft:stone","height":128},{"block":"minecraft:water","height":2},
                    {"block":"minecraft:air","height":10}
                    """),
            new Probe("protected", """
                    {"block":"minecraft:quartz_block","height":128},{"block":"minecraft:air","height":12}
                    """),
            new Probe("sealed", """
                    {"block":"minecraft:stone","height":132},{"block":"minecraft:air","height":8}
                    """));

    private SculkPatchVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-sculk-patches");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina Deep Dark sculk fixture"}}
                """);
        write(pack.resolve("data/minecraft/tags/blocks/sculk_replaceable_world_gen.json"), """
                {"replace":true,"values":["minecraft:stone"]}
                """);
        write(pack.resolve("data/retina/worldgen/configured_feature/sculk_probe.json"), """
                {"type":"minecraft:sculk_patch","config":{
                   "charge_count":10,"amount_per_charge":200,"spread_attempts":64,"spread_rounds":1,
                   "growth_rounds":1,"catalyst_chance":1.0,"extra_rare_growths":12}}
                """);
        write(pack.resolve("data/retina/worldgen/placed_feature/sculk_probe.json"), """
                {"feature":"retina:sculk_probe","placement":[{"type":"minecraft:rarity_filter","chance":6},
                  {"type":"minecraft:random_offset","xz_spread":-1,"y_spread":0},
                  {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":64}}},
                  {"type":"minecraft:biome"}]}
                """);
        write(pack.resolve("data/minecraft/worldgen/biome/plains.json"), """
                {"has_precipitation":false,"temperature":0.8,"downfall":0,
                 "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                 "carvers":{},"features":[[],[],[],[],[],[],[],[],[],["retina:sculk_probe"]],
                 "spawners":{},"spawn_costs":{}}
                """);
        for (var probe : PROBES) {
            write(pack.resolve("data/retina/dimension/sculk_" + probe.name + ".json"), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:plains","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[%s,{"block":"minecraft:bedrock","height":8}]}}}
                    """.formatted(probe.layers));
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            require(stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject()).anyMatch(v ->
                    v.get("source").getAsString().equals("minecraft:sculk_patch_deep_dark")
                            && v.get("kind").getAsString().equals("sculk_patch")), "stock placed feature");
            for (var probe : PROBES) {
                String dimension = "retina:sculk_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe.name);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "sculk_patch_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, String probe)
            throws Exception {
        var counts = new HashMap<Block, Integer>();
        var samples = new JsonArray();
        var sampled = new HashSet<Block>();
        int seams = 0, wet = 0, tiles = 0, waterTicks = 0;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var cp = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(cp)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "target data version");
                    var entities = entities(chunk);
                    for (var raw : chunk.getList("fluid_ticks", Tag.TAG_COMPOUND)) {
                        if (((CompoundTag) raw).getString("i").equals("minecraft:water")) waterTicks++;
                    }
                    for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) raw;
                        int yBase = section.getByte("Y") * 16;
                        if (yBase < 48 || yBase > 64 || !section.contains("block_states")) continue;
                        var states = decode(section);
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            var block = state.getBlock();
                            if (!OUTPUT.contains(block)) continue;
                            int at = yBase + y;
                            counts.merge(block, 1, Integer::sum);
                            if (block == Blocks.SCULK) {
                                require(at == 63, "solid sculk replaces tagged cave floor");
                                if (x == 0 || z == 0) seams++;
                            }
                            if (block == Blocks.SCULK_VEIN && state.getValue(BlockStateProperties.WATERLOGGED)) wet++;
                            if (block == Blocks.SCULK_SHRIEKER)
                                require(state.getValue(BlockStateProperties.CAN_SUMMON), "worldgen shrieker summons");
                            var pos = new BlockPos(cx * 16 + x, at, cz * 16 + z);
                            if (TILES.containsKey(block)) {
                                require(TILES.get(block).equals(entities.remove(pos)), "saved default block entity " + pos);
                                tiles++;
                            }
                            if (probe.equals("dry") && cx >= -28 && cz >= 4 && sampled.add(block)) {
                                samples.add(sample(definition, dimension, cp, pos, state));
                            }
                        }
                    }
                    require(entities.isEmpty(), "no orphaned sculk block entities");
                }
            }
        }

        switch (probe) {
            case "dry" -> require(counts.keySet().containsAll(OUTPUT) && seams > 0 && wet == 0 && samples.size() == 5,
                    "dry spread, growth and cross-chunk seams " + counts);
            case "wet" -> require(wet > 0 && waterTicks > 0 && seams > 0, "waterlogged vein spread and fluid updates " + counts);
            case "protected" -> require(!counts.containsKey(Blocks.SCULK) && counts.containsKey(Blocks.SCULK_CATALYST),
                    "loaded replacement tag protects substrate " + counts);
            case "sealed" -> require(counts.isEmpty(), "sealed cavity rejects sculk " + counts);
            default -> throw new AssertionError(probe);
        }
        var report = new JsonObject();
        report.add("samples", samples);
        report.addProperty("sculk", counts.getOrDefault(Blocks.SCULK, 0));
        report.addProperty("veins", counts.getOrDefault(Blocks.SCULK_VEIN, 0));
        report.addProperty("seam_sculk", seams);
        report.addProperty("waterlogged_veins", wet);
        report.addProperty("water_ticks", waterTicks);
        report.addProperty("tiles", tiles);
        return report;
    }

    private static Map<BlockPos, String> entities(CompoundTag chunk) {
        var entities = new HashMap<BlockPos, String>();
        for (var raw : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            var tag = (CompoundTag) raw;
            String id = tag.getString("id");
            if (TILES.containsValue(id)) {
                var pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                require(entities.put(pos, id) == null, "duplicate block entity " + pos);
            }
        }
        return entities;
    }

    private static JsonObject sample(NativeWorldDefinition definition, String dimension, ChunkPos cp,
                                     BlockPos pos, BlockState expected) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension, cp.x, cp.z)))) {
            var chunk = NbtIo.read(input);
            boolean found = false;
            for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) raw;
                if (section.getByte("Y") == Math.floorDiv(pos.getY(), 16) && section.contains("block_states")) {
                    found = decode(section).get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15) == expected;
                }
            }
            require(found, "direct chunk state " + pos);
            if (TILES.containsKey(expected.getBlock()))
                require(TILES.get(expected.getBlock()).equals(entities(chunk).get(pos)), "direct chunk block entity " + pos);
        }
        var sample = new JsonObject();
        sample.addProperty("x", pos.getX());
        sample.addProperty("y", pos.getY());
        sample.addProperty("z", pos.getZ());
        String block = BuiltInRegistries.BLOCK.getKey(expected.getBlock()).toString();
        if (expected.is(Blocks.SCULK_SHRIEKER)) block += "[can_summon=true]";
        sample.addProperty("state", block);
        if (TILES.containsKey(expected.getBlock())) sample.addProperty("entity", TILES.get(expected.getBlock()));
        return sample;
    }

    private static PalettedContainer<BlockState> decode(CompoundTag section) {
        return LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Sculk patch verification failed: " + label);
    }
}
