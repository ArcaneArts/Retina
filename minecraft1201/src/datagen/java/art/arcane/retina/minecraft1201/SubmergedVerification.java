package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
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
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SubmergedVerification {
    private static final List<String> DIMENSIONS = List.of("kelp", "seagrass", "pickle", "magma", "clipped", "shallow");
    private static final Set<String> ROOTS = Set.of("minecraft:kelp_cold", "minecraft:kelp_warm", "minecraft:sea_pickle",
            "minecraft:seagrass_cold", "minecraft:seagrass_deep", "minecraft:seagrass_deep_cold", "minecraft:seagrass_deep_warm",
            "minecraft:seagrass_normal", "minecraft:seagrass_river", "minecraft:seagrass_swamp", "minecraft:seagrass_warm",
            "minecraft:underwater_magma");
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final int FLOOR = -56;

    private SubmergedVerification() { }

    static void pack(Path packs) throws Exception {
        var directory = packs.resolve("retina-submerged");
        write(directory.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina 1.20.1 submerged generation fixture"}}
                """);
        feature(directory, "kelp", "kelp", "{}", "");
        feature(directory, "seagrass", "seagrass", "{\"probability\":0.5}", """
                {"type":"minecraft:count","count":3},
                """);
        feature(directory, "tall", "seagrass", "{\"probability\":1}", "");
        feature(directory, "pickle", "sea_pickle", """
                {"count":{"type":"minecraft:uniform","value":{"min_inclusive":4,"max_inclusive":8}}}
                """, "");
        feature(directory, "magma", "underwater_magma", """
                {"floor_search_range":5,"placement_radius_around_floor":1,"placement_probability_per_valid_position":1}
                """, "");
        feature(directory, "clipped", "random_boolean_selector", """
                {"feature_true":{"feature":"retina:kelp","placement":[]},
                 "feature_false":{"feature":"retina:kelp","placement":[]}}
                """, "");
        for (String name : DIMENSIONS) {
            String features = name.equals("shallow") ? "\"retina:kelp\",\"retina:tall\"" : "\"retina:" + name + "\"";
            write(data(directory, "worldgen/biome", name), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],[],[],[%s]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(features));
            write(data(directory, "dimension", name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                      "biome":"retina:submerged_%s","features":true,"lakes":false,"structure_overrides":[],
                      "layers":[{"block":"minecraft:stone","height":8},{"block":"minecraft:water","height":%d}]}}}
                    """.formatted(name, switch (name) {
                        case "shallow" -> 1;
                        case "clipped" -> 2;
                        default -> 16;
                    }));
        }
    }

    private static void feature(Path directory, String name, String type, String config, String extra) throws Exception {
        write(directory.resolve("data/retina/worldgen/configured_feature/" + name + ".json"),
                "{\"type\":\"minecraft:" + type + "\",\"config\":" + config + "}");
        String height = name.equals("magma") ? """
                {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":-54}}}
                """ : """
                {"type":"minecraft:heightmap","heightmap":"OCEAN_FLOOR_WG"}
                """;
        write(directory.resolve("data/retina/worldgen/placed_feature/" + name + ".json"), """
                {"feature":"retina:%s","placement":[%s%s,{"type":"minecraft:biome"}]}
                """.formatted(name, extra, height));
    }

    private static Path data(Path pack, String folder, String id) {
        return pack.resolve("data/retina/" + folder + "/submerged_" + id + ".json");
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            var exported = new HashSet<String>();
            for (var value : stock.getAsJsonArray("decorations")) exported.add(value.getAsJsonObject().get("source").getAsString());
            require(exported.containsAll(ROOTS), "stock submerged roots " + ROOTS.stream().filter(root -> !exported.contains(root)).toList());
            for (String name : DIMENSIONS) {
                String dimension = "retina:submerged_" + name;
                definition.bindDimension(dimension, 2);
                var directory = Files.createDirectories(world.resolve(name));
                var path = directory.resolve("r.-1.0.mca");
                long began = System.nanoTime();
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "full " + dimension + " region");
                var counts = inspect(path, name);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "reopen " + dimension);
                counts.addProperty("event", "submerged_1201");
                counts.addProperty("dimension", dimension);
                counts.addProperty("milliseconds", (System.nanoTime() - began) / 1_000_000);
                System.out.println("QA_EVT " + counts);
            }
        }
    }

    private static JsonObject inspect(Path path, String name) throws Exception {
        int heads = 0, bodies = 0, shortGrass = 0, tallGrass = 0, pickles = 0, magma = 0;
        var ages = new HashSet<Integer>();
        var pickleStates = new HashSet<Integer>();
        BlockPos sample = null;
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                try (var input = region.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                    var sections = NbtIo.read(input).getList("sections", Tag.TAG_COMPOUND);
                    for (var raw : sections) {
                        var section = (CompoundTag) raw;
                        int base = section.getByte("Y") << 4;
                        if (base != -64 && base != -48 || !section.contains("block_states")) continue;
                        var blocks = LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) {
                            var state = blocks.get(x, y, z);
                            if (state.is(Blocks.KELP)) {
                                int age = state.getValue(KelpBlock.AGE);
                                require(age >= 20 && age <= 23 && (name.equals("clipped") ? base + y == FLOOR
                                        : base + y > FLOOR && base + y <= FLOOR + 10), "kelp head");
                                ages.add(age);
                                heads++;
                            } else if (state.is(Blocks.KELP_PLANT)) {
                                require(base + y >= FLOOR && base + y < FLOOR + 10, "kelp body");
                                bodies++;
                            } else if (state.is(Blocks.SEAGRASS)) {
                                require(base + y == FLOOR, "seagrass floor");
                                shortGrass++;
                            } else if (state.is(Blocks.TALL_SEAGRASS) && state.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.LOWER) {
                                var upper = blocks.get(x, y + 1, z);
                                require(base + y == FLOOR && upper.is(Blocks.TALL_SEAGRASS)
                                        && upper.getValue(TallSeagrassBlock.HALF) == DoubleBlockHalf.UPPER, "tall seagrass halves");
                                tallGrass++;
                            } else if (state.is(Blocks.SEA_PICKLE)) {
                                require(base + y == FLOOR && state.getValue(BlockStateProperties.WATERLOGGED), "waterlogged sea pickle");
                                pickleStates.add(state.getValue(SeaPickleBlock.PICKLES));
                                pickles++;
                            } else if (state.is(Blocks.MAGMA_BLOCK)) {
                                require(base + y == FLOOR - 1 || base + y == FLOOR - 2, "magma retains water above its floor");
                                magma++;
                            }
                            if (sample == null && switch (name) {
                                case "kelp" -> state.is(Blocks.KELP);
                                case "seagrass" -> state.is(Blocks.TALL_SEAGRASS);
                                case "pickle" -> state.is(Blocks.SEA_PICKLE);
                                case "magma" -> state.is(Blocks.MAGMA_BLOCK);
                                default -> false;
                            }) sample = new BlockPos(cx * 16 + x, base + y, cz * 16 + z);
                        }
                    }
                }
            }
        }
        require(switch (name) {
            case "kelp" -> heads == 1024 && bodies > heads && ages.size() == 4;
            case "seagrass" -> shortGrass > 0 && tallGrass > 0;
            case "pickle" -> pickles > 1024 && pickleStates.size() == 4;
            case "magma" -> magma == 18_432;
            case "clipped" -> heads == 1024 && bodies == 0 && ages.size() == 4;
            default -> heads == 0 && bodies == 0 && shortGrass == 0 && tallGrass == 0;
        }, name + " material counts: " + List.of(heads, bodies, shortGrass, tallGrass, pickles, magma));
        var result = new JsonObject();
        result.addProperty("kelp_heads", heads);
        result.addProperty("kelp_bodies", bodies);
        result.addProperty("seagrass", shortGrass);
        result.addProperty("tall_seagrass", tallGrass);
        result.addProperty("sea_pickles", pickles);
        result.addProperty("magma", magma);
        if (sample != null) {
            result.addProperty("sample_x", sample.getX());
            result.addProperty("sample_y", sample.getY());
            result.addProperty("sample_z", sample.getZ());
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
