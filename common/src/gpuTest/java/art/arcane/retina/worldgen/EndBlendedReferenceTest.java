package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import java.nio.file.*;
import java.util.*;

/** Actual Minecraft scalar and optimized volume paths; no Retina noise implementation. */
public final class EndBlendedReferenceTest {
    public static void main(String[] args) throws Exception {
        var report = new JsonObject(); report.addProperty("schema", "retina.end.blended-reference.v1");
        var cases = new JsonArray(); int points = 0, different = 0;
        try (var f = new EndReferenceFixtures()) {
            var registered = (BlendedNoise) f.registry.lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(
                    ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace("end/base_3d_noise"))).value();
            var configurations = List.of(registered, new BlendedNoise(.1875, .375, 63.5, 127.25, 2),
                    new BlendedNoise(.001, 1, 1000, .001, 8));
            for (long seed : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 42, 123456789}) {
                var state = f.state(seed);
                for (var configuration : configurations) {
                    var sampler = state.getSampler(configuration).bind(SamplerContext.EMPTY_UNCACHED);
                    var row = new JsonObject(); row.addProperty("seed", seed);
                    if (seed == 0 && configuration == registered) row.add("seed_tables", seedTables(configuration, seed));
                    row.add("parameters", BlendedNoise.CODEC.codec().encodeStart(JsonOps.INSTANCE, configuration).getOrThrow());
                    var samples = new JsonArray();
                    var positions = new ArrayList<int[]>();
                    for (int x : new int[]{-30000000, -16777217, -1025, -513, -17, -9, -8, -1,
                            0, 1, 7, 8, 16, 511, 1024, 16777217, 30000000})
                        for (int y : new int[]{-64, 0, 1, 4, 63, 64, 127, 128, 255})
                            positions.add(new int[]{x, y, 511 - y * 3});
                    var random = new Random(seed);
                    for (int i = 0; i < 47; i++) positions.add(new int[]{random.nextInt(60000001) - 30000000,
                            random.nextInt(4097) - 2048, random.nextInt(60000001) - 30000000});
                    for (var position : positions) {
                        int x = position[0], y = position[1], z = position[2];
                        float scalar = sampler.sampleValue(x, y, z);
                        float volume;
                        try (var buffer = sampler.sampleVolume(new DensityVolume(1, 1, 1, x, y, z))) { volume = buffer.get(0); }
                        if (!Float.isFinite(scalar) || !Float.isFinite(volume)) throw new AssertionError("finite source blended noise");
                        if (Float.floatToRawIntBits(scalar) != Float.floatToRawIntBits(volume)) different++;
                        var sample = new JsonObject(); sample.add("xyz", EndReferenceFixtures.ints(x, y, z));
                        sample.addProperty("scalar_bits", Float.floatToRawIntBits(scalar));
                        sample.addProperty("volume_bits", Float.floatToRawIntBits(volume));
                        samples.add(sample); points++;
                    }
                    // Exercise the optimized source column loops on the actual End lattice.
                    var grid = new DensityVolume(3, 5, 3, -520, 56, 504, 8, 4, 8);
                    try (var buffer = sampler.sampleVolume(grid)) {
                        for (int z = 0; z < grid.sizeZ(); z++) for (int x = 0; x < grid.sizeX(); x++) for (int y = 0; y < grid.sizeY(); y++) {
                            var sample = new JsonObject(); sample.add("xyz", EndReferenceFixtures.ints(grid.blockX(x), grid.blockY(y), grid.blockZ(z)));
                            sample.addProperty("volume_bits", Float.floatToRawIntBits(buffer.get(grid.indexUnchecked(x, y, z))));
                            samples.add(sample); points++;
                        }
                    }
                    row.add("points", samples); cases.add(row);
                }
            }
        }
        report.add("cases", cases); report.addProperty("points", points); report.addProperty("scalar_volume_differences", different);
        report.addProperty("payload_sha256", EndReferenceFixtures.hash(report));
        var path = Path.of(args[0]); Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(EndReferenceFixtures.canonical(report)) + "\n");
        System.out.println("QA_EVT {\"event\":\"end_blended_reference\",\"status\":\"pass\",\"context\":{\"points\":"
                + points + ",\"scalar_volume_differences\":" + different + ",\"sha256\":\"" + report.get("payload_sha256").getAsString() + "\"}}");
    }

    private static JsonObject seedTables(BlendedNoise configuration, long seed) throws Exception {
        var fbms = configuration.createFbmSet(new net.minecraft.world.level.levelgen.LegacyRandomSource(seed));
        var out = new JsonObject();
        var stacks = List.of(fbms.minLimitNoise(), fbms.maxLimitNoise(), fbms.mainNoise());
        String[] names = {"lower", "upper", "main"};
        for (int stack = 0; stack < stacks.size(); stack++) {
            var layers = new JsonArray();
            for (var value : (Object[]) field(stacks.get(stack), "layers")) {
                var noise = (net.minecraft.world.level.levelgen.synth.Noise) field(value, "noise");
                var row = new JsonObject();
                row.addProperty("frequency", (Number) field(value, "frequency"));
                row.addProperty("amplitude", (Number) field(value, "amplitude"));
                row.addProperty("fudge_y", (Number) field(noise, "fudgeYScale"));
                var offsets = new JsonArray();
                for (String axis : new String[]{"X", "Y", "Z"}) offsets.add((Number) field(noise, "offset" + axis));
                row.add("offsets", offsets);
                var permutation = new JsonArray();
                for (byte p : (byte[]) field(noise, "perms")) permutation.add(Byte.toUnsignedInt(p));
                row.add("permutation", permutation); layers.add(row);
            }
            out.add(names[stack], layers);
        }
        return out;
    }

    private static Object field(Object object, String name) throws Exception {
        for (var type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
