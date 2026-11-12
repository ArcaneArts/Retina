package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

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

    @FunctionalInterface
    interface JointEncoder {
        JsonObject encode(JsonArray pos, BlockState state, CompoundTag tag);
    }

    static JsonArray rotations(BlockState state, LinkedHashMap<BlockState, Integer> materials) {
        var rotations = new JsonArray();
        for (var rotation : Rotation.values()) rotations.add(LegacyTerrainProfile.material(materials, state.rotate(rotation)));
        return rotations;
    }

    static JsonObject template(String id, CompoundTag raw, int ground, LinkedHashMap<BlockState, Integer> materials,
                               JsonArray omissions) {
        return template(id, raw, ground, materials, omissions, null);
    }

    static JsonObject template(String id, CompoundTag raw, int ground, LinkedHashMap<BlockState, Integer> materials,
                               JsonArray omissions, JointEncoder joints) {
        var source = raw.getList("palettes", Tag.TAG_LIST);
        if (source.isEmpty()) source.add(raw.getList("palette", Tag.TAG_COMPOUND));

        var palettes = new JsonArray();
        var primary = new ArrayList<BlockState>();
        for (var value : source) {
            var palette = new JsonArray();
            for (var entry : (ListTag) value) {
                var state = decode(BlockState.CODEC.parse(NbtOps.INSTANCE, entry));
                if (palettes.isEmpty()) primary.add(state);
                palette.add(rotations(state, materials));
            }
            palettes.add(palette);
        }

        var blocks = new JsonArray();
        var tags = new JsonObject();
        var connectors = new JsonArray();
        int ordinal = 0;
        for (var value : raw.getList("blocks", Tag.TAG_COMPOUND)) {
            var block = (CompoundTag) value;
            var pos = block.getList("pos", Tag.TAG_INT);
            blocks.add(pos.getInt(0));
            blocks.add(pos.getInt(1));
            blocks.add(pos.getInt(2));
            blocks.add(block.getInt("state"));
            if (block.contains("nbt", Tag.TAG_COMPOUND)) {
                var data = block.getCompound("nbt");
                var state = primary.get(block.getInt("state"));
                if (joints != null && state.is(Blocks.JIGSAW)) {
                    connectors.add(joints.encode(array(pos.getInt(0), pos.getInt(1), pos.getInt(2)), state, data));
                } else if (joints == null || !state.is(Blocks.STRUCTURE_BLOCK)) {
                    tags.add(Integer.toString(ordinal), tag(data));
                }
            }
            ordinal++;
        }

        var template = new JsonObject();
        template.addProperty("id", id);
        template.addProperty("ground", ground);
        var size = raw.getList("size", Tag.TAG_INT);
        template.add("size", array(size.getInt(0), size.getInt(1), size.getInt(2)));
        template.add("palettes", palettes);
        template.add("blocks", blocks);
        template.add("joints", connectors);
        template.add("tags", tags);
        var entities = new JsonArray();
        var sourceEntities = raw.getList("entities", Tag.TAG_COMPOUND);
        if (joints != null) sourceEntities.forEach(value -> entities.add(tag(value)));
        else if (!sourceEntities.isEmpty()) omissions.add("template_entities:" + id);
        template.add("entities", entities);
        return template;
    }
}
