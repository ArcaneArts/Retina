package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.placement.*;

import java.util.*;

/** Projects registered vegetation features into compact Rust recipes, once per world. */
final class DecorationProfile {
    private final HolderLookup.Provider registry;
    private final LinkedHashMap<BlockState, Integer> materials;
    private final JsonArray recipes = new JsonArray();
    private final Map<String, Integer> ids = new LinkedHashMap<>();
    private final Set<String> unsupported = new TreeSet<>();
    private DecorationProfile(HolderLookup.Provider registry, LinkedHashMap<BlockState, Integer> materials) {
        this.registry = registry;
        this.materials = materials;
    }
    static JsonArray export(HolderLookup.Provider registry, List<Holder<Biome>> biomes, JsonArray profiles, LinkedHashMap<BlockState, Integer> materials) {
        var exporter = new DecorationProfile(registry, materials);
        for (int i = 0; i < biomes.size(); i++) {
            var selected = new JsonArray();
            if ((profiles.get(i).getAsJsonObject().get("flags").getAsInt() & 16) != 0) { profiles.get(i).getAsJsonObject().add("decorations", selected); continue; }
            var features = biomes.get(i).value().getGenerationSettings().features();
            int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
            if (features.size() > step) for (var holder : features.get(step)) {
                String name = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline/" + selected.size());
                var before = new ArrayList<Integer>();
                exporter.placed(holder.value(), new Placement(), 1, name, before, 0);
                for (int id : before) selected.add(id);
            }
            profiles.get(i).getAsJsonObject().add("decorations", selected);
        }
        Retina.LOGGER.info("Exported {} registered decoration recipes; omitted feature kinds: {}", exporter.recipes.size(), exporter.unsupported);
        return exporter.recipes;
    }

    private static final class Placement {
        double density = 1, lowDensity = 1, rarity = 1, tries = 1;
        int xSpread = 0, ySpread = 0, zSpread = 0;
        boolean surface = false, noiseCount = false;
        JsonArray waterOffsets = new JsonArray();
        Placement copy() {
            var p = new Placement(); p.density = density; p.lowDensity = lowDensity; p.rarity = rarity; p.tries = tries;
            p.xSpread = xSpread; p.ySpread = ySpread; p.zSpread = zSpread; p.surface = surface; p.noiseCount = noiseCount; p.waterOffsets = waterOffsets.deepCopy(); return p;
        }
    }
    private void placed(PlacedFeature placed, Placement previous, double chance, String path, List<Integer> selected, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Recursive decoration feature: " + path);
        var p = previous.copy();
        for (var modifier : placed.placement()) {
            var json = PlacementModifier.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), modifier).getOrThrow().getAsJsonObject();
            switch (type(json)) {
                case "count" -> {
                    double n = mean(json.get("count"));
                    if (p.surface) p.tries *= n;
                    else { p.density *= n; p.lowDensity *= n; }
                }
                case "noise_threshold_count" -> {
                    if (Math.abs(json.get("noise_level").getAsDouble() + 0.8) > 0.00001) { unsupported.add("noise_threshold_count=" + json.get("noise_level")); return; }
                    p.density *= json.get("above_noise").getAsDouble();
                    p.lowDensity *= json.get("below_noise").getAsDouble(); p.noiseCount = true;
                }
                case "noise_based_count" -> { unsupported.add("noise_based_count"); return; }
                case "rarity_filter" -> p.rarity *= json.get("chance").getAsDouble();
                case "heightmap" -> p.surface = true;
                case "offset" -> { p.xSpread += extent(json.get("x")); p.ySpread += extent(json.get("y")); p.zSpread += extent(json.get("z")); }
                case "block_predicate_filter" -> waterOffsets(json.getAsJsonObject("predicate"), p.waterOffsets);
                case "in_square", "biome", "surface_water_depth_filter" -> { }
                default -> { unsupported.add("placement:" + type(json)); return; }
            }
        }
        var feature = placed.feature().value();
        if (feature instanceof RandomSelectorFeature random) {
            double remaining = chance;
            for (int i = 0; i < random.features().size(); i++) {
                var option = random.features().get(i);
                placed(option.feature().value(), p, remaining * option.chance(), path + "/choice" + i, selected, depth + 1);
                remaining *= 1 - option.chance();
            }
            placed(random.defaultFeature().value(), p, remaining, path + "/default", selected, depth + 1);
        } else if (feature instanceof SimpleRandomSelectorFeature random) {
            for (int i = 0; i < random.features().size(); i++) placed(random.features().get(i).value(), p, chance / random.features().size(), path + "/choice" + i, selected, depth + 1);
        } else if (feature instanceof RandomBooleanSelectorFeature random) {
            placed(random.featureTrue().value(), p, chance * 0.5, path + "/true", selected, depth + 1);
            placed(random.featureFalse().value(), p, chance * 0.5, path + "/false", selected, depth + 1);
        } else if (feature instanceof WeightedRandomSelectorFeature random) {
            double total = random.features().unwrap().stream().mapToInt(w -> w.weight()).sum();
            int i = 0;
            for (var w : random.features().unwrap()) placed(w.value().value(), p, chance * w.weight() / total, path + "/choice" + i++, selected, depth + 1);
        } else if (feature instanceof TreeFeature tree) {
            var data = tree(tree);
            if (data != null) emit(path, p, chance, "tree", data, selected);
        } else if (feature instanceof SimpleBlockFeature block) {
            var states = provider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), block.toPlace().value()).getOrThrow(), 1, 0, 0);
            var variants = new JsonArray();
            for (var state : states) {
                if (!(state.state.getBlock() instanceof VegetationBlock) || state.state.getBlock() instanceof SaplingBlock) continue;
                if (!state.state.getFluidState().isEmpty() || state.state.is(Blocks.LILY_PAD)) continue;
                if (state.state.getBlock() instanceof MushroomBlock) continue; // Their light/underground rules need a separate adapter.
                var variant = new JsonObject();
                var lower = state.state;
                int upper = 0;
                if (lower.getBlock() instanceof DoublePlantBlock) {
                    lower = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    upper = material(lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
                }
                variant.addProperty("lower", material(lower)); variant.addProperty("upper", upper);
                variant.addProperty("weight", state.weight); variant.addProperty("band", state.band);
                variant.addProperty("dry", lower.getBlock() instanceof DryVegetationBlock);
                variants.add(variant);
            }
            if (!variants.isEmpty()) { var data = new JsonObject(); data.add("states", variants); emit(path, p, chance, "plant", data, selected); }
            else unsupported.add(feature.getClass().getSimpleName() + ":non-surface-plant");
        } else unsupported.add(feature.getClass().getSimpleName());
    }

    private static void waterOffsets(JsonObject predicate, JsonArray offsets) {
        if (type(predicate).equals("all_of")) {
            for (var child : predicate.getAsJsonArray("predicates")) waterOffsets(child.getAsJsonObject(), offsets);
        } else if (type(predicate).equals("matching_fluids")) {
            boolean water = false;
            var fluids = predicate.get("fluids");
            var choices = new JsonArray();
            if (fluids.isJsonArray()) choices = fluids.getAsJsonArray(); else choices.add(fluids);
            for (var fluid : choices) water |= List.of("minecraft:water", "minecraft:flowing_water", "#minecraft:water").contains(fluid.getAsString());
            if (water) offsets.add(predicate.has("offset") ? predicate.get("offset").deepCopy() : new Gson().toJsonTree(new int[]{0, 0, 0}));
        } else if (type(predicate).equals("any_of")) {
            var alternatives = predicate.getAsJsonArray("predicates");
            if (alternatives.asList().stream().allMatch(p -> type(p.getAsJsonObject()).equals("matching_fluids")))
                for (var child : alternatives) waterOffsets(child.getAsJsonObject(), offsets);
        }
    }

    private void emit(String path, Placement p, double chance, String kind, JsonObject data, List<Integer> selected) {
        Integer id = ids.get(path);
        if (id == null) {
            id = recipes.size(); ids.put(path, id);
            data.addProperty("source", path); data.addProperty("salt", salt(path)); data.addProperty("kind", kind);
            data.addProperty("density", p.density * chance); data.addProperty("low_density", p.lowDensity * chance);
            data.add("water_offsets", p.waterOffsets.deepCopy());
            data.addProperty("noise_count", p.noiseCount); data.addProperty("rarity", p.rarity);
            data.addProperty("tries", Math.max(1, p.tries));
            var spread = new JsonArray(); spread.add(p.xSpread); spread.add(p.ySpread); spread.add(p.zSpread); data.add("spread", spread);
            recipes.add(data);
        }
        selected.add(id);
    }

    private JsonObject tree(TreeFeature tree) {
        var json = Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), tree).getOrThrow().getAsJsonObject();
        var trunk = json.getAsJsonObject("trunk_placer"); var foliage = json.getAsJsonObject("foliage_placer");
        var logs = provider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), tree.trunkProvider().value()).getOrThrow(), 1, 0, 0);
        var leaves = provider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), tree.foliageProvider().value()).getOrThrow(), 1, 0, 0);
        if (logs.isEmpty() || leaves.isEmpty()) return null;
        var result = new JsonObject();
        result.addProperty("trunk_shape", type(trunk)); result.addProperty("foliage_shape", type(foliage));
        var heights = new JsonArray(); heights.add(trunk.get("base_height")); heights.add(trunk.get("height_rand_a")); heights.add(trunk.get("height_rand_b")); result.add("height", heights);
        result.add("radius", range(foliage.get("radius"), 2)); result.add("offset", range(foliage.get("offset"), 0));
        result.add("foliage_height", range(foliage.has("height") ? foliage.get("height") : foliage.get("foliage_height"), 4));
        result.add("trunk_height", range(foliage.get("trunk_height"), 2));
        var logStates = new JsonArray();
        for (var state : logs) {
            var entry = new JsonObject(); var axes = new JsonArray();
            for (var axis : List.of(Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z)) axes.add(material(state.state.hasProperty(BlockStateProperties.AXIS) ? state.state.setValue(BlockStateProperties.AXIS, axis) : state.state));
            entry.add("axes", axes); entry.addProperty("weight", state.weight); logStates.add(entry);
        }
        result.add("logs", logStates);
        var leafStates = new JsonArray();
        for (var state : leaves) {
            var entry = new JsonObject(); var distances = new JsonArray();
            for (int distance = 1; distance <= 6; distance++) distances.add(material(state.state.hasProperty(BlockStateProperties.DISTANCE) ? state.state.setValue(BlockStateProperties.DISTANCE, distance) : state.state));
            entry.add("distances", distances); entry.addProperty("weight", state.weight); leafStates.add(entry);
        }
        result.add("leaves", leafStates);
        var soil = provider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), tree.belowTrunkProvider().value()).getOrThrow(), 1, 0, 0);
        result.addProperty("soil", soil.isEmpty() ? 0 : material(soil.getFirst().state));
        if (tree.rootPlacer().isPresent()) {
            var root = json.getAsJsonObject("root_placer");
            result.add("root_offset", range(root.get("trunk_offset_y"), 0));
            var roots = provider(root.get("root_provider"), 1, 0, 0);
            result.addProperty("root", roots.isEmpty() ? 0 : material(roots.getFirst().state));
        } else { result.add("root_offset", range(null, 0)); result.addProperty("root", 0); }
        var decorators = new JsonArray();
        for (var element : json.getAsJsonArray("decorators")) {
            var decorator = element.getAsJsonObject();
            String type = type(decorator);
            if (!List.of("trunk_vine", "leave_vine", "cocoa").contains(type)) {
                unsupported.add("tree_decorator:" + type); continue;
            }
            var exported = new JsonObject();
            exported.addProperty("kind", type.equals("leave_vine") ? "leaf_vine" : type);
            exported.addProperty("probability", type.equals("trunk_vine") ? 2.0 / 3.0 : decorator.get("probability").getAsDouble());
            var states = new JsonArray();
            // Faces point from the decoration back toward its supporting tree block.
            for (var direction : List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)) {
                if (type.equals("cocoa")) {
                    for (int age = 0; age < 3; age++) states.add(material(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, direction).setValue(CocoaBlock.AGE, age)));
                } else states.add(material(Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(direction), true)));
            }
            exported.add("states", states); decorators.add(exported);
        }
        result.add("decorators", decorators);
        return result;
    }

    private record State(BlockState state, double weight, int band) { }
    private List<State> provider(JsonElement json, double weight, int band, int depth) {
        if (json == null || depth > 16) return List.of();
        if (json.isJsonPrimitive()) {
            var key = ResourceKey.create(Registries.BLOCK_STATE_PROVIDER, Identifier.parse(json.getAsString()));
            var holder = registry.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(key);
            if (holder.isPresent()) return provider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), holder.get().value()).getOrThrow(), weight, band, depth + 1);
        }
        var state = BlockState.CODEC.parse(JsonOps.INSTANCE, json).result();
        if (state.isPresent()) return List.of(new State(state.get(), weight, band));
        if (!json.isJsonObject()) return List.of();
        var object = json.getAsJsonObject(); var result = new ArrayList<State>();
        switch (type(object)) {
            case "weighted", "weighted_state_provider" -> { for (var e : object.getAsJsonArray("entries")) { var entry = e.getAsJsonObject(); result.addAll(provider(entry.get("data"), weight * entry.get("weight").getAsDouble(), band, depth + 1)); } }
            case "noise_threshold" -> {
                var low = object.getAsJsonArray("low_states"); var high = object.getAsJsonArray("high_states");
                for (var e : low) result.addAll(provider(e, weight / low.size(), 1, depth + 1));
                double chance = object.get("high_chance").getAsDouble();
                result.addAll(provider(object.get("default_state"), weight * (1 - chance), 2, depth + 1));
                for (var e : high) result.addAll(provider(e, weight * chance / high.size(), 2, depth + 1));
            }
            case "noise_provider", "dual_noise_provider" -> { for (var e : object.getAsJsonArray("states")) result.addAll(provider(e, weight, band, depth + 1)); }
            case "rule_based" -> { for (var e : object.getAsJsonArray("rules")) result.addAll(provider(e.getAsJsonObject().get("then"), weight, band, depth + 1)); }
            case "simple_state_provider" -> result.addAll(provider(object.get("state"), weight, band, depth + 1));
            case "randomized_int", "randomized_int_state_provider" -> result.addAll(provider(object.get("source"), weight, band, depth + 1));
            default -> unsupported.add("provider:" + type(object));
        }
        return result;
    }
    private int material(BlockState state) { return materials.computeIfAbsent(state, ignored -> materials.size()); }
    private static String type(JsonObject object) { return object.has("type") ? object.get("type").getAsString().replace("minecraft:", "") : "unknown"; }
    private static long salt(String text) { long hash = 0xcbf29ce484222325L; for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) hash = (hash ^ Byte.toUnsignedInt(b)) * 0x100000001b3L; return hash; }
    private static int extent(JsonElement value) { var r = range(value, 0); return Math.max(Math.abs(r.get(0).getAsInt()), Math.abs(r.get(1).getAsInt())); }
    private static JsonArray range(JsonElement value, int fallback) {
        int min = fallback, max = fallback;
        if (value != null) {
            if (value.isJsonPrimitive()) min = max = value.getAsInt();
            else { var object = value.getAsJsonObject(); min = object.has("min_inclusive") ? object.get("min_inclusive").getAsInt() : object.has("min") ? object.get("min").getAsInt() : (int)mean(value); max = object.has("max_inclusive") ? object.get("max_inclusive").getAsInt() : object.has("max") ? object.get("max").getAsInt() : min; }
        }
        var result = new JsonArray(); result.add(min); result.add(max); return result;
    }
    private static double mean(JsonElement value) {
        if (value.isJsonPrimitive()) return value.getAsDouble();
        var object = value.getAsJsonObject();
        if (object.has("distribution")) { double sum = 0, weight = 0; for (var e : object.getAsJsonArray("distribution")) { var w = e.getAsJsonObject(); double n = w.get("weight").getAsDouble(); sum += n * mean(w.get("data")); weight += n; } return sum / weight; }
        if (type(object).equals("clamped")) {
            double min = object.get("min_inclusive").getAsDouble(), max = object.get("max_inclusive").getAsDouble();
            var source = object.getAsJsonObject("source");
            if (type(source).equals("uniform")) { int low = source.get("min_inclusive").getAsInt(), high = source.get("max_inclusive").getAsInt(); double sum = 0; for (int i = low; i <= high; i++) sum += Math.clamp(i, min, max); return sum / (high - low + 1); }
            return Math.clamp(mean(source), min, max);
        }
        if (object.has("min_inclusive")) return (object.get("min_inclusive").getAsDouble() + object.get("max_inclusive").getAsDouble()) / 2;
        if (object.has("min")) return (object.get("min").getAsDouble() + object.get("max").getAsDouble()) / 2;
        throw new IllegalArgumentException("Unsupported decoration count: " + value);
    }

    static void materialFlags(JsonObject profile, LinkedHashMap<BlockState, Integer> palette) {
        var heightmaps = new JsonArray(); var flags = new JsonArray();
        for (var state : palette.keySet()) {
            int mask = 0; for (var type : Heightmap.Types.values()) if (type.isOpaque().test(state)) mask |= 1 << type.ordinal();
            heightmaps.add(mask);
            int flag = state.is(BlockTags.SUPPORTS_VEGETATION) ? 1 : 0;
            if (state.is(BlockTags.SUPPORTS_DRY_VEGETATION)) flag |= 2;
            if (state.is(BlockTags.LEAVES)) flag |= 4;
            if (state.is(BlockTags.LOGS)) flag |= 8;
            if (state.getBlock() instanceof VegetationBlock) flag |= 16;
            if (state.is(BlockTags.JUNGLE_LOGS)) flag |= 32;
            flags.add(flag);
        }
        profile.add("heightmap_masks", heightmaps); profile.add("material_flags", flags);
    }
}
