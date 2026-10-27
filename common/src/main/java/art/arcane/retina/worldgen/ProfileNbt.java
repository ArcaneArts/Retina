package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.*;

/** Structural profile encoding shared with native/world_definition/value.rs. */
final class ProfileNbt {
    private ProfileNbt() { }

    static Tag encode(JsonElement value) {
        if (value.isJsonNull()) return ByteTag.valueOf((byte) -1);
        if (value.isJsonObject()) {
            var result = new CompoundTag();
            value.getAsJsonObject().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(), encode(entry.getValue())));
            return result;
        }
        if (value.isJsonArray()) return array(value.getAsJsonArray());
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return ByteTag.valueOf(primitive.getAsBoolean());
        if (primitive.isString()) return StringTag.valueOf(primitive.getAsString());
        if (integer(primitive)) {
            long number = primitive.getAsBigDecimal().longValueExact();
            return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE
                    ? IntTag.valueOf((int) number) : LongTag.valueOf(number);
        }
        double number = primitive.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite profile number");
        // Minecraft's valueOf cache normalizes -0.0 to +0.0. Persist exact bits.
        return new DoubleTag(number);
    }

    private static boolean integer(JsonElement value) {
        if (!(value instanceof JsonPrimitive p) || !p.isNumber()) return false;
        // Preserve floating point (including -0.0), even when mathematically integral.
        String number = p.getAsString();
        return !number.contains(".") && !number.contains("e") && !number.contains("E");
    }

    private static Tag array(JsonArray values) {
        if (values.isEmpty()) return new ListTag();
        if (values.asList().stream().allMatch(ProfileNbt::integer)) {
            var numbers = values.asList().stream().mapToLong(v -> v.getAsBigDecimal().longValueExact()).toArray();
            boolean bytes = true, ints = true;
            for (long number : numbers) {
                bytes &= number >= 0 && number <= 255;
                ints &= number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE;
            }
            if (bytes) {
                var result = new byte[numbers.length];
                for (int i = 0; i < result.length; i++) result[i] = (byte) numbers[i];
                return new ByteArrayTag(result);
            }
            if (ints) {
                var result = new int[numbers.length];
                for (int i = 0; i < result.length; i++) result[i] = (int) numbers[i];
                return new IntArrayTag(result);
            }
            return new LongArrayTag(numbers);
        }
        boolean primitives = values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isString())
                || values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isBoolean())
                || values.asList().stream().allMatch(v -> v.isJsonPrimitive()
                && v.getAsJsonPrimitive().isNumber() && !integer(v));
        var result = new ListTag();
        for (var value : values) {
            if (primitives) result.add(encode(value));
            else {
                var wrapper = new CompoundTag();
                wrapper.put("v", encode(value));
                result.add(wrapper);
            }
        }
        return result;
    }
}
