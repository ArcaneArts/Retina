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
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

final class DesertWellVerification {
    private static final String LOOT = "minecraft:archaeology/desert_well";
    private static final int SURFACE_Y = 51;
    private static final BlockPos CENTER = new BlockPos(-17, SURFACE_Y, 31);
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES =
            PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private record Probe(String name, String support, String surface) { }

    private static final List<Probe> PROBES = List.of(
            new Probe("sand", "stone", "sand"), new Probe("quartz", "stone", "quartz_block"),
            new Probe("floating", "air", "sand"));

    private DesertWellVerification() { }

    static void pack(Path packs) throws Exception {
        var pack = packs.resolve("retina-desert-wells");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina desert well fixture"}}
                """);
        write(pack.resolve("data/retina/worldgen/placed_feature/well.json"), """
                {"feature":"minecraft:desert_well","placement":[
                 {"type":"minecraft:random_offset","xz_spread":15,"y_spread":0},
                 {"type":"minecraft:heightmap","heightmap":"MOTION_BLOCKING"},{"type":"minecraft:biome"}]}
                """);
        write(pack.resolve("data/minecraft/worldgen/biome/desert.json"), """
                {"has_precipitation":false,"temperature":2.0,"downfall":0,
                 "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                  "carvers":{},"features":[[],[],[],[],["minecraft:desert_well"],[],[],[],[],["retina:well"]],
                 "spawners":{},"spawn_costs":{}}
                """);
        for (var probe : PROBES) {
            write(pack.resolve("data/retina/dimension/well_" + probe.name + ".json"), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                     "biome":"minecraft:desert","features":true,"lakes":false,"structure_overrides":[],
                     "layers":[{"block":"minecraft:%s","height":115},{"block":"minecraft:%s","height":1}]}}}
                    """.formatted(probe.support, probe.surface));
        }
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    static void verify(Path world) throws Exception {
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = JsonParser.parseString(definition.diagnosticProfile("minecraft:overworld")).getAsJsonObject();
            require(stock.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject()).anyMatch(v ->
                    v.get("source").getAsString().equals("minecraft:desert_well")
                            && v.getAsJsonObject("formation").get("shape").getAsString().equals("desert_well")), "stock placement");
            for (var probe : PROBES) {
                String dimension = "retina:well_" + probe.name;
                definition.bindDimension(dimension, 2);
                Path path = Files.createDirectories(world.resolve(probe.name)).resolve("r.-1.0.mca");
                require(definition.generateRegion(dimension, -1, 0, path).generated() == 1024, "region " + probe.name);
                var report = inspect(path, definition, dimension, probe);
                require(definition.generateRegion(dimension, -1, 0, path).preserved() == 1024, "region reopen " + probe.name);
                report.addProperty("event", "desert_well_1201");
                report.addProperty("dimension", dimension);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static JsonObject inspect(Path path, NativeWorldDefinition definition, String dimension, Probe probe) throws Exception {
        var report = new JsonObject();
        Map<ChunkPos, CompoundTag> near = new HashMap<>();
        var sample = new JsonArray();
        Set<BlockPos> positions = new HashSet<>();
        try (var region = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                var chunkPos = new ChunkPos(cx, cz);
                try (var input = region.getChunkDataInputStream(chunkPos)) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "chunk version");
                    if (cx >= -2 && cz >= 1 && cz <= 2) near.put(chunkPos, chunk);
                    for (var raw : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
                        var tag = (CompoundTag) raw;
                        var pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                        require(new ChunkPos(pos).equals(chunkPos) && positions.add(pos), "unique owning chunk for " + pos);
                        require(state(chunk, pos).is(Blocks.SUSPICIOUS_SAND), "brushable block at " + pos);
                        var entity = BlockEntity.loadStatic(pos, Blocks.SUSPICIOUS_SAND.defaultBlockState(), tag);
                        require(entity instanceof BrushableBlockEntity, "1.20.1 brushable reader");
                        var saved = entity.saveWithFullMetadata();
                        require(saved.getString("LootTable").equals(LOOT) && saved.getLong("LootTableSeed") == pos.asLong(),
                                "archaeology table and position seed at " + pos);
                        if (Math.abs(pos.getX() - CENTER.getX()) + Math.abs(pos.getZ() - CENTER.getZ()) <= 1) {
                            var p = new JsonObject();
                            p.addProperty("x", pos.getX());
                            p.addProperty("y", pos.getY());
                            p.addProperty("z", pos.getZ());
                            p.addProperty("seed", pos.asLong());
                            sample.add(p);
                        }
                    }
                }
            }
        }
        boolean positive = probe.name.equals("sand");
        require(positive ? positions.size() > 1900 && sample.size() == 2 : positions.isEmpty(), "archaeology count " + probe.name + " " + positions.size() + " sample " + sample.size());
        var roof = CENTER.above(4);
        if (positive) {
            require(state(near.get(new ChunkPos(CENTER)), CENTER).is(Blocks.WATER), "pool center");
            require(state(near.get(new ChunkPos(roof)), roof).is(Blocks.SANDSTONE), "roof center");
            var neighbor = roof.offset(1, 0, 1);
            require(state(near.get(new ChunkPos(neighbor)), neighbor).is(Blocks.SANDSTONE_SLAB), "roof across two chunk seams");
            var post = CENTER.offset(1, 2, 1);
            require(state(near.get(new ChunkPos(post)), post).is(Blocks.SANDSTONE), "column across two chunk seams");
            for (var entry : sample) {
                var p = entry.getAsJsonObject();
                var cp = new ChunkPos(new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt()));
                try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(dimension, cp.x, cp.z)))) {
                    require(NbtIo.read(input).getList("block_entities", Tag.TAG_COMPOUND)
                            .equals(near.get(cp).getList("block_entities", Tag.TAG_COMPOUND)), "direct chunk and region archaeology");
                }
            }
        } else {
            require(state(near.get(new ChunkPos(roof)), roof).isAir(), "no roof in " + probe.name);
        }
        report.add("sample", sample);
        report.addProperty("brushable_blocks", positions.size());
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

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("Desert well verification failed: " + description);
    }
}
