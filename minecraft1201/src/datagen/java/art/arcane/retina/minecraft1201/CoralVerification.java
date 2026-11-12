package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

final class CoralVerification {
    private static final List<String> DIMENSIONS = List.of("tree", "claw", "mushroom", "selector", "shallow");
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private CoralVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-coral");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina 1.20.1 coral fixture"}}
                """);
        tag(pack, "coral_blocks", "tube_coral_block");
        tag(pack, "corals", "tube_coral_fan");
        tag(pack, "wall_corals", "tube_coral_wall_fan");
        for (var name : List.of("tree", "claw", "mushroom")) {
            write(data(pack, "worldgen/configured_feature", name),
                    "{\"type\":\"minecraft:coral_" + name + "\",\"config\":{}}");
        }
        for (var name : DIMENSIONS) {
            var configured = switch (name) {
                case "selector" -> "minecraft:warm_ocean_vegetation";
                case "shallow" -> "retina:coral_tree";
                default -> "retina:coral_" + name;
            };
            write(data(pack, "worldgen/placed_feature", name), """
                    {"feature":"%s","placement":[{"type":"minecraft:random_offset","xz_spread":15,"y_spread":0},
                     {"type":"minecraft:heightmap","heightmap":"OCEAN_FLOOR_WG"},{"type":"minecraft:biome"}]}
                    """.formatted(configured));
            write(data(pack, "worldgen/biome", name), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{},"features":[[],[],[],[],[],[],[],[],[],["retina:coral_%s"]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(name));
            write(data(pack, "dimension", name), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                      "biome":"retina:coral_%s","features":true,"lakes":false,"structure_overrides":[],
                      "layers":[{"block":"minecraft:stone","height":8},{"block":"minecraft:water","height":%d}]}}}
                    """.formatted(name, name.equals("shallow") ? 1 : 16));
        }
    }

    private static void tag(Path pack, String tag, String block) throws Exception {
        write(pack.resolve("data/minecraft/tags/blocks/" + tag + ".json"),
                "{\"replace\":true,\"values\":[\"minecraft:" + block + "\"]}");
    }

    private static Path data(Path pack, String folder, String id) {
        return pack.resolve("data/retina/" + folder + "/coral_" + id + ".json");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            require(stock.getAsJsonArray("decorations").asList().stream().anyMatch(row -> row.getAsJsonObject()
                    .get("source").getAsString().equals("minecraft:warm_ocean_vegetation")), "stock warm-ocean coral root");
            for (var name : DIMENSIONS) {
                var dimension = "retina:coral_" + name;
                definition.bindDimension(dimension, 2);
                var directory = Files.createDirectories(world.resolve(name));
                var path = directory.resolve("r.-1.0.mca");
                long started = System.nanoTime();
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "full coral region");
                var report = inspect(path, name);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "coral region reopen");
                report.addProperty("event", "coral_1201");
                report.addProperty("dimension", dimension);
                report.addProperty("milliseconds", (System.nanoTime() - started) / 1_000_000);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, String shape) throws Exception {
        var bodies = new HashSet<BlockPos>();
        var attachments = new LinkedHashMap<BlockPos, BlockState>();
        int anchors = 0, crossed = 0;
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
                            if (state.is(Blocks.STONE) || state.is(Blocks.WATER) || state.isAir()) continue;
                            var pos = new BlockPos(cx * 16 + x, base + y, cz * 16 + z);
                            if (state.is(Blocks.TUBE_CORAL_BLOCK)) {
                                require(base + y >= -56 && base + y < -40, "submerged coral body " + pos);
                                bodies.add(pos);
                                if (x == 15 && z == 15 && base + y == -56) anchors++;
                                if (x < 8 || z < 8) crossed++;
                            } else {
                                require(state.is(Blocks.TUBE_CORAL_FAN) || state.is(Blocks.TUBE_CORAL_WALL_FAN)
                                        || state.is(Blocks.SEA_PICKLE), "datapack coral material tags " + state);
                                require(state.getValue(BlockStateProperties.WATERLOGGED), "submerged attachment " + pos);
                                attachments.put(pos, state);
                            }
                        }
                    }
                }
            }
        }
        var facings = EnumSet.noneOf(Direction.class);
        int topFans = 0, wallFans = 0, pickles = 0;
        BlockPos sample = null;
        for (var entry : attachments.entrySet()) {
            var pos = entry.getKey();
            var state = entry.getValue();
            BlockPos support = pos.below();
            if (state.is(Blocks.TUBE_CORAL_WALL_FAN)) {
                var direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
                support = pos.relative(direction.getOpposite());
                facings.add(direction);
                wallFans++;
                if (sample == null) sample = pos;
            } else if (state.is(Blocks.TUBE_CORAL_FAN)) {
                topFans++;
            } else {
                pickles++;
            }
            if (support.getX() >= -512 && support.getX() < 0 && support.getZ() >= 0 && support.getZ() < 512) {
                require(bodies.contains(support), "coral attachment support " + pos + " " + state);
            }
        }
        if (shape.equals("shallow")) {
            require(bodies.isEmpty() && attachments.isEmpty(), "coral suppressed in one-block water");
        } else {
            require(bodies.size() > 1024 && crossed > 0 && topFans > 0 && wallFans > 0 && pickles > 0
                    && facings.size() == 4, "all reef material states and chunk-boundary placement for " + shape);
            require(switch (shape) {
                case "tree", "claw" -> anchors == 1024;
                case "mushroom" -> anchors == 0;
                default -> anchors > 0 && anchors < 1024;
            }, shape + " distinct anchor geometry " + anchors);
        }
        var report = new JsonObject();
        report.addProperty("coral_blocks", bodies.size());
        report.addProperty("top_fans", topFans);
        report.addProperty("wall_fans", wallFans);
        report.addProperty("sea_pickles", pickles);
        report.addProperty("origin_blocks", anchors);
        report.addProperty("crossed_boundary", crossed);
        if (sample != null) {
            report.addProperty("sample_x", sample.getX());
            report.addProperty("sample_y", sample.getY());
            report.addProperty("sample_z", sample.getZ());
        }
        return report;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
