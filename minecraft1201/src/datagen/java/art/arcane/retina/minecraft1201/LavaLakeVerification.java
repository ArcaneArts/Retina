package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

final class LavaLakeVerification {
    private static final List<String> DIMENSIONS = List.of("surface", "underground", "wet", "protected");
    private static final int STORAGE_MIN_Y = -64;
    private static final String FLOOR = """
            {"type":"minecraft:y_clamped_gradient","from_y":14,"to_y":16,"from_value":1,"to_value":-1}
            """;
    private static final String ROOF = """
            {"type":"minecraft:min",
             "argument1":{"type":"minecraft:y_clamped_gradient","from_y":30,"to_y":36,"from_value":-1,"to_value":1},
             "argument2":{"type":"minecraft:y_clamped_gradient","from_y":50,"to_y":56,"from_value":1,"to_value":-1}}
            """;
    private static final String DENSITY = "{\"type\":\"minecraft:max\",\"argument1\":" + FLOOR + ",\"argument2\":" + ROOF + "}";
    private static final String SURFACE = "{\"type\":\"minecraft:heightmap\",\"heightmap\":\"WORLD_SURFACE_WG\"}";
    private static final String UNDERGROUND = """
            {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":26}}},
            {"type":"minecraft:environment_scan","direction_of_search":"down","max_steps":24,
             "target_condition":{"type":"minecraft:not","predicate":{"type":"minecraft:matching_blocks","blocks":"minecraft:air"}}},
            {"type":"minecraft:surface_relative_threshold_filter","heightmap":"OCEAN_FLOOR_WG","max_inclusive":-5}
            """;
    private static final String WET = """
            {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":15}}}
            """;
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private LavaLakeVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-lava-lakes");
        write(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"Retina lava lake fixture\"}}");
        for (var tag : List.of("features_cannot_replace", "lava_pool_stone_cannot_replace")) {
            write(pack.resolve("data/minecraft/tags/blocks/" + tag + ".json"),
                    "{\"replace\":false,\"values\":[\"minecraft:quartz_block\"]}");
        }
        write(data(pack, "worldgen/configured_feature", "basin"), """
                {"type":"minecraft:lake","config":{
                  "fluid":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:lava"}},
                  "barrier":{"type":"minecraft:simple_state_provider","state":{"Name":"minecraft:obsidian"}}}}
                """);
        write(data(pack, "worldgen/configured_feature", "selector"), """
                {"type":"minecraft:simple_random_selector",
                 "config":{"features":[{"feature":"retina:lake_basin","placement":[]}]}}
                """);
        write(data(pack, "worldgen/configured_carver", "substrate"), """
                {"type":"minecraft:cave","config":{"probability":0,
                  "y":{"type":"minecraft:constant","value":{"absolute":12}},"yScale":1,
                  "lava_level":{"absolute":-54},"replaceable":["minecraft:stone","minecraft:quartz_block"],
                  "horizontal_radius_multiplier":1,"vertical_radius_multiplier":1,"floor_level":-1}}
                """);
        for (var name : DIMENSIONS) {
            var placement = switch (name) {
                case "surface" -> SURFACE;
                case "wet" -> WET;
                default -> UNDERGROUND;
            };
            write(data(pack, "worldgen/placed_feature", name), """
                    {"feature":"retina:lake_%s","placement":[{"type":"minecraft:rarity_filter","chance":3},
                     {"type":"minecraft:random_offset","xz_spread":15,"y_spread":0},%s,{"type":"minecraft:biome"}]}
                    """.formatted(name.equals("underground") ? "selector" : "basin", placement));
            write(data(pack, "worldgen/biome", name), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{"air":["retina:lake_substrate"]},"features":[[],["retina:lake_%s"]],"spawners":{},"spawn_costs":{}}
                    """.formatted(name));
            write(data(pack, "dimension", name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:noise","settings":"retina:lake_%s",
                      "biome_source":{"type":"minecraft:fixed","biome":"retina:lake_%s"}}}
                    """.formatted(name, name));
            write(data(pack, "worldgen/noise_settings", name), """
                    {"aquifers_enabled":false,"default_block":{"Name":"minecraft:%s"},
                     "default_fluid":{"Name":"minecraft:water"},"disable_mob_generation":false,"legacy_random_source":false,
                     "noise":{"height":128,"min_y":0,"size_horizontal":1,"size_vertical":1},
                     "noise_router":{"barrier":0,"continents":0,"depth":0,"erosion":0,"fluid_level_floodedness":0,
                       "fluid_level_spread":0,"lava":0,"ridges":0,"temperature":0,"vegetation":0,
                       "vein_gap":0,"vein_ridged":0,"vein_toggle":0,"final_density":%s,"initial_density_without_jaggedness":%s},
                     "ore_veins_enabled":false,"sea_level":%d,"spawn_target":[],
                     "surface_rule":{"type":"minecraft:sequence","sequence":[]}}
                    """.formatted(name.equals("protected") ? "quartz_block" : "stone",
                    DENSITY, DENSITY, name.equals("wet") ? 28 : 0));
        }
    }

    private static Path data(Path pack, String folder, String name) {
        return pack.resolve("data/retina/" + folder + "/lake_" + name + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var roots = stock.getAsJsonArray("decorations").asList().stream().map(item -> item.getAsJsonObject())
                    .filter(item -> item.get("kind").getAsString().equals("flat_lake"))
                    .collect(Collectors.toMap(item -> item.get("source").getAsString(), item -> item));
            require(roots.containsKey("minecraft:lake_lava_surface") && roots.containsKey("minecraft:lake_lava_underground"),
                    "both stock lava lake placements");
            for (var name : DIMENSIONS) {
                var dimension = "retina:lake_" + name;
                definition.bindDimension(dimension, 2);
                var path = Files.createDirectories(world.resolve(name)).resolve("r.-1.0.mca");
                long started = System.nanoTime();
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "full " + name);
                var report = inspect(path, name, definition, dimension);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + name);
                report.addProperty("event", "lava_lake_1201");
                report.addProperty("dimension", dimension);
                report.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, String name, NativeWorldDefinition definition, String dimension) throws Exception {
        int lava = 0, barrier = 0, ticks = 0, marks = 0, crossed = 0;
        BlockPos sample = null;
        CompoundTag sampleChunk = null;
        int floor = name.equals("surface") ? 45 : 8;
        int ceiling = name.equals("surface") ? 62 : 22;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                try (var input = region.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465 && chunk.getInt("yPos") == -4, "1.20.1 padded storage");
                    var air = new HashSet<BlockPos>();
                    for (var raw : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        var section = (CompoundTag) raw;
                        int base = section.getByte("Y") * 16;
                        if (base < 0 || base >= 64 || !section.contains("block_states")) continue;
                        var states = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            var state = states.get(x, y, z);
                            if (state.is(Blocks.LAVA)) {
                                require(base + y >= floor && base + y <= ceiling, "lava altitude " + (base + y));
                                lava++;
                                if (x < 15 || z < 15) crossed++;
                                if (sample == null && cx >= -28 && cz >= 4) {
                                    sample = new BlockPos(cx * 16 + x, base + y, cz * 16 + z);
                                    sampleChunk = chunk;
                                }
                            } else if (state.is(Blocks.OBSIDIAN)) {
                                barrier++;
                            } else if (state.is(Blocks.CAVE_AIR)) {
                                air.add(new BlockPos(cx * 16 + x, base + y, cz * 16 + z));
                            }
                        }
                    }
                    for (var raw : chunk.getList("block_ticks", Tag.TAG_COMPOUND)) {
                        var tick = (CompoundTag) raw;
                        var pos = new BlockPos(tick.getInt("x"), tick.getInt("y"), tick.getInt("z"));
                        require(tick.getString("i").equals("minecraft:cave_air") && tick.getInt("t") == 0
                                && tick.getInt("p") == 0 && air.contains(pos), "cave-air update " + tick);
                        ticks++;
                    }
                    var processing = chunk.getList("PostProcessing", Tag.TAG_LIST);
                    for (int section = 0; section < processing.size(); section++) {
                        var entries = (ListTag) processing.get(section);
                        var seen = new HashSet<Short>();
                        for (var raw : entries) {
                            short packed = ((ShortTag) raw).getAsShort();
                            int y = STORAGE_MIN_Y + section * 16 + ((packed >> 4) & 15);
                            require(y >= floor && y <= ceiling && seen.add(packed), "padded post-processing coordinate " + y);
                            marks++;
                        }
                    }
                }
            }
        }
        var report = new JsonObject();
        if (name.equals("surface") || name.equals("underground")) {
            require(lava > 0 && barrier > 0 && ticks > 0 && marks > 0 && crossed > 0 && sample != null,
                    "cavity, barrier and generation updates for " + name + " " + lava + "/" + barrier + "/" + ticks + "/" + marks);
            try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension,
                    Math.floorDiv(sample.getX(), 16), Math.floorDiv(sample.getZ(), 16))))) {
                var direct = NbtIo.read(input);
                for (var key : List.of("block_ticks", "PostProcessing")) {
                    require(sampleChunk.get(key).equals(direct.get(key)), "direct chunk and MCA " + key);
                }
            }
            report.addProperty("sample_x", sample.getX());
            report.addProperty("sample_y", sample.getY());
            report.addProperty("sample_z", sample.getZ());
        } else {
            require(lava == 0 && barrier == 0, "water or protected rock rejects lake writes for " + name);
            if (name.equals("wet")) require(ticks == 0 && marks == 0, "water rejects all lake updates");
        }
        report.addProperty("lava", lava);
        report.addProperty("barrier", barrier);
        report.addProperty("cave_air_ticks", ticks);
        report.addProperty("post_processing", marks);
        report.addProperty("cross_chunk_lava", crossed);
        return report;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
