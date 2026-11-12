package art.arcane.retina.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Structural profile encoding shared with native/world_definition/value.rs. */
public final class ProfileEncoding {
    private ProfileEncoding() { }

    public static <T, C extends T, L extends T> T encode(JsonElement value, DefinitionTags<T, C, L> tags) {
        if (value.isJsonNull()) return tags.byteTag((byte) -1);
        if (value.isJsonObject()) {
            var result = tags.compound();
            value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> tags.put(result, entry.getKey(), encode(entry.getValue(), tags)));
            return result;
        }
        if (value.isJsonArray()) return array(value.getAsJsonArray(), tags);
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return tags.byteTag(primitive.getAsBoolean() ? (byte) 1 : (byte) 0);
        if (primitive.isString()) return tags.string(primitive.getAsString());
        if (integer(primitive)) {
            long number = primitive.getAsBigDecimal().longValueExact();
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                    ? tags.intTag((int) number) : tags.longTag(number);
        }
        double number = primitive.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite profile number");
        // The host adapter preserves the raw bits of -0.0.
        return tags.doubleTag(number);
    }

    private static boolean integer(JsonElement value) {
        if (!(value instanceof JsonPrimitive p) || !p.isNumber()) return false;
        // Preserve floating point (including -0.0), even when mathematically integral.
        String number = p.getAsString();
        return !number.contains(".") && !number.contains("e") && !number.contains("E");
    }

    private static <T, C extends T, L extends T> T array(JsonArray values, DefinitionTags<T, C, L> tags) {
        if (values.isEmpty()) return tags.list();
        if (values.asList().stream().allMatch(ProfileEncoding::integer)) {
            var numbers = values.asList().stream().mapToLong(v -> v.getAsBigDecimal().longValueExact()).toArray();
            boolean bytes = true, ints = true;
            for (long number : numbers) {
                bytes &= number >= 0 && number <= 255;
                ints &= number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
            }
            if (bytes) {
                var result = new byte[numbers.length];
                for (int i = 0; i < result.length; i++) result[i] = (byte) numbers[i];
                return tags.bytes(result);
            }
            if (ints) {
                var result = new int[numbers.length];
                for (int i = 0; i < result.length; i++) result[i] = (int) numbers[i];
                return tags.ints(result);
            }
            return tags.longs(numbers);
        }
        boolean primitives = values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isString())
                || values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isBoolean())
                || values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isNumber() && !integer(v));
        var result = tags.list();
        for (var value : values) {
            if (primitives) tags.add(result, encode(value, tags));
            else {
                var wrapper = tags.compound();
                tags.put(wrapper, "v", encode(value, tags));
                tags.add(result, wrapper);
            }
        }
        return result;
    }
}
