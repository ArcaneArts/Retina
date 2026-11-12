package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class DripstoneVerification {
    private static final JsonArray DRY_CAVE = layers(layer("stone", 8), layer("air", 8), layer("stone", 8));
    private static final JsonArray TALL_CAVE = layers(layer("stone", 8), layer("air", 16), layer("stone", 8));
    private static final List<Probe> PROBES = List.of(
            new Probe("pointed", layers(layer("quartz_block", 8), layer("water", 2), layer("air", 10), layer("quartz_block", 8)),
                    "pointed_floor", "pointed_ceiling"),
            new Probe("no_base", layers(layer("bricks", 8), layer("water", 2), layer("air", 10), layer("bricks", 8)),
                    "pointed_floor", "pointed_ceiling"),
            new Probe("cluster", DRY_CAVE, "cluster"),
            new Probe("pool", DRY_CAVE, "pool"),
            new Probe("large", TALL_CAVE, "large"),
            new Probe("no_roof", layers(layer("stone", 8), layer("air", 16)), "large"));
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private record Probe(String name, JsonArray layers, String... placements) { }

    private DripstoneVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-dripstone");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"Retina dripstone fixture\"}}");
        write(pack.resolve("data/minecraft/tags/blocks/dripstone_replaceable_blocks.json"),
                "{\"replace\":false,\"values\":[\"minecraft:quartz_block\"]}");
        configured(pack, "pointed", "pointed_dripstone", """
                {"chance_of_taller_dripstone":1,"chance_of_directional_spread":1,
                 "chance_of_spread_radius2":1,"chance_of_spread_radius3":1}
                """);
        for (var name : List.of("cluster", "pool")) {
            configured(pack, name, "dripstone_cluster", """
                    {"floor_to_ceiling_search_range":12,"height":8,"radius":3,
                     "max_stalagmite_stalactite_height_diff":4,"height_deviation":5,"dripstone_block_layer_thickness":2,
                     "density":{"type":"minecraft:trapezoid","value":{"min":1,"max":1.5,"plateau":-0.1}},"wetness":%d,
                     "chance_of_dripstone_column_at_max_distance_from_center":1,
                     "max_distance_from_edge_affecting_chance_of_dripstone_column":1,
                     "max_distance_from_center_affecting_height_bias":64}
                    """.formatted(name.equals("pool") ? 1 : 0));
        }
        configured(pack, "large", "large_dripstone", """
                {"floor_to_ceiling_search_range":20,"column_radius":4,
                 "height_scale":{"type":"minecraft:clamped_normal","value":{"mean":2.5,"deviation":-0.25,"min":2,"max":3}},
                 "max_column_radius_to_cave_height_ratio":1,"stalactite_bluntness":0.7,"stalagmite_bluntness":0.7,
                 "wind_speed":{"type":"minecraft:trapezoid","value":{"min":0.65,"max":0.75,"plateau":-0.25}},
                 "min_radius_for_wind":1,"min_bluntness_for_wind":0.1}
                """);
        placed(pack, "pointed_floor", "pointed", -56);
        placed(pack, "pointed_ceiling", "pointed", -45);
        placed(pack, "cluster", "cluster", -52);
        placed(pack, "pool", "pool", -52);
        placed(pack, "large", "large", -48);
        for (var probe : PROBES) {
            String roots = java.util.Arrays.stream(probe.placements).map(id -> "\"retina:dripstone_" + id + "\"")
                    .collect(Collectors.joining(","));
            write(data(pack, "worldgen/biome", probe.name), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],[],[],[%s]],"spawners":{},"spawn_costs":{}}
                    """.formatted(roots));
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                      "biome":"retina:dripstone_%s","features":true,"lakes":false,"structure_overrides":[],"layers":%s}}}
                    """.formatted(probe.name, probe.layers));
        }
    }

    private static JsonArray layers(JsonObject... blocks) {
        var layers = new JsonArray();
        for (var block : blocks) layers.add(block);
        return layers;
    }

    private static JsonObject layer(String block, int height) {
        var layer = new JsonObject();
        layer.addProperty("block", "minecraft:" + block);
        layer.addProperty("height", height);
        return layer;
    }

    private static void configured(Path pack, String id, String type, String config) throws Exception {
        write(data(pack, "worldgen/configured_feature", id),
                "{\"type\":\"minecraft:" + type + "\",\"config\":" + config + "}");
    }

    private static void placed(Path pack, String id, String feature, int y) throws Exception {
        write(data(pack, "worldgen/placed_feature", id), """
                {"feature":"retina:dripstone_%s","placement":[{"type":"minecraft:random_offset","xz_spread":15,"y_spread":0},
                 {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":%d}}},
                 {"type":"minecraft:biome"}]}
                """.formatted(feature, y));
    }

    private static Path data(Path pack, String folder, String id) {
        return pack.resolve("data/retina/" + folder + "/dripstone_" + id + ".json");
    }

    private static void write(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var profile = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var roots = profile.getAsJsonArray("decorations").asList().stream().map(item -> item.getAsJsonObject())
                    .filter(item -> item.get("source").getAsString().contains("dripstone")).collect(Collectors.toMap(
                            item -> item.get("source").getAsString(), item -> item.get("kind").getAsString()));
            require(roots.keySet().containsAll(Set.of("minecraft:pointed_dripstone", "minecraft:dripstone_cluster",
                    "minecraft:large_dripstone")), "stock dripstone exports " + roots);
            for (var probe : PROBES) {
                var dimension = "retina:dripstone_" + probe.name;
                definition.bindDimension(dimension, 2);
                var path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                long started = System.nanoTime();
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "complete " + probe.name);
                var report = inspect(path, probe.name);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "dripstone_1201");
                report.addProperty("dimension", dimension);
                report.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, String name) throws Exception {
        int blocks = 0, pointed = 0, waterlogged = 0, merged = 0, pools = 0, windy = 0;
        int hanging = 0, rising = 0;
        var thickness = EnumSet.noneOf(DripstoneThickness.class);
        var report = new JsonObject();
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                try (var input = region.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                    var sections = NbtIo.read(input).getList("sections", Tag.TAG_COMPOUND);
                    for (var raw : sections) {
                        var section = (CompoundTag) raw;
                        int base = section.getByte("Y") * 16;
                        if (base != -64 && base != -48 || !section.contains("block_states")) continue;
                        var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            int worldY = base + y;
                            if (state.is(Blocks.DRIPSTONE_BLOCK)) {
                                blocks++;
                                if (x >= 5 && x <= 10 || z >= 5 && z <= 10) windy++;
                                if (worldY > -48) hanging++;
                                if (worldY < -48) rising++;
                            } else if (state.is(Blocks.POINTED_DRIPSTONE)) {
                                pointed++;
                                var size = state.getValue(PointedDripstoneBlock.THICKNESS);
                                thickness.add(size);
                                if (size == DripstoneThickness.TIP_MERGE) merged++;
                                if (state.getValue(PointedDripstoneBlock.WATERLOGGED)) {
                                    waterlogged++;
                                    if (name.equals("pointed")) require(state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.UP
                                            && (worldY == -56 || worldY == -55), "waterlogged ascending dripstone");
                                } else if (name.equals("pointed")) {
                                    require(state.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN
                                            && (worldY == -45 || worldY == -46), "dry hanging dripstone");
                                }
                            } else if (state.is(Blocks.WATER) && worldY == -57) {
                                pools++;
                            } else {
                                continue;
                            }
                            boolean target = name.equals("large") ? state.is(Blocks.DRIPSTONE_BLOCK) : state.is(Blocks.POINTED_DRIPSTONE);
                            if (target && !report.has("sample_x") && cx >= -28 && cx <= -4 && cz >= 4 && cz <= 28) {
                                report.addProperty("sample_x", cx * 16 + x);
                                report.addProperty("sample_y", worldY);
                                report.addProperty("sample_z", cz * 16 + z);
                            }
                        }
                    }
                }
            }
        }
        switch (name) {
            case "pointed" -> require(pointed == 4096 && waterlogged == 2048 && blocks > 2048
                    && thickness.equals(EnumSet.of(DripstoneThickness.TIP, DripstoneThickness.FRUSTUM)), "both tall pointed formations " + pointed);
            case "cluster" -> require(blocks > 1024 && pointed > 1024 && merged > 0 && thickness.size() == 5
                    && pools == 0, "cluster layers and merged tips " + thickness);
            case "pool" -> require(blocks > 1024 && pointed > 1024 && pools > 1024 && waterlogged > 0, "cluster floor pools and waterlogged growth");
            case "large" -> require(blocks > 1024 && pointed == 0 && windy > 0 && rising > 0 && hanging > 0,
                    "wind displaces large floor and ceiling columns beyond the base radius");
            default -> require(blocks == 0 && pointed == 0, "formation rejected without supported cavity " + name);
        }
        report.addProperty("dripstone_blocks", blocks);
        report.addProperty("pointed_blocks", pointed);
        report.addProperty("waterlogged", waterlogged);
        report.addProperty("merged", merged);
        report.addProperty("pool_water", pools);
        report.addProperty("beyond_base_radius", windy);
        return report;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
