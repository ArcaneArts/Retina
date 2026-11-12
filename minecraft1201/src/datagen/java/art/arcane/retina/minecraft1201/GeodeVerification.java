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
import java.util.Set;

final class GeodeVerification {
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Set<Block> BUDS = Set.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER);
    private static final Set<Block> SHELL = Set.of(Blocks.SMOOTH_BASALT, Blocks.CALCITE,
            Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.POLISHED_DIORITE);

    private record Probe(String name, String biome, String substrate, boolean wet, boolean expected) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("dry", "desert", "stone", false, true),
            new Probe("wet", "badlands", "stone", true, true),
            new Probe("invalid", "desert", "quartz_block", false, false),
            new Probe("protected", "desert", "obsidian", false, false));

    private GeodeVerification() { }

    static void pack(Path packs) throws Exception {
        Path pack = packs.resolve("retina-geodes");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina amethyst geode fixture"}}
                """);
        write(pack.resolve("data/minecraft/tags/blocks/geode_invalid_blocks.json"), """
                {"replace":false,"values":["minecraft:quartz_block"]}
                """);
        write(pack.resolve("data/retina/tags/blocks/geode_protected.json"), """
                {"replace":false,"values":["minecraft:obsidian"]}
                """);
        for (String name : List.of("dry", "wet")) {
            boolean wet = name.equals("wet");
            write(data(pack, "worldgen/configured_feature", name), configuration(wet));
            write(data(pack, "worldgen/placed_feature", name), """
                    {"feature":"retina:geode_%s","placement":[{"type":"minecraft:rarity_filter","chance":12},
                     {"type":"minecraft:random_offset","xz_spread":-1,"y_spread":0},
                     {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":24}}},
                     {"type":"minecraft:biome"}]}
                    """.formatted(name));
            write(pack.resolve("data/minecraft/worldgen/biome/" + (wet ? "badlands" : "desert") + ".json"), """
                    {"has_precipitation":false,"temperature":2.0,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],["retina:geode_%s"]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(name));
        }
        for (var probe : PROBES) {
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:%s","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[{"block":"minecraft:%s","height":128}]}}}
                    """.formatted(probe.biome, probe.substrate));
        }
    }

    private static String configuration(boolean wet) {
        return """
                {"type":"minecraft:geode","config":{
                 "blocks":{"filling_provider":%s,"inner_layer_provider":{"type":"minecraft:weighted_state_provider",
                     "entries":[{"data":{"Name":"minecraft:amethyst_block"},"weight":1},
                                {"data":{"Name":"minecraft:polished_diorite"},"weight":1}]},
                   "alternate_inner_layer_provider":%s,"middle_layer_provider":%s,"outer_layer_provider":%s,
                   "inner_placements":[{"Name":"minecraft:small_amethyst_bud"},{"Name":"minecraft:medium_amethyst_bud"},
                     {"Name":"minecraft:large_amethyst_bud"},{"Name":"minecraft:amethyst_cluster"}],
                   "cannot_replace":"#retina:geode_protected","invalid_blocks":"#minecraft:geode_invalid_blocks"},
                 "crack":{"base_crack_size":2.0,"crack_point_offset":2,"generate_crack_chance":%d},
                 "distribution_points":4,"invalid_blocks_threshold":0,
                 "layers":{"filling":1.7,"inner_layer":2.2,"middle_layer":3.2,"outer_layer":4.2},
                 "max_gen_offset":16,"min_gen_offset":-16,"noise_multiplier":0.05,
                 "outer_wall_distance":{"type":"minecraft:uniform","value":{"min_inclusive":4,"max_inclusive":6}},
                 "point_offset":{"type":"minecraft:uniform","value":{"min_inclusive":1,"max_inclusive":2}},
                 "placements_require_layer0_alternate":true,"use_alternate_layer0_chance":0.3,
                 "use_potential_placements_chance":0.6}}
                """.formatted(provider(wet ? "water" : "air"), provider("budding_amethyst"), provider("calcite"),
                provider("smooth_basalt"), wet ? 1 : 0);
    }

    private static String provider(String block) {
        return "{\"type\":\"minecraft:simple_state_provider\",\"state\":{\"Name\":\"minecraft:" + block + "\"}}";
    }

    private static Path data(Path pack, String kind, String name) {
        return pack.resolve("data/retina/" + kind + "/geode_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            require(stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject()).anyMatch(v ->
                    v.get("source").getAsString().equals("minecraft:amethyst_geode")
                            && v.get("kind").getAsString().equals("geode")), "stock placed feature");
            for (var probe : PROBES) {
                String dimension = "retina:geode_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "geode_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, Probe probe) throws Exception {
        var counts = new HashMap<Block, Integer>();
        var samples = new JsonArray();
        var sampled = new HashSet<Block>();
        var facings = new HashSet<String>();
        int waterlogged = 0, ticks = 0, seam = 0;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var cp = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(cp)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "target data version");
                    var scheduled = new HashSet<BlockPos>();
                    for (var raw : chunk.getList("fluid_ticks", Tag.TAG_COMPOUND)) {
                        var tick = (CompoundTag) raw;
                        var pos = new BlockPos(tick.getInt("x"), tick.getInt("y"), tick.getInt("z"));
                        require(new ChunkPos(pos).equals(cp) && scheduled.add(pos), "fluid tick ownership and uniqueness");
                        require(tick.getString("i").equals("minecraft:water") && tick.getInt("t") == 0,
                                "source water tick");
                        ticks++;
                    }
                    for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) raw;
                        int yBase = section.getByte("Y") * 16;
                        if (yBase < 0 || yBase >= 48 || !section.contains("block_states")) continue;
                        var states = decode(section);
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            Block block = state.getBlock();
                            boolean bud = BUDS.contains(block);
                            if (!bud && !SHELL.contains(block) && !state.is(Blocks.WATER) && !state.isAir()) continue;
                            counts.merge(block, 1, Integer::sum);
                            if (bud) {
                                facings.add(state.getValue(BlockStateProperties.FACING).getName());
                                if (state.getValue(BlockStateProperties.WATERLOGGED)) waterlogged++;
                            }
                            if (SHELL.contains(block) && (x == 0 || z == 0)) seam++;
                            if ((bud || SHELL.contains(block)) && cx >= -28 && cx <= -4 && cz >= 4 && cz <= 28
                                    && sampled.add(block)) {
                                var pos = new BlockPos(cx * 16 + x, yBase + y, cz * 16 + z);
                                samples.add(sample(definition, dimension, cp, pos, state));
                            }
                        }
                    }
                }
            }
        }
        if (probe.expected) {
            require(counts.keySet().containsAll(SHELL) && counts.keySet().containsAll(BUDS)
                    && facings.size() >= 4 && seam > 0, "layer providers, facing and seams " + counts);
            require(probe.wet ? waterlogged > 0 && ticks > 0 && counts.containsKey(Blocks.WATER)
                    : waterlogged == 0 && ticks == 0 && counts.containsKey(Blocks.AIR),
                    "crack and waterlogging " + probe.name + " " + waterlogged + "/" + ticks);
        } else {
            require(counts.keySet().stream().noneMatch(b -> SHELL.contains(b) || BUDS.contains(b)) && ticks == 0,
                    "no writes to " + probe.name + " substrate");
        }
        var report = new JsonObject();
        report.add("samples", samples);
        report.addProperty("shell", SHELL.stream().mapToInt(b -> counts.getOrDefault(b, 0)).sum());
        report.addProperty("buds", BUDS.stream().mapToInt(b -> counts.getOrDefault(b, 0)).sum());
        report.addProperty("waterlogged", waterlogged);
        report.addProperty("fluid_ticks", ticks);
        return report;
    }

    private static JsonObject sample(NativeWorldDefinition definition, String dimension, ChunkPos cp, BlockPos pos,
                                     BlockState state) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension, cp.x, cp.z)))) {
            var chunk = NbtIo.read(input);
            var section = chunk.getList("sections", Tag.TAG_COMPOUND).stream().map(raw -> (CompoundTag) raw)
                    .filter(raw -> raw.getByte("Y") == Math.floorDiv(pos.getY(), 16)).findFirst().orElseThrow();
            require(decode(section).get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15) == state, "direct chunk state");
        }
        var report = new JsonObject();
        report.addProperty("x", pos.getX());
        report.addProperty("y", pos.getY());
        report.addProperty("z", pos.getZ());
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (BUDS.contains(state.getBlock())) {
            block += "[facing=" + state.getValue(BlockStateProperties.FACING).getName()
                    + ",waterlogged=" + state.getValue(BlockStateProperties.WATERLOGGED) + "]";
        }
        report.addProperty("state", block);
        return report;
    }

    private static PalettedContainer<BlockState> decode(CompoundTag section) {
        return LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Geode verification failed: " + label);
    }
}
