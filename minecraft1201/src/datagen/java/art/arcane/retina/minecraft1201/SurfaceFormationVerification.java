package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Exercises the effective configuration, placement and saved chunk states of the surface recipes. */
final class SurfaceFormationVerification {
    private static final BlockState ROCK = Blocks.EMERALD_BLOCK.defaultBlockState();
    private static final BlockState PACKED = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState BLUE = Blocks.BLUE_ICE.defaultBlockState();
    private static final String HEIGHTMAP = "{\"type\":\"minecraft:heightmap\",\"heightmap\":\"WORLD_SURFACE_WG\"}";
    private static final String WATER_HEIGHT = """
            {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":-64}}}
            """;
    private static final String WATER_LAYER = "{\"block\":\"minecraft:water\",\"height\":1}";
    private static final List<Probe> PROBES = List.of(
            new Probe("rock", "retina:formation_rock", layers("quartz_block", 8), HEIGHTMAP, ROCK, 0, -57, true),
            new Probe("rock_no_ground", "retina:formation_rock", layers("bricks", 8), HEIGHTMAP, ROCK, 0, -57, false),
            new Probe("spike", "minecraft:ice_spike", layers("stone", 109) + "," + layers("snow_block", 18),
                    HEIGHTMAP, PACKED, 15, 62, true),
            new Probe("blue_ice", "minecraft:blue_ice", WATER_LAYER + "," + layers("packed_ice", 1), WATER_HEIGHT, BLUE, 15, -64, true),
            new Probe("blue_no_ice", "minecraft:blue_ice", WATER_LAYER + "," + layers("stone", 1), WATER_HEIGHT, BLUE, 15, -64, false),
            new Probe("iceberg_packed", "minecraft:iceberg_packed", WATER_LAYER, HEIGHTMAP, PACKED, 15, -63, true),
            new Probe("iceberg_blue", "minecraft:iceberg_blue", WATER_LAYER, HEIGHTMAP, BLUE, 15, -63, true));
    private static final Set<String> STOCK = Set.of("forest_rock", "ice_spike", "blue_ice", "iceberg_blue", "iceberg_packed");
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private record Probe(String name, String configured, String layers, String height, BlockState target,
                         int offset, int surface, boolean expected) { }

    private SurfaceFormationVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-formations");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"Retina surface formations\"}}");
        write(pack.resolve("data/minecraft/tags/blocks/dirt.json"),
                "{\"replace\":false,\"values\":[\"minecraft:quartz_block\"]}");
        write(data(pack, "worldgen/configured_feature", "rock"),
                "{\"type\":\"minecraft:forest_rock\",\"config\":{\"state\":{\"Name\":\"minecraft:emerald_block\"}}}");
        for (var probe : PROBES) {
            String rarity = probe.name.startsWith("iceberg") ? "{\"type\":\"minecraft:rarity_filter\",\"chance\":7}," : "";
            write(data(pack, "worldgen/placed_feature", probe.name), """
                    {"feature":"%s","placement":[%s{"type":"minecraft:random_offset","xz_spread":%d,"y_spread":0},
                     %s,{"type":"minecraft:biome"}]}
                    """.formatted(probe.configured, rarity, probe.offset, probe.height));
            write(data(pack, "worldgen/biome", probe.name), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],[],[],["retina:formation_%s"]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(probe.name));
            write(data(pack, "dimension", probe.name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                      "biome":"retina:formation_%s","features":true,"lakes":false,"structure_overrides":[],
                      "layers":[%s]}}}
                    """.formatted(probe.name, probe.layers));
        }
    }

    private static String layers(String block, int height) {
        return "{\"block\":\"minecraft:" + block + "\",\"height\":" + height + "}";
    }

    private static Path data(Path pack, String folder, String name) {
        return pack.resolve("data/retina/" + folder + "/formation_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var sources = stock.getAsJsonArray("decorations").asList().stream().map(row -> row.getAsJsonObject())
                    .filter(row -> row.get("kind").getAsString().equals("surface_formation"))
                    .map(row -> row.get("source").getAsString()).collect(Collectors.toSet());
            require(sources.containsAll(STOCK.stream().map(id -> "minecraft:" + id).toList()), "stock surface formations " + sources);
            for (var probe : PROBES) {
                var dimension = "retina:formation_" + probe.name;
                definition.bindDimension(dimension, 2);
                var path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                long started = System.nanoTime();
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "complete " + probe.name);
                var report = inspect(path, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + probe.name);
                report.addProperty("event", "surface_formation_1201");
                report.addProperty("dimension", dimension);
                report.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, Probe probe) throws Exception {
        int count = 0, crossed = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, snow = 0;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                try (var input = region.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                    var sections = NbtIo.read(input).getList("sections", Tag.TAG_COMPOUND);
                    for (var raw : sections) {
                        var section = (CompoundTag) raw;
                        int base = section.getByte("Y") * 16;
                        if (base + 15 < probe.surface - 20 || base > probe.surface + 55 || !section.contains("block_states")) continue;
                        var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            if (state.is(Blocks.SNOW_BLOCK) && base + y > probe.surface) snow++;
                            if (state != probe.target) continue;
                            count++;
                            min = Math.min(min, base + y);
                            max = Math.max(max, base + y);
                            if (probe.offset == 0 ? x >= 13 || z >= 13 : x <= 2 || z <= 2) crossed++;
                        }
                    }
                }
            }
        }
        if (probe.expected) {
            require(count > 1024 && crossed > 0 && max > probe.surface, "material and footprint " + probe.name);
            if (probe.name.startsWith("iceberg")) {
                require(snow > 0 && min < probe.surface && max >= probe.surface + 3, "iceberg above and below sea level " + probe.name);
            }
            if (probe.name.equals("spike")) require(min <= 51 && max >= 80, "spike roots and tall variant");
        } else {
            require(count == 0, "no formation without matching ground or packed ice " + probe.name);
        }
        var report = new JsonObject();
        report.addProperty("blocks", count);
        report.addProperty("crossed_boundary", crossed);
        report.addProperty("snow_caps", snow);
        if (probe.expected) {
            report.addProperty("min_y", min);
            report.addProperty("max_y", max);
        }
        return report;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
