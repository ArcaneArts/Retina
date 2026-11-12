package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.levelgen.GenerationStep;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

final class CarvingMaskVerification {
    private CarvingMaskVerification() { }

    static void pack(Path directory) throws Exception {
        var pack = directory.resolve("retina-carving-masks");
        write(pack.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina carving stage fixture"}}
                """);
        for (boolean liquid : List.of(false, true)) {
            var name = dimension(liquid);
            var data = pack.resolve("data/retina");
            write(data.resolve("worldgen/biome/" + name + ".json"), """
                    {"has_precipitation":false,"temperature":0.8,"downfall":0,
                     "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                     "carvers":{"air":["minecraft:cave"]%s},
                     "features":[[],[],[],[],[],[],[],[],[],
                       ["minecraft:seagrass_simple","retina:air_mask","retina:liquid_mask","retina:ordinary"]],
                     "spawners":{},"spawn_costs":{}}
                    """.formatted(liquid ? ",\"liquid\":[\"minecraft:cave\"]" : ""));
            write(data.resolve("dimension/" + name + ".json"), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:noise","settings":"minecraft:overworld",
                     "biome_source":{"type":"minecraft:fixed","biome":"retina:%s"}}}
                    """.formatted(name));
        }
        write(pack.resolve("data/retina/worldgen/configured_feature/mask_marker.json"), marker("diamond_block"));
        write(pack.resolve("data/retina/worldgen/configured_feature/ordinary.json"), marker("emerald_block"));
        for (String stage : List.of("air", "liquid")) {
            write(pack.resolve("data/retina/worldgen/placed_feature/" + stage + "_mask.json"), """
                    {"feature":"retina:mask_marker","placement":[{"type":"minecraft:carving_mask","step":"%s"}]}
                    """.formatted(stage));
        }
        write(pack.resolve("data/retina/worldgen/placed_feature/ordinary.json"), """
                {"feature":"retina:ordinary","placement":[
                 {"type":"minecraft:height_range","height":{"type":"minecraft:constant","value":{"absolute":200}}}]}
                """);
    }

    static void verify(Path world, RegistryExport.Loaded loaded) throws Exception {
        var chunkPos = new ChunkPos(-3, 4);
        var vanillaEmptyMask = new CarvingMask(384, -64);
        require(vanillaEmptyMask.stream(chunkPos).findAny().isEmpty(), "Minecraft's empty mask emits zero positions");
        vanillaEmptyMask.set(0, -10, 0);
        require(vanillaEmptyMask.stream(chunkPos).count() == 1, "Minecraft mask streams actual entries");
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            var stock = profile(definition, "minecraft:overworld");
            require(emptyCount(stock, "minecraft:seagrass_simple"), "stock cave seagrass has an empty liquid mask");
            for (boolean liquid : List.of(false, true)) {
                var name = "retina:" + dimension(liquid);
                var dimension = loaded.dimensions().entrySet().stream()
                        .filter(e -> e.getKey().location().toString().equals(name)).findFirst().orElseThrow().getValue();
                var biomes = dimension.generator().getBiomeSource().possibleBiomes();
                require(biomes.size() == 1, "fixed biome source");
                var settings = biomes.iterator().next().value().getGenerationSettings();
                require(settings.getCarvers(GenerationStep.Carving.AIR).iterator().hasNext(), "loaded air carvers");
                require(settings.getCarvers(GenerationStep.Carving.LIQUID).iterator().hasNext() == liquid,
                        "loaded liquid stage");

                var profile = profile(definition, name);
                var omissions = profile.getAsJsonArray("decoration_omissions").asList().stream()
                        .map(v -> v.getAsString()).toList();
                require(omissions.contains("retina:air_mask:placement:carving_mask:air"), "active air mask remains explicit");
                for (String root : List.of("minecraft:seagrass_simple", "retina:liquid_mask")) {
                    require(emptyCount(profile, root) == !liquid, root + " uses the effective carving stage");
                    require(omissions.contains(root + ":placement:carving_mask:liquid") == liquid,
                            root + " reports a nonempty stage");
                }
                if (liquid) require(profile.getAsJsonArray("geology_omissions").asList().stream()
                        .anyMatch(v -> v.getAsString().equals("minecraft:cave:liquid-carver")), "active liquid carver report");

                definition.bindDimension(name, 2);
                var blocks = new HashSet<String>();
                try (var input = new DataInputStream(new ByteArrayInputStream(definition.chunk(name, chunkPos.x, chunkPos.z)))) {
                    var chunk = NbtIo.read(input);
                    require(chunk.getInt("DataVersion") == 3465, "1.20.1 chunk data version");
                    for (var section : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                        for (var state : ((CompoundTag) section).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)) {
                            blocks.add(((CompoundTag) state).getString("Name"));
                        }
                    }
                }
                require(blocks.contains("minecraft:emerald_block"), "ordinary root emits its marker");
                require(!blocks.contains("minecraft:diamond_block") && !blocks.contains("minecraft:seagrass"),
                        "carving mask emits no phantom placements");
                var report = new JsonObject();
                report.addProperty("event", "carving_mask_1201");
                report.addProperty("status", "pass");
                report.addProperty("dimension", name);
                report.addProperty("liquid_carvers", liquid);
                report.addProperty("materials", blocks.size());
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static String dimension(boolean liquid) {
        return liquid ? "active_liquid" : "empty_liquid";
    }

    private static String marker(String block) {
        return """
                {"type":"minecraft:simple_block","config":{"to_place":{"type":"minecraft:simple_state_provider",
                 "state":{"Name":"minecraft:%s"}}}}
                """.formatted(block);
    }

    private static JsonObject profile(NativeWorldDefinition definition, String dimension) {
        return JsonParser.parseString(definition.diagnosticProfile(dimension)).getAsJsonObject();
    }

    private static boolean emptyCount(JsonObject profile, String source) {
        return profile.getAsJsonArray("decorations").asList().stream().map(v -> v.getAsJsonObject())
                .filter(row -> row.get("source").getAsString().equals(source)).anyMatch(row -> {
                    var first = row.getAsJsonArray("placement").get(0).getAsJsonObject();
                    return first.get("type").getAsString().equals("count") && first.get("count").getAsInt() == 0;
                });
    }

    private static void write(Path path, String contents) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Carving mask verification failed: " + label);
    }
}
