package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.DensityProgramCompiler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.HashMap;
import java.util.Map;

/** Translates the 1.20.1 density codecs into the shared compiler's input. */
final class LegacyDensityCompiler extends DensityProgramCompiler {
    private final RegistryAccess registries;
    private final RegistryOps<JsonElement> ops;
    private final NoiseSettings settings;
    private final Map<String, JsonElement> densities = new HashMap<>();

    LegacyDensityCompiler(RegistryAccess registries, NoiseSettings settings) {
        this.registries = registries;
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.settings = settings;
    }

    JsonElement encode(DensityFunction function) {
        return decode(DensityFunction.HOLDER_HELPER_CODEC.encodeStart(ops, function));
    }

    @Override
    protected JsonElement resolve(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return value;
        return densities.computeIfAbsent(value.getAsString(), id -> {
            var registry = registries.registryOrThrow(Registries.DENSITY_FUNCTION);
            var function = registry.get(new ResourceLocation(id));
            if (function == null) throw new IllegalArgumentException("Unknown 1.20.1 density function " + id);
            return decode(DensityFunction.DIRECT_CODEC.encodeStart(ops, function));
        });
    }

    @Override
    protected JsonObject noiseParameters(JsonElement value) {
        var holder = decode(NormalNoise.NoiseParameters.CODEC.parse(ops, value));
        var noise = holder.value();
        var parameters = new JsonObject();
        parameters.addProperty("base_octave", noise.firstOctave());
        parameters.addProperty("octave_count", noise.amplitudes().size());
        parameters.addProperty("normalize", "legacy");
        var amplitudes = new JsonArray();
        noise.amplitudes().forEach(amplitude -> amplitudes.add(amplitude));
        parameters.add("amplitude_modifiers", amplitudes);
        return parameters;
    }

    @Override
    protected JsonObject normalizeDensity(JsonObject value) {
        return switch (type(value)) {
            case "cache_2d", "cache_once", "flat_cache", "cache_all_in_cell" ->
                    expression(value, "cache", "input", "argument");
            case "abs", "cube", "square", "half_negative", "quarter_negative", "squeeze", "blend_density" ->
                    expression(value, type(value), "input", "argument");
            case "add", "mul", "min", "max" -> {
                var node = expression(value, type(value), "left", "argument1");
                node.add("right", value.get("argument2"));
                yield node;
            }
            case "interpolated" -> {
                var node = expression(value, "interpolated", "input", "argument");
                node.addProperty("cell_size_xz", settings.getCellWidth());
                node.addProperty("cell_size_y", settings.getCellHeight());
                yield node;
            }
            case "shift", "shift_a", "shift_b" -> expression(value, type(value), "noise", "argument");
            case "shifted_noise" -> {
                var node = new JsonObject();
                value.entrySet().forEach(entry -> node.add(entry.getKey(), entry.getValue()));
                node.addProperty("type", "noise");
                yield node;
            }
            case "y_clamped_gradient" -> {
                var node = expression(value, "gradient", "from_coordinate", "from_y");
                node.addProperty("axis", "y");
                node.add("to_coordinate", value.get("to_y"));
                node.add("from_value", value.get("from_value"));
                node.add("to_value", value.get("to_value"));
                yield node;
            }
            case "weird_scaled_sampler" -> scaledNoise(value);
            default -> value;
        };
    }

    private static JsonObject expression(JsonObject original, String kind, String field, String originalField) {
        var node = new JsonObject();
        node.addProperty("type", kind);
        node.add(field, original.get(originalField));
        return node;
    }

    private static JsonObject scaledNoise(JsonObject original) {
        var node = expression(original, "scaled_absolute_noise", "noise", "noise");
        node.add("input", original.get("input"));
        // The two quantized rarity functions used by 1.20.1 spaghetti caves.
        switch (original.get("rarity_value_mapper").getAsString()) {
            case "type_1" -> {
                node.add("thresholds", array(-0.5F, 0, 0.5F));
                node.add("scales", array(0.75F, 1, 1.5F, 2));
            }
            case "type_2" -> {
                node.add("thresholds", array(-0.75F, -0.5F, 0.5F, 0.75F));
                node.add("scales", array(0.5F, 0.75F, 1, 2, 3));
            }
            default -> throw new IllegalArgumentException("Unsupported 1.20.1 rarity mapper " + original);
        }
        return node;
    }

    private static JsonArray array(float... values) {
        var array = new JsonArray();
        for (float value : values) array.add(value);
        return array;
    }

    @Override
    protected int lowerOtherDensity(Program program, JsonObject value, String kind) {
        throw new IllegalArgumentException("Unsupported 1.20.1 density function " + value.get("type"));
    }

    static <T> T decode(DataResult<T> result) {
        return result.getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
    }
}
