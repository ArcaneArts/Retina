package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FossilFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

final class LegacyOverworldFossils {
    record Recipe(JsonObject data, int reach) { }

    private static final Set<String> PROCESSORS = Set.of(
            "minecraft:block_rot", "minecraft:protected_blocks", "minecraft:rule", "minecraft:nop");
    private static final Set<String> RULE_STATES = Set.of("minecraft:always_true", "minecraft:block_match",
            "minecraft:blockstate_match", "minecraft:tag_match", "minecraft:random_block_match",
            "minecraft:random_blockstate_match");
    private static final Set<String> RULE_POSITIONS = Set.of(
            "minecraft:always_true", "minecraft:linear_pos", "minecraft:axis_aligned_linear_pos");

    private final RegistryOps<JsonElement> ops;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final LegacyFeaturePredicates predicates;
    private final Function<ResourceLocation, Optional<CompoundTag>> source;
    private final int minY;
    private final Map<ResourceLocation, JsonObject> templates = new HashMap<>();
    private final JsonArray processors = new JsonArray();

    LegacyOverworldFossils(RegistryOps<JsonElement> ops, LinkedHashMap<BlockState, Integer> palette,
                         LegacyFeaturePredicates predicates, Function<ResourceLocation, Optional<CompoundTag>> source,
                         int minY) {
        this.ops = ops;
        this.palette = palette;
        this.predicates = predicates;
        this.source = source;
        this.minY = minY;
    }

    Recipe compile(FossilFeatureConfiguration config) {
        var pairs = new JsonArray();
        int reach = 0;
        for (int i = 0; i < config.fossilStructures.size(); i++) {
            var base = template(config.fossilStructures.get(i));
            var overlay = template(config.overlayStructures.get(i));
            var pair = new JsonArray();
            pair.add(base);
            pair.add(overlay);
            pairs.add(pair);
            var size = base.getAsJsonArray("size");
            var overlaySize = overlay.getAsJsonArray("size");
            for (int axis : new int[]{0, 2}) {
                int side = size.get(axis).getAsInt();
                reach = Math.max(reach, (side + 1) / 2 + Math.max(0, overlaySize.get(axis).getAsInt() - side));
            }
        }
        if (pairs.isEmpty()) throw new IllegalArgumentException("fossil:empty-templates");
        var fossil = new JsonObject();
        fossil.add("pairs", pairs);
        fossil.add("base_processors", program(config.fossilProcessors.value()));
        fossil.add("overlay_processors", program(config.overlayProcessors.value()));
        fossil.addProperty("empty_corners", config.maxEmptyCornersAllowed);
        fossil.addProperty("min_y", minY);
        fossil.addProperty("floor_map", Heightmap.Types.OCEAN_FLOOR_WG.ordinal());
        fossil.add("empty", predicates.material(state -> state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA)));
        var data = new JsonObject();
        data.addProperty("kind", "fossil");
        data.add("fossil", fossil);
        return new Recipe(data, reach);
    }

    void finish() {
        LegacyStructureProcessors.finishPrograms(processors, palette);
    }

    private JsonObject template(ResourceLocation id) {
        return templates.computeIfAbsent(id, key -> {
            var raw = source.apply(key).orElseThrow(() -> new IllegalArgumentException("fossil:missing-template:" + key));
            if (!raw.getList("entities", Tag.TAG_COMPOUND).isEmpty() || raw.getList("blocks", Tag.TAG_COMPOUND)
                    .stream().anyMatch(block -> ((CompoundTag) block).contains("nbt", Tag.TAG_COMPOUND))) {
                throw new IllegalArgumentException("fossil:template-entities:" + key);
            }
            return LegacyTemplateNbt.template(key.toString(), raw, 0, palette, new JsonArray());
        });
    }

    private JsonElement program(StructureProcessorList list) {
        var raw = decode(StructureProcessorType.DIRECT_CODEC.encodeStart(ops, list));
        var entries = raw.isJsonArray() ? raw.getAsJsonArray() : raw.getAsJsonObject().getAsJsonArray("processors");
        for (var entry : entries) {
            var row = entry.getAsJsonObject();
            String type = row.get("processor_type").getAsString();
            if (!PROCESSORS.contains(type)) throw new IllegalArgumentException("fossil:processor:" + type);
            if (row.has("rules")) for (var value : row.getAsJsonArray("rules")) {
                var rule = value.getAsJsonObject();
                requirePredicate(rule, "input_predicate", RULE_STATES);
                requirePredicate(rule, "location_predicate", RULE_STATES);
                requirePredicate(rule, "position_predicate", RULE_POSITIONS);
                if (rule.has("block_entity_modifier")) {
                    throw new IllegalArgumentException("fossil:processor-block-entity");
                }
            }
        }
        var bound = LegacyStructureProcessors.bind(raw, palette, new TreeSet<>());
        for (var entry : entries) {
            var row = entry.getAsJsonObject();
            if (!row.has("rules")) continue;
            for (var value : row.getAsJsonArray("rules")) {
                if (value.getAsJsonObject().getAsJsonObject("output_state").has("retina_entity")) {
                    throw new IllegalArgumentException("fossil:processor-block-entity");
                }
            }
        }
        processors.add(bound);
        return bound;
    }
    private static void requirePredicate(JsonObject rule, String field, Set<String> allowed) {
        if (!rule.has(field)) return;
        String type = rule.getAsJsonObject(field).get("predicate_type").getAsString();
        if (!allowed.contains(type)) throw new IllegalArgumentException("fossil:predicate:" + type);
    }

}
