package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.densityfunction.*;

import java.nio.file.*;

/** Four complete chunks of vanilla base-column decisions and bulk density bits, without a saved world. */
public final class EndTerrainReferenceTest {
    public static void main(String[] args) throws Exception {
        var report = new JsonObject(); report.addProperty("schema", "retina.end.dense-terrain-reference.v1");
        var seeds = new JsonArray();
        int chunks = 0;
        try (var f = new EndReferenceFixtures()) {
            long[] values = {123456789, 42};
            int[][][] positions = {{{0, 0}, {32, 0}, {256, -33}}, {{-257, -1}}};
            for (int seedIndex = 0; seedIndex < values.length; seedIndex++) {
                long seed = values[seedIndex];
                var state = f.state(seed);
                var sampler = state.getSampler(f.settings.value().noiseRouter().finalDensity()).bind(SamplerContext.EMPTY_UNCACHED);
                var group = new JsonObject(); group.addProperty("seed", seed); group.add("samples", new JsonArray());
                var columns = new JsonArray(); var chunkPositions = new JsonArray();
                for (var chunk : positions[seedIndex]) {
                    chunkPositions.add(EndReferenceFixtures.ints(chunk[0], chunk[1])); chunks++;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        int wx = chunk[0] * 16 + x, wz = chunk[1] * 16 + z;
                        var base = f.generator.getBaseColumn(wx, wz, f.height, state);
                        var row = new JsonObject(); row.add("xz", EndReferenceFixtures.ints(wx, wz));
                        var density = new JsonArray(); var solid = new JsonArray();
                        try (var buffer = sampler.sampleVolume(new DensityVolume(1, 128, 1, wx, 0, wz))) {
                            for (int y = 0; y < 256; y++) {
                                boolean occupied = !base.getBlock(y).isAir();
                                if (occupied) solid.add(y);
                                if (y < 128) {
                                    float value = buffer.get(y);
                                    density.add(Float.floatToRawIntBits(value));
                                    if (occupied != (value > 0)) throw new AssertionError("source bulk sampler/base-column solidity differs");
                                } else if (occupied) throw new AssertionError("source base terrain above noise range");
                            }
                        }
                        row.add("density_bits", density); row.add("solid_y", solid);
                        var heights = new JsonObject();
                        for (var type : Heightmap.Types.values()) heights.addProperty(type.name(), f.generator.getBaseHeight(wx, wz, type, f.height, state));
                        row.add("heights", heights); columns.add(row);
                    }
                }
                group.add("chunks", chunkPositions); group.add("columns", columns); seeds.add(group);
            }
        }
        report.add("seeds", seeds); report.addProperty("chunks", chunks); report.addProperty("full_regions_generated", 0);
        report.addProperty("payload_sha256", EndReferenceFixtures.hash(report));
        var path = Path.of(args[0]); Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(EndReferenceFixtures.canonical(report)) + "\n");
        System.out.println("QA_EVT {\"event\":\"end_dense_terrain_reference\",\"status\":\"pass\",\"context\":{\"chunks\":"
                + chunks + ",\"columns\":1024,\"full_regions\":0,\"sha256\":\"" + report.get("payload_sha256").getAsString() + "\"}}");
    }
}
