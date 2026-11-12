package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Function;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Exports the loaded template-pool graph for Retina's shared jigsaw planner. */
final class LegacyJigsawProfile {
    private final RegistryAccess registries;
    private final RegistryOps<JsonElement> ops;
    private final LinkedHashMap<BlockState, Integer> materials;
    private final Function<ResourceLocation, Optional<CompoundTag>> source;
    private final JsonArray templates;
    private final JsonObject pools;
    private final JsonArray omissions;
    private final HashMap<String, Integer> indices = new HashMap<>();
    private final HashMap<String, JsonElement> processors = new HashMap<>();
    private final LinkedHashSet<String> gaps = new LinkedHashSet<>();

    private LegacyJigsawProfile(RegistryAccess registries, LinkedHashMap<BlockState, Integer> materials,
                                Function<ResourceLocation, Optional<CompoundTag>> source, JsonObject profile) {
        this.registries = registries;
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.materials = materials;
        this.source = source;
        this.templates = profile.getAsJsonArray("templates");
        this.pools = profile.getAsJsonObject("pools");
        this.omissions = profile.getAsJsonArray("omissions");
    }

    static void add(RegistryAccess registries, LinkedHashMap<BlockState, Integer> materials,
                    Function<ResourceLocation, Optional<CompoundTag>> source, WorldGenerationContext context, JsonObject profile) {
        var exporter = new LegacyJigsawProfile(registries, materials, source, profile);
        for (var entry : profile.getAsJsonArray("definitions")) {
            var definition = entry.getAsJsonObject();
            if (!definition.get("kind").getAsString().equals("jigsaw")) continue;
            var config = definition.getAsJsonObject("config");
            var root = config.get("start_pool");
            LegacyFeaturePlacement.bindHeight(config.get("start_height"), context);
            if (!root.isJsonPrimitive()) {
                exporter.gaps.add("inline_start_pool:" + definition.get("id").getAsString());
                definition.addProperty("unimplemented", true);
                continue;
            }
            var id = root.getAsString();
            definition.addProperty("pool", id);
            exporter.pool(id);
            if (!exporter.pools.has(id)) definition.addProperty("unimplemented", true);
        }
        exporter.gaps.forEach(exporter.omissions::add);
    }

    private void pool(String id) {
        if (pools.has(id)) return;
        var value = registries.registryOrThrow(Registries.TEMPLATE_POOL).get(new ResourceLocation(id));
        if (value == null) {
            gaps.add("missing_pool:" + id);
            return;
        }
        var raw = decode(StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, value)).getAsJsonObject();
        var pool = new JsonObject();
        var fallbackRef = raw.get("fallback");
        String fallback = fallbackRef.isJsonPrimitive() ? fallbackRef.getAsString() : "minecraft:empty";
        if (!fallbackRef.isJsonPrimitive()) gaps.add("inline_fallback_pool:" + id);
        pool.addProperty("fallback", fallback);
        var entries = new JsonArray();
        pool.add("entries", entries);
        // Register the pool first. Template connectors can link back to it.
        pools.add(id, pool);
        pool(fallback);
        for (var valueElement : raw.getAsJsonArray("elements")) {
            var valueEntry = valueElement.getAsJsonObject();
            var element = valueEntry.getAsJsonObject("element");
            var row = new JsonObject();
            row.add("weight", valueEntry.get("weight"));
            row.addProperty("terrain_matching", element.has("projection")
                    && element.get("projection").getAsString().equals("terrain_matching"));
            var parts = new JsonArray();
            appendParts(element, parts);
            row.add("parts", parts);
            entries.add(row);
        }
    }

    private void appendParts(JsonObject element, JsonArray parts) {
        String kind = element.get("element_type").getAsString();
        switch (kind) {
            case "minecraft:empty_pool_element" -> { }
            case "minecraft:list_pool_element" -> {
                for (var nested : element.getAsJsonArray("elements")) appendParts(nested.getAsJsonObject(), parts);
            }
            case "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                String id = element.get("location").getAsString();
                int index = template(id);
                if (index < 0) return;
                var part = new JsonObject();
                part.addProperty("template", index);
                part.addProperty("ignore_air", kind.equals("minecraft:legacy_single_pool_element"));
                part.addProperty("finalize_entities", true);
                part.add("processors", processors(element.get("processors")));
                parts.add(part);
            }
            default -> gaps.add("pool_element:" + kind);
        }
    }

    private int template(String id) {
        Integer known = indices.get(id);
        if (known != null) return known;
        var raw = source.apply(new ResourceLocation(id));
        if (raw.isEmpty()) {
            gaps.add("missing_template:" + id);
            indices.put(id, -1);
            return -1;
        }
        var template = LegacyTemplateNbt.template(id, raw.get(), 1, materials, omissions, this::joint);
        int index = templates.size();
        templates.add(template);
        indices.put(id, index);
        for (var joint : template.getAsJsonArray("joints")) pool(joint.getAsJsonObject().get("pool").getAsString());
        return index;
    }

    private JsonObject joint(JsonArray pos, BlockState state, CompoundTag tag) {
        var result = new JsonObject();
        result.add("pos", pos);
        var front = JigsawBlock.getFrontFacing(state);
        result.addProperty("front", front.ordinal());
        result.addProperty("top", JigsawBlock.getTopFacing(state).ordinal());
        // Connector priorities were added after 1.20.1.
        result.addProperty("selection_priority", 0);
        result.addProperty("placement_priority", 0);
        for (String key : new String[] {"name", "target", "pool"}) result.addProperty(key, tag.getString(key));
        String joint = tag.getString("joint");
        result.addProperty("joint", joint.isEmpty() ? (front.getAxis().isHorizontal() ? "aligned" : "rollable") : joint);
        String finalState = tag.getString("final_state");
        try {
            var parsed = BlockStateParser.parseForBlock(registries.lookupOrThrow(Registries.BLOCK),
                    finalState.isEmpty() ? "minecraft:air" : finalState, false);
            result.add("final", LegacyTemplateNbt.rotations(parsed.blockState(), materials));
        } catch (CommandSyntaxException error) {
            throw new IllegalArgumentException("Invalid 1.20.1 jigsaw final state " + finalState, error);
        }
        return result;
    }

    private JsonElement processors(JsonElement source) {
        if (!source.isJsonPrimitive()) return LegacyStructureProcessors.bind(source, materials, gaps);
        return processors.computeIfAbsent(source.getAsString(), id -> {
            var list = registries.registryOrThrow(Registries.PROCESSOR_LIST).get(new ResourceLocation(id));
            if (list == null) throw new IllegalArgumentException("Missing structure processor list " + id);
            return LegacyStructureProcessors.bind(decode(StructureProcessorType.DIRECT_CODEC.encodeStart(ops, list)), materials, gaps);
        });
    }
}
