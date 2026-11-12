package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeWorldDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SurfaceFreezeVerification {
    private record Probe(String id, String biome, int height, boolean water, boolean features) { }
    private static final List<Probe> PROBES = List.of(
            new Probe("grass", "cold", 4, false, true),
            new Probe("high", "threshold", 204, false, true),
            new Probe("warm", "threshold", 4, false, true),
            new Probe("water", "frozen", 126, true, true),
            new Probe("disabled", "cold", 4, false, false));
    private static final com.mojang.serialization.Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private SurfaceFreezeVerification() { }

    static void pack(Path packs) throws Exception {
        var directory = packs.resolve("retina-surface-freeze");
        write(directory.resolve("pack.mcmeta"), """
                {"pack":{"pack_format":15,"description":"Retina 1.20.1 surface freeze verification"}}
                """);
        biome(directory, "cold", 0.0, "none");
        biome(directory, "threshold", 0.225, "none");
        biome(directory, "frozen", 0.0, "frozen");
        for (var probe : PROBES) {
            write(directory.resolve("data/retina/dimension/freeze_" + probe.id() + ".json"), """
                    {"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{
                      "biome":"retina:freeze_%s","features":%s,"lakes":false,"structure_overrides":[],
                      "layers":[{"block":"minecraft:stone","height":%d},{"block":"minecraft:%s","height":1}]}}}
                    """.formatted(probe.biome(), probe.features(), probe.height() - 1,
                    probe.water() ? "water" : "grass_block"));
        }
    }

    private static void biome(Path directory, String id, double temperature, String modifier) throws Exception {
        write(directory.resolve("data/retina/worldgen/biome/freeze_" + id + ".json"), """
                {"has_precipitation":true,"temperature":%s,"temperature_modifier":"%s","downfall":0.5,
                 "effects":{"fog_color":12638463,"water_color":4159204,"water_fog_color":329011,"sky_color":7907327},
                 "carvers":{},"features":[[],[],[],[],[],[],[],[],[],[],["minecraft:freeze_top_layer"]],
                 "spawners":{},"spawn_costs":{}}
                """.formatted(temperature, modifier));
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    static void verify(Path world, RegistryExport.Loaded loaded) throws Exception {
        var biomes = loaded.registries().registryOrThrow(Registries.BIOME);
        try (var definition = NativeWorldDefinition.open(world.resolve("retina.dat"))) {
            for (var probe : PROBES) {
                var id = "retina:freeze_" + probe.id();
                var biome = biomes.get(new ResourceLocation("retina:freeze_" + probe.biome()));
                require(biome != null, "fixture biome " + id);
                definition.bindDimension(id, 2);
                var profile = JsonParser.parseString(definition.diagnosticProfile(id)).getAsJsonObject();
                require(profile.has("terrain_features") == probe.features(), "freeze data matches enabled feature " + id);
                var directory = Files.createDirectories(world.resolve(probe.id()));
                var path = directory.resolve("r.-1.0.mca");
                long start = System.nanoTime();
                require(definition.generateRegion(id, -1, 0, path).generated() == 1024, "whole region " + id);
                int count = inspect(path, probe, biome);
                int total = 1024 * 256;
                require(switch (probe.id()) {
                    case "grass" -> count == total;
                    case "high", "water" -> count > 0 && count < total;
                    default -> count == 0;
                }, "fixture exercises expected temperature range " + id + " " + count);
                var report = new JsonObject();
                report.addProperty("event", "surface_freeze_1201");
                report.addProperty("dimension", id);
                report.addProperty("frozen", count);
                report.addProperty("columns", total);
                report.addProperty("milliseconds", (System.nanoTime() - start) / 1_000_000);
                System.out.println("QA_EVT " + report);
            }
        }
    }

    private static int inspect(Path path, Probe probe, Biome biome) throws Exception {
        int top = -64 + probe.height();
        int frozen = 0;
        var pos = new BlockPos.MutableBlockPos();
        try (var file = new RegionFile(path, path.getParent(), true)) {
            for (int cz = 0; cz < 32; cz++) for (int cx = -32; cx < 0; cx++) {
                try (var input = file.getChunkDataInputStream(new ChunkPos(cx, cz))) {
                    var sections = NbtIo.read(input).getList("sections", Tag.TAG_COMPOUND);
                    var ground = section(sections, top - 1);
                    var above = (top >> 4) == ((top - 1) >> 4) ? ground : section(sections, top);
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int y = probe.water() ? top - 1 : top;
                        pos.set(cx * 16 + x, y, cz * 16 + z);
                        boolean cold = probe.features() && biome.coldEnoughToSnow(pos);
                        var support = ground.get(x, (top - 1) & 15, z);
                        var surface = above.get(x, top & 15, z);
                        if (probe.water()) {
                            require(support.is(cold ? Blocks.ICE : Blocks.WATER) && surface.isAir(), "water at " + pos);
                        } else {
                            require(support.is(Blocks.GRASS_BLOCK) && support.getValue(SnowyDirtBlock.SNOWY) == cold,
                                    "grass at " + pos);
                            require(cold ? surface == Blocks.SNOW.defaultBlockState() : surface.isAir(), "snow at " + pos);
                        }
                        if (cold) frozen++;
                    }
                }
            }
        }
        return frozen;
    }

    private static PalettedContainer<BlockState> section(net.minecraft.nbt.ListTag sections, int y) {
        for (var raw : sections) {
            var section = (CompoundTag) raw;
            if (section.getByte("Y") == (y >> 4) && section.contains("block_states")) {
                return LegacyDensityCompiler.decode(STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")));
            }
        }
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
