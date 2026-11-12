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

final class RootedAzaleaVerification {
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Set<Block> OUTPUT = Set.of(Blocks.ROOTED_DIRT, Blocks.HANGING_ROOTS, Blocks.OAK_LOG,
            Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES);

    private record Probe(String name, String substrate, int cavity, boolean grows) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("open", "stone", 24, true), new Probe("low_roof", "stone", 4, false),
            new Probe("substrate", "quartz_block", 24, false));

    private RootedAzaleaVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-rooted-azaleas");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina rooted azalea fixture"}}
                """);
        write(pack.resolve("data/minecraft/tags/blocks/azalea_grows_on.json"), """
                {"replace":false,"values":["minecraft:stone"]}
                """);
        write(data(pack, "worldgen/configured_feature", "opening"), """
                {"type":"minecraft:block_column","config":{"direction":"up","allowed_placement":{"type":"minecraft:true"},
                 "prioritize_tip":false,"layers":[{"height":2,"provider":{"type":"minecraft:simple_state_provider",
                 "state":{"Name":"minecraft:air"}}}]}}
                """);
        write(data(pack, "worldgen/configured_feature", "tree"), """
                {"type":"minecraft:root_system","config":{
                 "feature":{"feature":"minecraft:azalea_tree","placement":[]},
                 "allowed_tree_position":{"type":"minecraft:all_of","predicates":[
                   {"type":"minecraft:matching_blocks","blocks":"minecraft:air"},
                   {"type":"minecraft:matching_block_tag","offset":[0,-1,0],"tag":"minecraft:azalea_grows_on"}]},
                 "root_column_max_height":16,"required_vertical_space_for_tree":3,"allowed_vertical_water_for_tree":2,
                 "root_radius":3,"root_placement_attempts":20,"root_replaceable":"#minecraft:azalea_root_replaceable",
                 "root_state_provider":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:rooted_dirt"}},
                 "hanging_root_radius":3,"hanging_roots_vertical_span":2,"hanging_root_placement_attempts":64,
                 "hanging_root_state_provider":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:hanging_roots"}}}}
                """);
        String origin = """
                {"type":"minecraft:random_offset","xz_spread":-1,"y_spread":0},
                {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":54}}}
                """;
        write(data(pack, "worldgen/placed_feature", "opening"), """
                {"feature":"retina:azalea_opening","placement":[%s,{"type":"minecraft:biome"}]}
                """.formatted(origin));
        write(data(pack, "worldgen/placed_feature", "tree"), """
                {"feature":"retina:azalea_tree","placement":[{"type":"minecraft:rarity_filter","chance":3},%s,
                  {"type":"minecraft:environment_scan","direction_of_search":"up","max_steps":12,
                   "target_condition":{"type":"minecraft:solid"},
                   "allowed_search_condition":{"type":"minecraft:matching_blocks","blocks":"minecraft:air"}},
                  {"type":"minecraft:random_offset","xz_spread":0,"y_spread":-1},{"type":"minecraft:biome"}]}
                """.formatted(origin));
        write(pack.resolve("data/minecraft/worldgen/biome/plains.json"), """
                {"has_precipitation":false,"temperature":0.8,"downfall":0,
                 "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                 "carvers":{},"features":[[],[],[],[],[],[],[],[],[],["retina:azalea_opening","retina:azalea_tree"]],
                 "spawners":{},"spawn_costs":{}}
                """);
        for (var probe : PROBES) {
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:plains","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[{"block":"minecraft:%s","height":128},{"block":"minecraft:air","height":%d},
                               {"block":"minecraft:bedrock","height":8}]}}}
                    """.formatted(probe.substrate, probe.cavity));
        }
    }

    private static Path data(Path pack, String folder, String name) {
        return pack.resolve("data/retina/" + folder + "/azalea_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            require(stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject()).anyMatch(v ->
                    v.get("source").getAsString().equals("minecraft:rooted_azalea_tree")
                            && v.get("kind").getAsString().equals("root_system")), "stock placed feature");
            for (var probe : PROBES) {
                String dimension = "retina:azalea_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "rooted_azalea_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, Probe probe) throws Exception {
        var counts = new HashMap<Block, Integer>();
        var samples = new JsonArray();
        var sampled = new HashSet<Block>();
        int belowSoil = 0, seamLeaves = 0, sealedColumns = 0;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var cp = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(cp)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "target data version");
                    if (state(chunk, 8, 64 + probe.cavity, 8).is(Blocks.BEDROCK)) sealedColumns++;
                    for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) raw;
                        int yBase = section.getByte("Y") * 16;
                        if (yBase + 15 < 50 || yBase >= 88 || !section.contains("block_states")) continue;
                        var states = decode(section);
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            var block = state.getBlock();
                            if (!OUTPUT.contains(block)) continue;
                            int at = yBase + y;
                            counts.merge(block, 1, Integer::sum);
                            if (state.is(Blocks.ROOTED_DIRT)) {
                                require(at >= 55 && at <= 63, "root column height");
                                if (at < 63) belowSoil++;
                            } else if (state.is(Blocks.HANGING_ROOTS)) {
                                var support = state(chunk, x, at + 1, z);
                                require(at == 55 && (support.is(Blocks.ROOTED_DIRT) || support.is(Blocks.STONE)),
                                        "hanging roots retain solid support");
                            } else {
                                require(at >= 64 && at < 88, "tree grew under the cave ceiling");
                                if (state.hasProperty(BlockStateProperties.DISTANCE)) {
                                    require(state.getValue(BlockStateProperties.DISTANCE) <= 6, "leaf connectivity");
                                    if (x == 0 || z == 0) seamLeaves++;
                                }
                            }
                            if (cx >= -28 && cz >= 4 && sampled.add(block)) {
                                var pos = new BlockPos(cx * 16 + x, at, cz * 16 + z);
                                samples.add(sample(definition, dimension, cp, pos, state));
                            }
                        }
                    }
                }
            }
        }
        require(sealedColumns == 1024, "cave roof stayed in place");
        if (probe.grows) {
            require(counts.keySet().containsAll(OUTPUT) && belowSoil > 0 && seamLeaves > 0 && samples.size() == 5,
                    "roots and tree across seams " + counts);
        } else {
            require(counts.isEmpty(), "rejected tree emitted roots " + probe.name);
        }
        var report = new JsonObject();
        report.add("samples", samples);
        report.addProperty("rooted_dirt", counts.getOrDefault(Blocks.ROOTED_DIRT, 0));
        report.addProperty("hanging_roots", counts.getOrDefault(Blocks.HANGING_ROOTS, 0));
        report.addProperty("logs", counts.getOrDefault(Blocks.OAK_LOG, 0));
        report.addProperty("seam_leaves", seamLeaves);
        return report;
    }

    private static JsonObject sample(NativeWorldDefinition definition, String dimension, ChunkPos cp,
                                     BlockPos pos, BlockState expected) throws Exception {
        try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension, cp.x, cp.z)))) {
            require(state(NbtIo.read(input), pos.getX() & 15, pos.getY(), pos.getZ() & 15) == expected, "direct chunk state");
        }
        var sample = new JsonObject();
        sample.addProperty("x", pos.getX());
        sample.addProperty("y", pos.getY());
        sample.addProperty("z", pos.getZ());
        String block = BuiltInRegistries.BLOCK.getKey(expected.getBlock()).toString();
        if (expected.hasProperty(BlockStateProperties.DISTANCE)) {
            block += "[distance=" + expected.getValue(BlockStateProperties.DISTANCE) + ",persistent=false]";
        }
        sample.addProperty("state", block);
        return sample;
    }

    private static BlockState state(CompoundTag chunk, int x, int y, int z) {
        for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) raw;
            if (section.getByte("Y") == Math.floorDiv(y, 16) && section.contains("block_states")) {
                return decode(section).get(x, y & 15, z);
            }
        }
        return Blocks.AIR.defaultBlockState();
    }

    private static PalettedContainer<BlockState> decode(CompoundTag section) {
        return LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Rooted azalea verification failed: " + label);
    }
}
