package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.type;
import static art.arcane.retina.minecraft1201.LegacyFeaturePredicates.selection;

final class LegacyTreeFeatures {
    private static final Set<String> TRUNKS = Set.of("straight_trunk_placer", "fancy_trunk_placer",
            "forking_trunk_placer", "giant_trunk_placer", "mega_jungle_trunk_placer", "dark_oak_trunk_placer");
    private static final Set<String> FOLIAGE = Set.of("blob_foliage_placer", "fancy_foliage_placer",
            "bush_foliage_placer", "acacia_foliage_placer", "spruce_foliage_placer", "pine_foliage_placer",
            "mega_pine_foliage_placer", "jungle_foliage_placer", "dark_oak_foliage_placer");

    record Recipe(JsonObject data, int reach) { }

    private final RegistryOps<JsonElement> ops;
    private final LegacyFeaturePredicates predicates;
    private final LegacyBlockProviders providers;
    private final ToIntFunction<BlockState> material;
    private final Set<String> approximations;

    LegacyTreeFeatures(RegistryOps<JsonElement> ops, LegacyFeaturePredicates predicates,
                       LegacyBlockProviders providers, ToIntFunction<BlockState> material, Set<String> approximations) {
        this.ops = ops;
        this.predicates = predicates;
        this.providers = providers;
        this.material = material;
        this.approximations = approximations;
    }

    Recipe tree(TreeConfiguration config) {
        var encoded = decode(TreeConfiguration.CODEC.encodeStart(ops, config)).getAsJsonObject();
        var trunk = encoded.getAsJsonObject("trunk_placer");
        var foliage = encoded.getAsJsonObject("foliage_placer");
        String trunkType = type(trunk);
        String foliageType = type(foliage);
        var geometry = new JsonObject();
        if (trunkType.equals("cherry_trunk_placer")) geometry.add("trunk", cherryTrunk(trunk));
        else if (trunkType.equals("upwards_branching_trunk_placer")) geometry.add("trunk", upwardsTrunk(trunk));
        else if (!TRUNKS.contains(trunkType)) throw new IllegalArgumentException("tree:" + trunkType);

        if (foliageType.equals("cherry_foliage_placer")) geometry.add("foliage", cherryFoliage(foliage));
        else if (foliageType.equals("random_spread_foliage_placer")) geometry.add("foliage", spreadFoliage(foliage));
        else if (!FOLIAGE.contains(foliageType)) throw new IllegalArgumentException("tree:" + foliageType);

        var data = new JsonObject();
        data.addProperty("kind", "tree");
        data.addProperty("trunk_shape", trunkType);
        data.addProperty("foliage_shape", foliageType);
        data.add("height", array(trunk.get("base_height").getAsInt(), trunk.get("height_rand_a").getAsInt(),
                trunk.get("height_rand_b").getAsInt()));
        var radius = range(foliage.get("radius"), 2);
        data.add("radius", radius);
        data.add("offset", range(foliage.get("offset"), 0));
        var height = foliage.has("crown_height") ? foliage.get("crown_height") : foliage.get("height");
        data.add("foliage_height", range(height, 4));
        data.add("trunk_height", range(foliage.get("trunk_height"), 2));
        data.add("logs", variants(encoded.getAsJsonObject("trunk_provider"), true));
        data.add("leaves", variants(encoded.getAsJsonObject("foliage_provider"), false));
        var roots = encoded.getAsJsonObject("root_placer");
        int rootReach = 0;
        if (roots == null) {
            data.addProperty("soil", material.applyAsInt(simpleState(encoded.getAsJsonObject("dirt_provider"))));
            data.addProperty("root", 0);
        } else {
            if (!type(roots).equals("mangrove_root_placer")) throw new IllegalArgumentException("tree:" + type(roots));
            var rootState = simpleState(roots.getAsJsonObject("root_provider"));
            var rootGeometry = mangroveRoots(roots, rootState);
            geometry.add("roots", rootGeometry);
            rootReach = rootGeometry.get("width").getAsInt();
            data.addProperty("soil", 0);
            data.addProperty("root", material.applyAsInt(rootState));
            approximations.add("decoration:mangrove-root-layout");
        }
        data.add("root_offset", array(0, 0));
        if (geometry.size() != 0) data.add("geometry", geometry);
        data.add("decorators", decorators(encoded.getAsJsonArray("decorators")));
        approximations.add("decoration:shared-tree-shapes-and-clearance");

        int crownReach = switch (foliageType) {
            case "cherry_foliage_placer" -> radius.get(1).getAsInt() + 1;
            case "random_spread_foliage_placer" -> radius.get(1).getAsInt() - 1;
            case "dark_oak_foliage_placer" -> Math.min(radius.get(1).getAsInt(), 4) + 4;
            case "fancy_foliage_placer", "acacia_foliage_placer",
                    "pine_foliage_placer", "spruce_foliage_placer", "mega_pine_foliage_placer" ->
                    Math.min(radius.get(1).getAsInt(), 4) + 1;
            default -> 6;
        };
        int reach = switch (trunkType) {
            case "cherry_trunk_placer" -> maximum(trunk.get("branch_horizontal_length")) + 1 + crownReach;
            case "upwards_branching_trunk_placer" -> maximum(trunk.get("extra_branch_steps")) + crownReach;
            case "fancy_trunk_placer" -> 7 + crownReach;
            case "mega_jungle_trunk_placer" -> 5 + crownReach;
            case "forking_trunk_placer", "dark_oak_trunk_placer" -> 3 + crownReach;
            case "giant_trunk_placer" -> 1 + crownReach;
            default -> crownReach;
        };
        int decoratedReach = reach + (data.getAsJsonArray("decorators").isEmpty() ? 0 : 1);
        return new Recipe(data, Math.max(rootReach, decoratedReach));
    }

    private JsonObject cherryTrunk(JsonObject trunk) {
        var result = kind("cherry");
        result.add("branch_count", integer(trunk.get("branch_count")));
        result.add("length", integer(trunk.get("branch_horizontal_length")));
        var start = trunk.getAsJsonObject("branch_start_offset_from_top");
        result.add("start", array(start.get("min_inclusive").getAsInt(), start.get("max_inclusive").getAsInt()));
        result.add("end", integer(trunk.get("branch_end_offset_from_top")));
        return result;
    }

    private JsonObject upwardsTrunk(JsonObject trunk) {
        var result = kind("upwards_branching");
        result.add("steps", integer(trunk.get("extra_branch_steps"), 1, 16));
        result.add("extra_length", integer(trunk.get("extra_branch_length"), 0, 16));
        result.add("chance", trunk.get("place_branch_per_log_probability"));
        result.add("can_grow_through", predicates.material(selection(trunk.get("can_grow_through"))));
        return result;
    }

    private JsonObject cherryFoliage(JsonObject foliage) {
        var result = kind("cherry");
        result.add("radius", integer(foliage.get("radius")));
        result.add("offset", integer(foliage.get("offset")));
        result.add("height", integer(foliage.get("height")));
        result.add("corner_hole", foliage.get("corner_hole_chance"));
        result.add("bottom_hole", foliage.get("wide_bottom_layer_hole_chance"));
        result.add("hanging", foliage.get("hanging_leaves_chance"));
        result.add("extension", foliage.get("hanging_leaves_extension_chance"));
        return result;
    }

    private JsonObject spreadFoliage(JsonObject foliage) {
        var result = kind("random_spread");
        result.add("radius", integer(foliage.get("radius"), 1, 16));
        result.add("height", integer(foliage.get("foliage_height")));
        result.add("attempts", foliage.get("leaf_placement_attempts"));
        return result;
    }

    private JsonObject mangroveRoots(JsonObject roots, BlockState rootState) {
        var placement = roots.getAsJsonObject("mangrove_root_placement");
        var result = new JsonObject();
        result.add("trunk_offset", integer(roots.get("trunk_offset_y"), 0, 16));
        result.add("width", placement.get("max_root_width"));
        result.add("length", placement.get("max_root_length"));
        result.add("skew", placement.get("random_skew_chance"));
        var growThrough = selection(placement.get("can_grow_through"));
        result.add("grow_through", predicates.material(state -> state.isAir() || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.getFluidState().is(FluidTags.WATER) || growThrough.test(state)));
        result.add("muddy_in", predicates.material(selection(placement.get("muddy_roots_in"))));
        result.add("water", predicates.material(state -> state.getFluidState().is(FluidTags.WATER)));
        result.addProperty("wet", material.applyAsInt(rootState.hasProperty(BlockStateProperties.WATERLOGGED)
                ? rootState.setValue(BlockStateProperties.WATERLOGGED, true) : rootState));
        result.addProperty("muddy", material.applyAsInt(simpleState(placement.getAsJsonObject("muddy_roots_provider"))));
        if (roots.has("above_root_placement")) {
            var placementAbove = roots.getAsJsonObject("above_root_placement");
            var above = new JsonObject();
            above.add("chance", placementAbove.get("above_root_placement_chance"));
            above.addProperty("material", material.applyAsInt(simpleState(placementAbove.getAsJsonObject("above_root_provider"))));
            result.add("above", above);
        }
        return result;
    }

    private static JsonObject kind(String kind) {
        var result = new JsonObject();
        result.addProperty("kind", kind);
        return result;
    }

    private static JsonElement integer(JsonElement value) {
        return LegacyFeaturePlacement.integer(value);
    }

    private JsonElement integer(JsonElement value, int min, int max) {
        var provider = decode(IntProvider.CODEC.parse(ops, value));
        if (provider.getMinValue() < min || provider.getMaxValue() > max) {
            throw new IllegalArgumentException("tree:integer-out-of-range:" + value);
        }
        return integer(value);
    }

    private int maximum(JsonElement value) {
        return decode(IntProvider.CODEC.parse(ops, value)).getMaxValue();
    }

    private BlockState simpleState(JsonObject provider) {
        if (!type(provider).equals("simple_state_provider")) throw new IllegalArgumentException("tree:provider:" + type(provider));
        return state(provider.get("state"));
    }

    Recipe mushroom(HugeMushroomFeatureConfiguration config, boolean red) {
        if (config.foliageRadius < 0 || config.foliageRadius > 15) {
            throw new IllegalArgumentException("huge-mushroom:radius");
        }
        var data = new JsonObject();
        data.addProperty("kind", "huge_mushroom");
        data.addProperty("red", red);
        data.addProperty("foliage_radius", config.foliageRadius);
        var cap = providers.encode(config.capProvider);
        data.add("cap", cap.data());
        data.add("stem", providers.encode(config.stemProvider).data());
        data.add("support", predicates.material(state -> state.is(BlockTags.DIRT) || state.is(BlockTags.MUSHROOM_GROW_BLOCK)));
        data.add("clearance", predicates.material(state -> state.isAir() || state.is(BlockTags.LEAVES)));
        data.add("replaceable", predicates.material(state -> !state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        var faces = new JsonArray();
        for (var base : cap.states()) {
            var variants = new JsonArray();
            for (int mask = 0; mask < 32; mask++) {
                var variant = base;
                if (base.getBlock() instanceof HugeMushroomBlock) {
                    variant = base.setValue(HugeMushroomBlock.WEST, (mask & 1) != 0)
                            .setValue(HugeMushroomBlock.EAST, (mask & 2) != 0)
                            .setValue(HugeMushroomBlock.NORTH, (mask & 4) != 0)
                            .setValue(HugeMushroomBlock.SOUTH, (mask & 8) != 0);
                    if (red) variant = variant.setValue(HugeMushroomBlock.UP, (mask & 16) != 0);
                }
                variants.add(material.applyAsInt(variant));
            }
            var row = new JsonObject();
            row.addProperty("source", material.applyAsInt(base));
            row.add("states", variants);
            faces.add(row);
        }
        data.add("faces", faces);
        return new Recipe(data, config.foliageRadius);
    }

    private JsonArray variants(JsonObject provider, boolean logs) {
        var result = new JsonArray();
        switch (type(provider)) {
            case "simple_state_provider" -> result.add(variant(state(provider.get("state")), 1, logs));
            case "weighted_state_provider" -> {
                for (var element : provider.getAsJsonArray("entries")) {
                    var entry = element.getAsJsonObject();
                    result.add(variant(state(entry.get("data")), entry.get("weight").getAsInt(), logs));
                }
            }
            default -> throw new IllegalArgumentException("tree:provider:" + type(provider));
        }
        return result;
    }

    private JsonObject variant(BlockState state, int weight, boolean logs) {
        var result = new JsonObject();
        var states = new JsonArray();
        if (logs) {
            for (var axis : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z)) {
                states.add(material.applyAsInt(state.hasProperty(BlockStateProperties.AXIS)
                        ? state.setValue(BlockStateProperties.AXIS, axis) : state));
            }
        } else {
            for (int distance = 1; distance <= 6; distance++) {
                states.add(material.applyAsInt(state.hasProperty(BlockStateProperties.DISTANCE)
                        ? state.setValue(BlockStateProperties.DISTANCE, distance) : state));
            }
        }
        result.add(logs ? "axes" : "distances", states);
        result.addProperty("weight", weight);
        return result;
    }

    private JsonArray decorators(JsonArray input) {
        var result = new JsonArray();
        for (var element : input) {
            var decorator = element.getAsJsonObject();
            String type = type(decorator);
            if (!Set.of("trunk_vine", "leave_vine", "cocoa").contains(type)) {
                approximations.add("decoration:tree-decorator:" + type);
                continue;
            }
            var row = new JsonObject();
            row.addProperty("kind", type.equals("leave_vine") ? "leaf_vine" : type);
            row.addProperty("probability", type.equals("trunk_vine") ? 2.0 / 3.0 : decorator.get("probability").getAsDouble());
            var states = new JsonArray();
            for (var direction : List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)) {
                if (type.equals("cocoa")) {
                    for (int age = 0; age < 3; age++) {
                        states.add(material.applyAsInt(Blocks.COCOA.defaultBlockState()
                                .setValue(CocoaBlock.FACING, direction).setValue(CocoaBlock.AGE, age)));
                    }
                } else {
                    states.add(material.applyAsInt(Blocks.VINE.defaultBlockState()
                            .setValue(VineBlock.getPropertyForFace(direction), true)));
                }
            }
            row.add("states", states);
            result.add(row);
        }
        return result;
    }

    private BlockState state(JsonElement value) {
        return decode(BlockState.CODEC.parse(ops, value));
    }

    private JsonArray range(JsonElement encoded, int fallback) {
        if (encoded == null) return array(fallback, fallback);
        var provider = decode(IntProvider.CODEC.parse(ops, encoded));
        return array(provider.getMinValue(), provider.getMaxValue());
    }
}
