package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.nio.file.*;
import java.util.*;

/** Additional scalar oracle: signed 64-bit seeds, truncating negative coordinates and far integer phases. */
public final class EndIslandReferenceTest {
    public static void main(String[] args) throws Exception {
        var report = new JsonObject();
        report.addProperty("schema", "retina.end.island-reference.v1");
        var seeds = new JsonArray();
        try (var f = new EndReferenceFixtures()) {
            for (long seed : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 42, 123456789}) {
                var state = f.state(seed);
                var samplers = state.samplersWithContext(SamplerContext.EMPTY_UNCACHED);
                var resolver = f.resolver(state);
                var source = new LegacyRandomSource(seed);
                source.consumeCount(17292);
                var centerNoise = new SimplexNoise(source, true);
                var out = new JsonObject(); out.addProperty("seed", seed);
                var points = new ArrayList<int[]>();
                for (int x : new int[]{-30000000, -16777217, -1025, -1024, -513, -17, -16, -9, -8, -7, -1,
                        0, 1, 7, 8, 9, 15, 16, 17, 511, 512, 1024, 1025, 16777217, 30000000})
                    for (int z : new int[]{-513, -1, 0, 511}) points.add(new int[]{x, z});
                var random = new Random(seed);
                for (int i = 0; i < 156; i++) points.add(new int[]{random.nextInt(60000001) - 30000000,
                        random.nextInt(60000001) - 30000000});
                var samples = new JsonArray();
                for (var point : points) {
                    int x = point[0], z = point[1];
                    var row = new JsonObject(); row.add("xz", EndReferenceFixtures.ints(x, z));
                    row.addProperty("island_bits", Float.floatToRawIntBits(samplers.sampleValue(f.settings.value().noiseRouter().erosion(), x, 64, z)));
                    row.addProperty("outer_bits", Float.floatToRawIntBits(samplers.sampleValue(DensityFunctions.endOuterIslands(), x, 64, z)));
                    row.addProperty("center_bits", Float.floatToRawIntBits(centerNoise.get(x / 16, z / 16)));
                    row.addProperty("biome", EndReferenceFixtures.biome(resolver.getNoiseBiome(Math.floorDiv(x, 4), 16, Math.floorDiv(z, 4))));
                    samples.add(row);
                }
                out.add("samples", samples); seeds.add(out);
            }
        }
        report.add("seeds", seeds); report.addProperty("payload_sha256", EndReferenceFixtures.hash(report));
        var path = Path.of(args[0]); Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(EndReferenceFixtures.canonical(report)) + "\n");
        System.out.println("QA_EVT {\"event\":\"end_island_reference\",\"status\":\"pass\",\"context\":{\"seeds\":7,\"samples\":1792,\"sha256\":\""
                + report.get("payload_sha256").getAsString() + "\"}}");
    }
}
