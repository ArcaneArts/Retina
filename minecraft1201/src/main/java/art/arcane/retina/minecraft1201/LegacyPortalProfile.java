package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyTerrainProfile.material;

/** Binds the 1.20.1 Nether ruined portal templates to the native structure replay. */
final class LegacyPortalProfile {
    private static final List<ResourceLocation> TEMPLATES = IntStream.range(0, 13)
            .mapToObj(i -> new ResourceLocation("ruined_portal/" + (i < 10 ? "portal_" + (i + 1) : "giant_portal_" + (i - 9))))
            .toList();

    private LegacyPortalProfile() { }

    static boolean supported(JsonObject config) {
        var setups = config.getAsJsonArray("setups");
        return !setups.isEmpty() && setups.asList().stream().map(value -> value.getAsJsonObject())
                .allMatch(setup -> setup.get("placement").getAsString().equals("in_nether") && !setup.get("can_be_cold").getAsBoolean());
    }

    static List<CompoundTag> load(Function<ResourceLocation, Optional<CompoundTag>> source, JsonArray omissions) {
        var templates = new ArrayList<CompoundTag>(TEMPLATES.size());
        for (var id : TEMPLATES) {
            var template = source.apply(id);
            if (template.isEmpty()) {
                omissions.add("missing_template:" + id);
                return List.of();
            }
            templates.add(template.get());
        }
        return templates;
    }

    static void add(JsonObject structures, List<CompoundTag> rawTemplates, LinkedHashMap<BlockState, Integer> materials) {
        var templates = structures.getAsJsonArray("templates");
        var omissions = structures.getAsJsonArray("omissions");
        var kit = new JsonObject();
        var pairs = new JsonArray();
        var processorTemplate = templates.size();
        for (int i = 0; i < rawTemplates.size(); i++) {
            var raw = rawTemplates.get(i);
            var size = raw.getList("size", Tag.TAG_INT);
            var pair = new JsonArray();
            for (var mirror : List.of(Mirror.NONE, Mirror.FRONT_BACK)) {
                var row = LegacyTemplateNbt.template(TEMPLATES.get(i).toString(), raw, 0, materials, omissions);
                var transform = new JsonObject();
                transform.addProperty("mirror", mirror.ordinal());
                transform.add("pivot", array(size.getInt(0) / 2, 0, size.getInt(2) / 2));
                row.add("transform", transform);
                pair.add(templates.size());
                templates.add(row);
            }
            pairs.add(pair);
        }
        kit.add("templates", pairs);
        structures.add("portals", kit);

        material(materials, Blocks.NETHERRACK.defaultBlockState());
        material(materials, Blocks.MAGMA_BLOCK.defaultBlockState());
        material(materials, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true));
        for (var direction : Direction.Plane.HORIZONTAL) {
            material(materials, vine(direction));
        }

        var pools = structures.getAsJsonObject("pools");
        var gaps = new LinkedHashSet<String>();
        for (var value : structures.getAsJsonArray("definitions")) {
            var definition = value.getAsJsonObject();
            if (!definition.get("kind").getAsString().equals("ruined_portal")) continue;
            String id = definition.get("id").getAsString().replace(':', '/');
            int index = 0;
            for (var setupValue : definition.getAsJsonObject("config").getAsJsonArray("setups")) {
                var setup = setupValue.getAsJsonObject();
                String pool = "retina:portal/" + id + "/" + index++;
                addPool(pools, pool, processorTemplate, LegacyStructureProcessors.bind(processors(setup), materials, gaps));
                setup.addProperty("retina_pool", pool);
            }
        }
        gaps.forEach(omissions::add);
    }

    private static JsonArray processors(JsonObject setup) {
        // 1.20.1 RuinedPortalPiece builds these rules in source order, followed by its processors.
        var rules = List.of(
                new ProcessorRule(new RandomBlockMatchTest(Blocks.GOLD_BLOCK, 0.3F), AlwaysTrueTest.INSTANCE, Blocks.AIR.defaultBlockState()),
                new ProcessorRule(new RandomBlockMatchTest(Blocks.LAVA, 0.2F), AlwaysTrueTest.INSTANCE, Blocks.MAGMA_BLOCK.defaultBlockState()),
                new ProcessorRule(new RandomBlockMatchTest(Blocks.NETHERRACK, 0.07F), AlwaysTrueTest.INSTANCE, Blocks.MAGMA_BLOCK.defaultBlockState()));
        var rule = decode(RuleProcessor.CODEC.encodeStart(JsonOps.INSTANCE, new RuleProcessor(rules))).getAsJsonObject();
        rule.addProperty("processor_type", "minecraft:rule");
        var result = new JsonArray();
        result.add(rule);
        var age = processor("block_age");
        age.add("mossiness", setup.get("mossiness"));
        result.add(age);
        var protectedBlocks = processor("protected_blocks");
        protectedBlocks.addProperty("value", BlockTags.FEATURES_CANNOT_REPLACE.location().toString());
        result.add(protectedBlocks);
        result.add(processor("lava_submerged_block"));
        if (setup.get("replace_with_blackstone").getAsBoolean()) result.add(processor("blackstone_replace"));
        return result;
    }

    private static JsonObject processor(String type) {
        var row = new JsonObject();
        row.addProperty("processor_type", "minecraft:" + type);
        return row;
    }

    private static void addPool(JsonObject pools, String name, int template, JsonElement processors) {
        var part = new JsonObject();
        part.addProperty("template", template);
        part.addProperty("ignore_air", true);
        part.add("processors", processors);
        var parts = new JsonArray();
        parts.add(part);
        var entry = new JsonObject();
        entry.add("parts", parts);
        entry.addProperty("weight", 1);
        entry.addProperty("terrain_matching", false);
        var entries = new JsonArray();
        entries.add(entry);
        var pool = new JsonObject();
        pool.addProperty("fallback", "minecraft:empty");
        pool.add("entries", entries);
        pools.add(name, pool);
    }

    static void prepare(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (structures == null || !structures.has("portals")) return;
        LegacyPortalShapes.prepare(materials);
        // Processors see untransformed materials. Close the transform table over all final material indices.
        for (var state : List.copyOf(materials.keySet())) {
            for (var mirror : List.of(Mirror.NONE, Mirror.FRONT_BACK)) {
                for (var rotation : Rotation.values()) material(materials, state.mirror(mirror).rotate(rotation));
            }
        }
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (structures == null || !structures.has("portals")) return;
        var kit = structures.getAsJsonObject("portals");
        kit.addProperty("netherrack", materials.get(Blocks.NETHERRACK.defaultBlockState()));
        kit.addProperty("magma", materials.get(Blocks.MAGMA_BLOCK.defaultBlockState()));
        kit.addProperty("leaves", materials.get(Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true)));
        var vines = new JsonArray();
        for (var direction : Direction.Plane.HORIZONTAL) vines.add(materials.get(vine(direction)));
        kit.add("vines", vines);

        var replaceable = new JsonArray();
        var air = new JsonArray();
        var fullFaces = new JsonArray();
        var vineStates = new JsonArray();
        for (var state : materials.keySet()) {
            replaceable.add(!state.is(Blocks.AIR) && !state.is(Blocks.OBSIDIAN) && !state.is(BlockTags.FEATURES_CANNOT_REPLACE));
            air.add(state.isAir());
            vineStates.add(state.is(Blocks.VINE));
            int faces = 0;
            var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            for (var direction : Direction.Plane.HORIZONTAL) {
                if (Block.isFaceFull(shape, direction)) faces |= 1 << direction.get2DDataValue();
            }
            fullFaces.add(faces);
        }
        kit.add("replaceable", replaceable);
        kit.add("air", air);
        kit.add("full_faces", fullFaces);
        kit.add("vine_states", vineStates);

        var transforms = new JsonArray[] {new JsonArray(), new JsonArray()};
        for (var state : materials.keySet()) {
            for (int i = 0; i < transforms.length; i++) {
                var row = new JsonArray();
                var mirror = i == 0 ? Mirror.NONE : Mirror.FRONT_BACK;
                for (var rotation : Rotation.values()) row.add(materials.get(state.mirror(mirror).rotate(rotation)));
                transforms[i].add(row);
            }
        }
        var templates = structures.getAsJsonArray("templates");
        for (var pair : kit.getAsJsonArray("templates")) {
            for (int i = 0; i < transforms.length; i++) {
                int index = pair.getAsJsonArray().get(i).getAsInt();
                templates.get(index).getAsJsonObject().getAsJsonObject("transform").add("materials", transforms[i]);
            }
        }
        LegacyPortalShapes.finish(kit, templates, materials);
    }

    private static BlockState vine(Direction face) {
        return Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(face.getOpposite()), true);
    }
}
