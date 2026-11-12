package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

import static art.arcane.retina.definition.FlatProfileModel.array;

final class LegacyTemplateNbt {
    private LegacyTemplateNbt() { }

    static JsonArray tag(Tag value) {
        var result = new JsonArray();
        result.add(value.getId());
        if (value instanceof CompoundTag compound) {
            var fields = new JsonObject();
            for (String key : compound.getAllKeys()) fields.add(key, tag(compound.get(key)));
            result.add(fields);
        } else if (value instanceof ListTag list) {
            var values = new JsonArray();
            list.forEach(child -> values.add(tag(child)));
            var data = new JsonArray();
            data.add(list.getElementType());
            data.add(values);
            result.add(data);
        } else if (value instanceof ByteArrayTag bytes) {
            var values = new JsonArray();
            for (byte n : bytes.getAsByteArray()) values.add(n);
            result.add(values);
        } else if (value instanceof IntArrayTag integers) {
            result.add(array(integers.getAsIntArray()));
        } else if (value instanceof LongArrayTag longs) {
            var values = new JsonArray();
            for (long n : longs.getAsLongArray()) values.add(n);
            result.add(values);
        } else if (value instanceof NumericTag number) {
            result.add(number.getAsNumber());
        } else {
            result.add(value.getAsString());
        }
        return result;
    }
}
