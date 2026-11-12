package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.LinkedHashMap;
import java.util.Map;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Export the initialized 1.20.1 placement and block-provider noise tables. */
final class LegacyDecorationNoise {
    private static final double SECOND_STACK_SCALE = 1.0181268882175227;
    private final JsonArray programs = new JsonArray();
    private final Map<NoiseKey, Integer> indices = new LinkedHashMap<>();

    private record NoiseKey(NormalNoise.NoiseParameters parameters, long seed, float scale, boolean slow) { }

    int register(JsonObject provider, boolean slow) {
        var parameters = decode(NormalNoise.NoiseParameters.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                provider.get(slow ? "slow_noise" : "noise")));
        var key = new NoiseKey(parameters, provider.get("seed").getAsLong(),
                provider.get(slow ? "slow_scale" : "scale").getAsFloat(), slow);
        return indices.computeIfAbsent(key, entry -> {
            int id = programs.size();
            programs.add(compile(entry));
            return id;
        });
    }

    void export(JsonObject world) {
        var countNoise = new JsonObject();
        var random = new WorldgenRandom(new LegacyRandomSource(2345L));
        offsets(random); // Biome.BIOME_INFO_NOISE uses SimplexNoise's octave zero.
        countNoise.add("permutation", permutation(random));
        world.add("decoration_noise", countNoise);
        world.add("decoration_provider_noises", programs);
    }

    private static JsonObject compile(NoiseKey key) {
        var amplitudes = key.parameters().amplitudes();
        int first = 0;
        while (first < amplitudes.size() && amplitudes.getDouble(first) == 0) first++;
        int last = amplitudes.size() - 1;
        while (last > first && amplitudes.getDouble(last) == 0) last--;
        var layers = new JsonArray();
        if (first < amplitudes.size()) {
            double valueFactor = (1.0 / 6.0) / (0.1 * (1.0 + 1.0 / (last - first + 1)));
            double inputScale = key.slow() ? 1.0 : key.scale();
            var random = new WorldgenRandom(new LegacyRandomSource(key.seed()));
            appendStack(layers, random, key.parameters(), inputScale, valueFactor);
            appendStack(layers, random, key.parameters(), inputScale * SECOND_STACK_SCALE, valueFactor);
        }
        var program = new JsonObject();
        program.add("layers", layers);
        if (key.slow()) program.addProperty("coordinate_scale", key.scale());
        return program;
    }

    private static void appendStack(JsonArray layers, RandomSource random, NormalNoise.NoiseParameters parameters,
                                    double scale, double valueFactor) {
        var positional = random.forkPositional();
        var amplitudes = parameters.amplitudes();
        double weight = Math.scalb(1.0, amplitudes.size() - 1) / (Math.scalb(1.0, amplitudes.size()) - 1.0);
        for (int i = 0; i < amplitudes.size(); i++, weight *= 0.5) {
            double amplitude = amplitudes.getDouble(i);
            if (amplitude == 0) continue;
            int octave = parameters.firstOctave() + i;
            var octaveRandom = positional.fromHashOf("octave_" + octave);
            var layer = new JsonObject();
            layer.addProperty("frequency", Math.scalb(scale, octave));
            layer.addProperty("amplitude", (float) (amplitude * weight * valueFactor));
            layer.add("offsets", offsets(octaveRandom));
            layer.add("permutation", permutation(octaveRandom));
            layers.add(layer);
        }
    }

    private static JsonArray offsets(RandomSource random) {
        var offsets = new JsonArray();
        for (int i = 0; i < 3; i++) offsets.add(random.nextDouble() * 256.0);
        return offsets;
    }

    private static JsonArray permutation(RandomSource random) {
        int[] values = new int[256];
        for (int i = 0; i < values.length; i++) values[i] = i;
        var permutation = new JsonArray();
        for (int i = 0; i < values.length; i++) {
            int other = i + random.nextInt(values.length - i);
            permutation.add(values[other]);
            values[other] = values[i];
        }
        return permutation;
    }
}
