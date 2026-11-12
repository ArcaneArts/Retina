package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class OverworldFossilVerification {
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private record Probe(String name, String biome, String substrate, int height, Block ore) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("upper", "desert", "stone", 128, Blocks.COAL_ORE),
            new Probe("lower", "badlands", "deepslate", 128, Blocks.DEEPSLATE_DIAMOND_ORE),
            new Probe("custom", "plains", "stone", 128, Blocks.EMERALD_ORE),
            new Probe("empty_corners", "plains", "stone", 4, null),
            new Probe("protected", "plains", "quartz_block", 128, null));

    private OverworldFossilVerification() { }

    static void pack(Path packs) throws Exception {
        Path pack = packs.resolve("retina-overworld-fossils");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina Overworld fossil fixture"}}
                """);
        write(pack.resolve("data/retina/tags/blocks/fossil_protected.json"), """
                {"replace":false,"values":["minecraft:quartz_block"]}
                """);
        write(pack.resolve("data/retina/worldgen/processor_list/fossil_base.json"), """
                {"processors":[{"processor_type":"minecraft:protected_blocks","value":"#retina:fossil_protected"}]}
                """);
        write(pack.resolve("data/retina/worldgen/processor_list/fossil_overlay.json"), """
                {"processors":[{"processor_type":"minecraft:rule","rules":[
                   {"input_predicate":{"predicate_type":"minecraft:block_match","block":"minecraft:coal_ore"},
                    "location_predicate":{"predicate_type":"minecraft:blockstate_match",
                        "block_state":{"Name":"minecraft:bone_block","Properties":{"axis":"x"}}},
                    "output_state":{"Name":"minecraft:emerald_ore"}}]},
                  {"processor_type":"minecraft:protected_blocks","value":"#retina:fossil_protected"}]}
                """);
        write(data(pack, "worldgen/configured_feature", "custom"), """
                {"type":"minecraft:fossil","config":{
                 "fossil_structures":["retina:fossil/custom_base"],"overlay_structures":["retina:fossil/custom_overlay"],
                 "fossil_processors":"retina:fossil_base","overlay_processors":"retina:fossil_overlay",
                 "max_empty_corners_allowed":0}}
                """);
        var bone = Blocks.BONE_BLOCK.defaultBlockState().setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X);
        template(pack, "custom_base", bone, List.of(
                new BlockPos(0, 0, 1), new BlockPos(1, 0, 1), new BlockPos(2, 0, 1), new BlockPos(3, 0, 1),
                new BlockPos(4, 0, 1), new BlockPos(5, 0, 1), new BlockPos(6, 0, 1), new BlockPos(6, 1, 1)));
        template(pack, "custom_overlay", Blocks.COAL_ORE.defaultBlockState(), List.of(new BlockPos(2, 0, 1)));
        for (var probe : PROBES.subList(0, 3)) {
            var feature = switch (probe.name) {
                case "upper" -> "minecraft:fossil_coal";
                case "lower" -> "minecraft:fossil_diamonds";
                default -> "retina:fossil_custom";
            };
            write(data(pack, "worldgen/placed_feature", probe.name), """
                    {"feature":"%s","placement":[{"type":"minecraft:rarity_filter","chance":7},
                     {"type":"minecraft:random_offset","xz_spread":-1,"y_spread":0},
                     {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":%d}}},
                     {"type":"minecraft:biome"}]}
                    """.formatted(feature, probe.name.equals("lower") ? -24 : 25));
            write(pack.resolve("data/minecraft/worldgen/biome/" + probe.biome + ".json"), """
                    {"has_precipitation":false,"temperature":1.5,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],["retina:fossil_%s"]],"spawners":{},"spawn_costs":{}}
                    """.formatted(probe.name));
        }
        for (var probe : PROBES) {
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:%s","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[{"block":"minecraft:%s","height":%d}]}}}
                    """.formatted(probe.biome, probe.substrate, probe.height));
        }
    }

    private static void template(Path pack, String name, BlockState state, List<BlockPos> positions) throws Exception {
        var raw = new CompoundTag();
        raw.putInt("DataVersion", 3465);
        raw.put("size", integers(7, 2, 3));
        var palette = new ListTag();
        palette.add(LegacyDensityCompiler.decode(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state)));
        raw.put("palette", palette);
        var blocks = new ListTag();
        for (var pos : positions) {
            var block = new CompoundTag();
            block.put("pos", integers(pos.getX(), pos.getY(), pos.getZ()));
            block.putInt("state", 0);
            blocks.add(block);
        }
        raw.put("blocks", blocks);
        raw.put("entities", new ListTag());
        var path = pack.resolve("data/retina/structures/fossil/" + name + ".nbt");
        Files.createDirectories(path.getParent());
        NbtIo.writeCompressed(raw, path.toFile());
    }

    private static ListTag integers(int... numbers) {
        var list = new ListTag();
        for (int n : numbers) list.add(IntTag.valueOf(n));
        return list;
    }

    private static Path data(Path pack, String folder, String name) {
        return pack.resolve("data/retina/" + folder + "/fossil_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var roots = stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject())
                    .filter(v -> v.get("kind").getAsString().equals("fossil"))
                    .map(v -> v.get("source").getAsString()).collect(Collectors.toSet());
            require(roots.containsAll(Set.of("minecraft:fossil_upper", "minecraft:fossil_lower")), "stock roots " + roots);
            for (var probe : PROBES) {
                String dimension = "retina:fossil_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "overworld_fossil_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, Probe probe) throws Exception {
        int bone = 0, ore = 0, untouchedOverlay = 0, seams = 0;
        var axes = new HashSet<String>();
        var samples = new JsonArray();
        var sampled = new HashSet<Block>();
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var cp = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(cp)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "target chunk data version");
                    require(chunk.getCompound("structures").getCompound("starts").isEmpty(), "placed feature without structure starts");
                    for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) raw;
                        int yBase = section.getByte("Y") * 16;
                        if (!section.contains("block_states")) continue;
                        var palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
                        if (palette.stream().map(v -> ((CompoundTag) v).getString("Name")).noneMatch(name ->
                                name.equals("minecraft:bone_block") || name.endsWith("_ore"))) continue;
                        var states = decode(section);
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            boolean isBone = state.is(Blocks.BONE_BLOCK);
                            boolean isOre = state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                                    || state.is(Blocks.EMERALD_ORE);
                            if (!isBone && !isOre) continue;
                            require(probe.ore != null, "unexpected template in " + probe.name);
                            int height = yBase + y;
                            require(probe.name.equals("lower") ? height >= -48 && height <= -32 : height >= 1 && height <= 22,
                                    "burial height " + height);
                            if (isBone) {
                                bone++;
                                axes.add(state.getValue(BlockStateProperties.AXIS).getName());
                                if (x == 0 || z == 0 || x == 15 || z == 15) seams++;
                            } else {
                                if (probe.name.equals("custom") && state.is(Blocks.COAL_ORE)) untouchedOverlay++;
                                else {
                                    require(state.is(probe.ore), "overlay processor output " + state);
                                    ore++;
                                }
                            }
                            if (cx >= -28 && cz >= 4 && sampled.add(state.getBlock())) {
                                var pos = new BlockPos(cx * 16 + x, height, cz * 16 + z);
                                samples.add(sample(definition, dimension, cp, pos, state));
                            }
                        }
                    }
                }
            }
        }
        if (probe.ore != null) {
            boolean custom = probe.name.equals("custom");
            require(bone > 100 && ore > 0 && seams > 0 && axes.containsAll(Set.of("x", "z"))
                            && samples.size() == (custom ? 3 : 2) && (!custom || untouchedOverlay > 0),
                    "rotated templates, overlay and chunk seam " + probe.name + " " + bone + "/" + ore + "/" + axes);
        } else {
            require(bone == 0 && ore == 0, "fossil rejection " + probe.name);
        }
        var report = new JsonObject();
        report.add("samples", samples);
        report.addProperty("bone", bone);
        report.addProperty("ore", ore);
        report.addProperty("unmatched_overlay", untouchedOverlay);
        report.addProperty("seam_blocks", seams);
        return report;
    }

    private static JsonObject sample(NativeWorldDefinition definition, String dimension, ChunkPos cp,
                                     BlockPos pos, BlockState state) throws Exception {
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
        if (state.is(Blocks.BONE_BLOCK)) block += "[axis=" + state.getValue(BlockStateProperties.AXIS).getName() + "]";
        report.addProperty("state", block);
        return report;
    }

    private static PalettedContainer<BlockState> decode(CompoundTag section) {
        return LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Overworld fossil verification failed: " + label);
    }
}
