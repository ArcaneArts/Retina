package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.*;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

import java.util.*;

/** Resolves registry feature rules once; native generation never calls back into Java per block. */
final class GeologyProfile {
    private record Pending(AbstractOreFeature feature, JsonObject recipe) { }
    static void export(HolderLookup.Provider registry, List<Holder<Biome>> biomes, JsonArray profiles,
                       LinkedHashMap<BlockState, Integer> palette, JsonObject world, int minY, int height, int sea) {
        var recipes = new JsonArray();
        var pending = new ArrayList<Pending>();
        var ids = new LinkedHashMap<String, Integer>();
        var omitted = new TreeSet<String>();
        var gpuSediments = new HashSet<String>();
        for (var id : world.getAsJsonObject("registry_program").getAsJsonArray("shore_features")) gpuSediments.add(id.getAsString());
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        for (int i = 0; i < biomes.size(); i++) {
            var oreIds = new JsonArray();
            // Sediment/stone replacement features can appear outside UNDERGROUND_ORES.
            for (var step : biomes.get(i).value().getGenerationSettings().features()) for (var holder : step) {
                var placed = holder.value();
                if (!(placed.feature().value() instanceof AbstractOreFeature ore)) continue;
                String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline/" + placed.hashCode());
                if (gpuSediments.contains(id)) continue;
                Integer index = ids.get(id);
                if (index == null) {
                    var recipe = new JsonObject();
                    recipe.addProperty("id", id);
                    recipe.addProperty("size", ore.size());
                    recipe.addProperty("discard", ore.discardChanceOnAirExposure());
                    recipe.addProperty("scattered", ore instanceof ScatteredOreFeature);
                    int countMin = 1, countMax = 1, rarity = 1;
                    JsonObject range = null;
                    boolean supported = true;
                    for (var modifier : placed.placement()) {
                        var data = PlacementModifier.CODEC.encodeStart(ops, modifier).getOrThrow().getAsJsonObject();
                        switch (type(data)) {
                            case "count" -> {
                                var value = data.get("count");
                                if (value.isJsonPrimitive()) countMin = countMax = value.getAsInt();
                                else if (type(value.getAsJsonObject()).equals("uniform")) {
                                    countMin = value.getAsJsonObject().get("min_inclusive").getAsInt();
                                    countMax = value.getAsJsonObject().get("max_inclusive").getAsInt();
                                } else { omitted.add(id + ":count"); supported = false; }
                            }
                            case "rarity_filter" -> rarity = data.get("chance").getAsInt();
                            case "height_range" -> range = range(data.get("height"), minY, height, sea);
                            case "biome", "in_square" -> { }
                            default -> { omitted.add(id + ":" + type(data)); supported = false; }
                        }
                    }
                    // Random/location-dependent replacement rules cannot become a static palette table.
                    for (var target : ore.targetStates()) {
                        if (!supportedRule(RuleTest.CODEC.encodeStart(ops, target.target()).getOrThrow().getAsJsonObject())) {
                            omitted.add(id + ":replacement-rule"); supported = false;
                        }
                    }
                    if (!supported || range == null) { omitted.add(id + ":placement"); continue; }
                    recipe.addProperty("count_min", countMin); recipe.addProperty("count_max", countMax);
                    recipe.addProperty("rarity", rarity); recipe.add("height", range);
                    for (var target : ore.targetStates()) material(palette, target.state());
                    index = recipes.size(); ids.put(id, index); recipes.add(recipe); pending.add(new Pending(ore, recipe));
                }
                oreIds.add(index);
            }
            profiles.get(i).getAsJsonObject().add("ores", oreIds);
            var carvers = new JsonArray();
            for (var holder : biomes.get(i).value().getGenerationSettings().getCarvers()) {
                var c = new JsonObject();
                c.addProperty("id", holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline"));
                if (holder.value() instanceof CaveWorldCarver cave) {
                    c.addProperty("kind", 0); c.addProperty("probability", cave.probability());
                    c.add("height", range(HeightProvider.CODEC.encodeStart(ops, cave.y()).getOrThrow(), minY, height, sea));
                    c.addProperty("count", (cave.count().minInclusive() + cave.count().maxInclusive()) * 0.5);
                    c.addProperty("thickness", mean(cave.thickness()));
                    c.addProperty("horizontal", mean(cave.horizontalRadiusMultiplier()));
                    c.addProperty("vertical", mean(cave.verticalRadiusMultiplier()));
                    c.addProperty("floor", mean(cave.floorLevel()));
                    c.addProperty("room", mean(cave.roomVerticalRadiusMultiplier()));
                } else if (holder.value() instanceof CanyonWorldCarver canyon) {
                    c.addProperty("kind", 1); c.addProperty("probability", canyon.probability());
                    c.add("height", range(HeightProvider.CODEC.encodeStart(ops, canyon.y()).getOrThrow(), minY, height, sea));
                    c.addProperty("count", 1); c.addProperty("thickness", mean(canyon.shape().thickness()));
                    c.addProperty("horizontal", mean(canyon.shape().horizontalRadiusFactor()));
                    c.addProperty("vertical", mean(canyon.shape().yScale()));
                    c.addProperty("floor", -1); c.addProperty("room", mean(canyon.shape().distanceFactor()));
                    c.addProperty("width_smoothness",canyon.shape().widthSmoothness());
                    c.addProperty("vertical_default",canyon.shape().verticalRadiusDefaultFactor());
                    c.addProperty("vertical_center",canyon.shape().verticalRadiusCenterFactor());
                    c.add("thickness_range", floatRange(canyon.shape().thickness()));
                    c.add("horizontal_range", floatRange(canyon.shape().horizontalRadiusFactor()));
                    c.add("distance_range", floatRange(canyon.shape().distanceFactor()));
                    c.add("vertical_range", floatRange(canyon.shape().yScale()));
                    c.add("rotation_range", floatRange(canyon.verticalRotation()));
                } else { omitted.add("carver:" + holder.value().getClass().getSimpleName()); continue; }
                carvers.add(c);
            }
            profiles.get(i).getAsJsonObject().add("carvers", carvers);
        }
        world.addProperty("lava", material(palette, Blocks.LAVA.defaultBlockState()));
        // Same global lava boundary as 26.3 NoiseBasedChunkGenerator's fluid picker.
        world.addProperty("lava_level", Math.min(-54, sea));
        world.addProperty("geology_min_y", minY); world.addProperty("geology_height", height);
        var caveNoises = new JsonArray();
        for (String id : List.of("cave_cheese", "spaghetti_3d_1", "spaghetti_3d_2", "cave_layer", "spaghetti_roughness", "pillar")) {
            var noise = registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE, Identifier.withDefaultNamespace(id))).value();
            var data = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, noise).getOrThrow().getAsJsonObject();
            var n = new JsonObject(); n.addProperty("frequency", Math.scalb(1.0, data.get("base_octave").getAsInt()));
            n.addProperty("amplitude", (data.has("base_amplitude") ? data.get("base_amplitude").getAsDouble() : 1.0));
            var modifiers = new JsonArray(); var supplied = data.getAsJsonArray("amplitude_modifiers");
            for (int j = 0; j < (data.has("octave_count") ? data.get("octave_count").getAsInt() : 1); j++) modifiers.add(supplied != null && j < supplied.size() ? supplied.get(j).getAsDouble() : 1.0);
            n.add("modifiers", modifiers); caveNoises.add(n);
        }
        world.add("cave_noises", caveNoises);
        // Structure/geology exporters have now reserved their material states.
        // Close contextual provider transforms before freezing dense ore and
        // cave predicates, so late registered substrates participate as well.
        ProviderStateTransforms.prepare(world.getAsJsonArray("decorations"),palette);
        DecorationProfile.reserveCurrentSurvival(world,palette);
        var carveable = new JsonArray();
        for (var state : palette.keySet()) carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.UNCARVABLE));
        world.add("carveable", carveable);
        var states = palette.keySet().toArray(BlockState[]::new);
        var random = RandomSource.create(0);
        for (var item : pending) {
            var bands = new JsonArray();
            int[] previous = null;
            JsonObject current = null;
            for (int y = minY; y < minY + height; y++) {
                var pos = new BlockPos(0, y, 0);
                int[] replacements = new int[states.length];
                for (int m = 0; m < states.length; m++) for (var target : item.feature.targetStates()) {
                    if (target.target().test(states[m], pos, random)) { replacements[m] = palette.get(target.state()); break; }
                }
                if (!Arrays.equals(previous, replacements)) {
                    current = new JsonObject(); current.addProperty("min", y);
                    var values = new JsonArray(); for (int replacement : replacements) values.add(replacement);
                    current.add("materials", values); bands.add(current); previous = replacements;
                }
                current.addProperty("max", y);
            }
            item.recipe.add("replacement_bands", bands);
        }
        world.add("ores", recipes);
        Retina.LOGGER.info("Exported {} registered ore recipes and GPU cave profiles; omitted geology: {}", recipes.size(), omitted);
    }
    private static boolean supportedRule(JsonObject rule) {
        String type = rule.get("predicate_type").getAsString().replace("minecraft:", "");
        return switch (type) {
            case "tag_match", "block_match", "height_match" -> true;
            case "all_of", "any_of" -> {
                boolean supported = true;
                for (var child : rule.getAsJsonArray("rules")) supported &= supportedRule(child.getAsJsonObject());
                yield supported;
            }
            case "not" -> supportedRule(rule.getAsJsonObject("rule"));
            default -> false;
        };
    }
    private static String type(JsonObject object) { return object.has("type") ? object.get("type").getAsString().replace("minecraft:", "") : "constant"; }
    private static JsonArray floatRange(FloatProvider provider) {var a=new JsonArray();a.add(provider.min());a.add(provider.max());return a;}
    private static double mean(FloatProvider provider) { return (provider.min() + provider.max()) * 0.5; }
    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) { return palette.computeIfAbsent(state, ignored -> palette.size()); }
    private static int anchor(JsonElement value, int min, int height, int sea) {
        var a = value.getAsJsonObject();
        if (a.has("absolute")) return a.get("absolute").getAsInt();
        if (a.has("above_bottom")) return min + a.get("above_bottom").getAsInt();
        if (a.has("below_top")) return min + height - 1 - a.get("below_top").getAsInt();
        if (a.has("relative_to_sea_level")) return sea + a.get("relative_to_sea_level").getAsInt();
        throw new IllegalArgumentException("Unsupported geology anchor: " + a);
    }
    static JsonObject range(JsonElement value, int min, int height, int sea) {
        var data = value.getAsJsonObject(); var result = new JsonObject(); String kind = type(data);
        if (kind.equals("constant")) {
            int y = anchor(data.has("value") ? data.get("value") : data, min, height, sea);
            result.addProperty("min", y); result.addProperty("max", y);
        } else if (kind.equals("uniform") || kind.equals("trapezoid")) {
            result.addProperty("min", anchor(data.get("min_inclusive"), min, height, sea));
            result.addProperty("max", anchor(data.get("max_inclusive"), min, height, sea));
        } else throw new IllegalArgumentException("Unsupported geology height distribution: " + data);
        result.addProperty("triangle", kind.equals("trapezoid"));
        result.addProperty("plateau", data.has("plateau") ? data.get("plateau").getAsInt() : 0);
        return result;
    }
}
