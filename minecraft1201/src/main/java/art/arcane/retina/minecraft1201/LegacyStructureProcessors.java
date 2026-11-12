package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Resolves the 1.20.1 processor codec into palette indices before native generation. */
final class LegacyStructureProcessors {
    private static final Set<String> SUPPORTED = Set.of("minecraft:rule", "minecraft:block_rot", "minecraft:protected_blocks",
            "minecraft:capped", "minecraft:nop");

    private LegacyStructureProcessors() { }

    static JsonElement bind(JsonElement source, LinkedHashMap<BlockState, Integer> materials, Set<String> gaps) {
        walk(source, row -> {
            if (row.has("processor_type")) {
                String type = row.get("processor_type").getAsString();
                if (!SUPPORTED.contains(type)) {
                    gaps.add("processor:" + type);
                    // Preserve its position in the list without passing an incomplete native table.
                    row.addProperty("processor_type", "minecraft:nop");
                }
            }
            for (String field : List.of("block_state", "output_state")) {
                if (!row.has(field)) continue;
                var raw = row.get(field);
                var state = decode(BlockState.CODEC.parse(JsonOps.INSTANCE, raw));
                var binding = raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
                binding.add("retina_rotations", LegacyTemplateNbt.rotations(state, materials));
                if (field.equals("output_state") && state.getBlock() instanceof EntityBlock type) {
                    var entity = type.newBlockEntity(BlockPos.ZERO, state);
                    if (entity != null) binding.add("retina_entity", LegacyTemplateNbt.tag(entity.saveWithFullMetadata()));
                }
                row.add(field, binding);
            }
            var modifier = row.getAsJsonObject("block_entity_modifier");
            if (modifier != null && modifier.get("type").getAsString().equals("minecraft:append_static")) {
                var data = decode(CompoundTag.CODEC.parse(JsonOps.INSTANCE, modifier.get("data")));
                modifier.add("retina_nbt", LegacyTemplateNbt.tag(data));
            }
        }, Collections.newSetFromMap(new IdentityHashMap<>()));
        return source;
    }

    static void finish(JsonObject profile, LinkedHashMap<BlockState, Integer> materials) {
        if (profile == null) return;
        walk(profile.getAsJsonObject("pools"), row -> {
            if (row.has("predicate_type") && row.get("predicate_type").getAsString().equals("minecraft:tag_match")) {
                bindTag(row, "tag", "retina_matching", materials);
            }
            if (row.has("processor_type") && row.get("processor_type").getAsString().equals("minecraft:protected_blocks")) {
                bindTag(row, "value", "retina_protected", materials);
            }
            if (row.has("rottable_blocks")) bindRotatable(row, materials);
        }, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void bindRotatable(JsonObject row, LinkedHashMap<BlockState, Integer> materials) {
        var raw = row.get("rottable_blocks");
        if (raw.isJsonPrimitive() && raw.getAsString().startsWith("#")) {
            bindTag(row, "rottable_blocks", "retina_rottable", materials);
            return;
        }
        var blocks = new HashSet<ResourceLocation>();
        if (raw instanceof JsonArray list) {
            for (var item : list) blocks.add(new ResourceLocation(item.getAsString()));
        } else {
            blocks.add(new ResourceLocation(raw.getAsString()));
        }
        var indices = new JsonArray();
        materials.forEach((state, index) -> {
            if (blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) indices.add(index);
        });
        row.add("retina_rottable", indices);
    }

    private static void bindTag(JsonObject row, String field, String target, LinkedHashMap<BlockState, Integer> materials) {
        if (!row.has(field)) return;
        String id = row.get(field).getAsString();
        var tag = TagKey.create(Registries.BLOCK, new ResourceLocation(id.startsWith("#") ? id.substring(1) : id));
        var indices = new JsonArray();
        materials.forEach((state, index) -> {
            if (state.is(tag)) indices.add(index);
        });
        row.add(target, indices);
    }

    private static void walk(JsonElement value, java.util.function.Consumer<JsonObject> visit, Set<JsonElement> seen) {
        if (!seen.add(value)) return;
        if (value instanceof JsonObject row) {
            visit.accept(row);
            for (var field : row.entrySet()) {
                if (!field.getKey().startsWith("retina_")) walk(field.getValue(), visit, seen);
            }
        } else if (value instanceof JsonArray list) {
            for (var entry : list) walk(entry, visit, seen);
        }
    }
}
