package art.arcane.retina.minecraft1201;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

final class EndTerrainReference {
    private EndTerrainReference() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        try (var loaded = RegistryExport.load(output.getParent().resolve("datapacks"), WorldPresets.NORMAL)) {
            sample(output, loaded);
        }
    }

    private static void sample(Path output, RegistryExport.Loaded loaded) throws Exception {
        var end = loaded.dimensions().getHolderOrThrow(LevelStem.END).value();
        var generator = (NoiseBasedChunkGenerator) end.generator();
        var settings = generator.generatorSettings().value();
        var noises = loaded.registries().lookupOrThrow(Registries.NOISE);
        var heights = LevelHeightAccessor.create(end.type().value().minY(), end.type().value().height());
        var groups = new JsonArray();
        for (long seed : new long[]{0, 12345, -9223372036854775791L}) {
            var random = RandomState.create(settings, noises, seed);
            var group = new JsonObject();
            group.addProperty("seed", seed);
            var columns = new JsonArray();
            for (var position : new int[][]{
                    {0, 0}, {8, -8}, {-9, -24}, {70, 19}, {99, -9}, {-111, 63}, {119, 119}, {-161, -13},
                    {1008, 0}, {-1025, 42}, {1088, 36}, {1274, 418}, {-1426, -136}, {1548, 0},
                    {2998, -475}, {-2795, 2880}, {17321, -20305}, {370720, 0}, {390008, 16}, {-400016, -400008}
            }) {
                int x = position[0], z = position[1];
                var column = generator.getBaseColumn(x, z, heights, random);
                byte[] occupied = new byte[16];
                for (int y = 0; y < 128; y++) {
                    if (!column.getBlock(y).isAir()) occupied[y / 8] |= (byte) (1 << (y % 8));
                }
                var record = new JsonObject();
                record.addProperty("x", x);
                record.addProperty("z", z);
                record.addProperty("occupied_le", HexFormat.of().formatHex(occupied));
                var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z), random.sampler());
                record.addProperty("biome", biome.unwrapKey().orElseThrow().location().toString());
                var erosion = random.sampler().erosion().compute(new DensityFunction.SinglePointContext(x, 0, z));
                record.addProperty("erosion_bits", Long.toHexString(Double.doubleToRawLongBits(erosion)));
                columns.add(record);
            }
            group.add("columns", columns);
            groups.add(group);
        }
        var result = new JsonObject();
        result.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        result.add("groups", groups);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
        System.out.println("QA_EVT {\"event\":\"end_1201_reference\",\"status\":\"pass\",\"columns\":60}");
    }
}
