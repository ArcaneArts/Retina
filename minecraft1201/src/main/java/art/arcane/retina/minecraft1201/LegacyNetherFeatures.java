package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.HugeFungusConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.DeltaFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ReplaceSphereConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Translate loaded Nether features to the shared ordered decoration engine. */
final class LegacyNetherFeatures {
    private record Primitive(JsonObject data, int reach) { }

    private final RegistryOps<JsonElement> ops;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final WorldGenerationContext context;
    private final LegacyFeaturePredicates predicates = new LegacyFeaturePredicates();
    private final LinkedHashMap<String, Integer> fluidTicks = new LinkedHashMap<>();

    private LegacyNetherFeatures(RegistryAccess registries, LinkedHashMap<BlockState, Integer> palette,
                                 WorldGenerationContext context) {
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.palette = palette;
        this.context = context;
    }

    static LegacyFeaturePredicates export(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                                          JsonArray biomeEntries, LinkedHashMap<BlockState, Integer> palette,
                                          WorldGenerationContext context, JsonObject world) {
        var exporter = new LegacyNetherFeatures(registries, palette, context);
        exporter.exportRoots(registries, sourceOrder, biomes, biomeEntries, world);
        return exporter.predicates;
    }

    private void exportRoots(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                             JsonArray biomeEntries, JsonObject world) {
        var features = registries.registryOrThrow(Registries.PLACED_FEATURE);
        var steps = FeatureSorter.buildFeaturesPerStep(sourceOrder, biome -> biome.value().getGenerationSettings().features(), true);
        var recipes = new JsonArray();
        var omissions = new TreeSet<String>();
        for (var entry : biomeEntries) entry.getAsJsonObject().add("decorations", new JsonArray());
        int order = 0;
        for (int step = 0; step < steps.size(); step++) {
            var roots = steps.get(step);
            for (var root : roots.features()) {
                int rootOrder = order++;
                var configured = root.feature().value();
                var feature = configured.feature();
                if (feature == Feature.ORE || feature == Feature.SCATTERED_ORE) continue;
                int index = roots.indexMapping().applyAsInt(root);
                String source = features.getResourceKey(root).map(key -> key.location().toString())
                        .orElse("inline/nether/" + step + "/" + index);
                try {
                    var placement = LegacyFeaturePlacement.encode(root, ops);
                    if (!LegacyFeaturePlacement.bindNether(placement, context)) throw new IllegalArgumentException("placement");
                    var primitive = primitive(configured);
                    for (int biome = 0; biome < biomes.size(); biome++) {
                        var settings = biomes.get(biome).value().getGenerationSettings().features();
                        if (step < settings.size() && settings.get(step).stream().anyMatch(holder -> holder.value() == root)) {
                            biomeEntries.get(biome).getAsJsonObject().getAsJsonArray("decorations").add(recipes.size());
                        }
                    }
                    recipes.add(recipe(source, step, rootOrder, placement, primitive));
                } catch (IllegalArgumentException unsupported) {
                    omissions.add(source + ":" + unsupported.getMessage());
                }
            }
        }
        var report = new JsonArray();
        omissions.forEach(report::add);
        var ticks = new JsonArray();
        fluidTicks.forEach((id, index) -> {
            var tick = new JsonObject();
            tick.addProperty("id", id);
            tick.addProperty("kind", "fluid");
            ticks.add(tick);
        });
        world.add("decorations", recipes);
        world.add("decoration_omissions", report);
        world.add("decoration_ticks", ticks);
        world.addProperty("ordered_decorations", true);
        world.addProperty("decoration_source_order", true);
        world.addProperty("decoration_biome_3d", true);
    }

    private Primitive primitive(ConfiguredFeature<?, ?> configured) {
        var feature = configured.feature();
        if (feature == Feature.SPRING) return spring((SpringConfiguration) configured.config());
        if (feature == Feature.DELTA_FEATURE) return delta((DeltaFeatureConfiguration) configured.config());
        if (feature == Feature.REPLACE_BLOBS) return blobs((ReplaceSphereConfiguration) configured.config());
        if (feature == Feature.HUGE_FUNGUS) return fungus((HugeFungusConfiguration) configured.config());
        if (feature == Feature.GLOWSTONE_BLOB) return glowstone();
        throw new IllegalArgumentException(BuiltInRegistries.FEATURE.getKey(feature).toString());
    }

    private Primitive spring(SpringConfiguration config) {
        var data = kind("spring");
        data.addProperty("state", material(config.state.createLegacyBlock()));
        data.addProperty("requires_block_below", config.requiresBlockBelow);
        data.addProperty("rock_count", config.rockCount);
        data.addProperty("hole_count", config.holeCount);
        data.add("valid_blocks", predicates.material(state -> config.validBlocks.contains(state.getBlock().builtInRegistryHolder())));
        data.add("air", predicates.material(BlockState::isAir));
        String fluid = BuiltInRegistries.FLUID.getKey(config.state.getType()).toString();
        data.addProperty("tick", fluidTicks.computeIfAbsent(fluid, key -> fluidTicks.size() + 1));
        return new Primitive(data, 0);
    }

    private Primitive delta(DeltaFeatureConfiguration config) {
        int reach = config.size().getMaxValue() + config.rimSize().getMaxValue();
        if (reach > 15) throw new IllegalArgumentException("delta:geometry");
        var data = kind("delta");
        data.addProperty("contents", material(config.contents()));
        data.addProperty("rim", material(config.rim()));
        data.add("size", integer(config.size()));
        data.add("rim_size", integer(config.rimSize()));
        data.add("blocked", predicates.blocks(array(0, 0, 0), Blocks.BEDROCK, Blocks.NETHER_BRICKS,
                Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS, Blocks.NETHER_WART,
                Blocks.CHEST, Blocks.SPAWNER, config.contents().getBlock()));
        data.add("air", predicates.material(BlockState::isAir));
        return new Primitive(data, reach);
    }

    private Primitive blobs(ReplaceSphereConfiguration config) {
        var data = kind("replace_blobs");
        data.add("target", predicates.blocks(array(0, 0, 0), config.targetState.getBlock()));
        data.addProperty("state", material(config.replaceState));
        data.add("radius", integer(config.radius()));
        return new Primitive(data, config.radius().getMaxValue());
    }

    private Primitive fungus(HugeFungusConfiguration config) {
        var data = kind("huge_fungus");
        data.add("replaceable_blocks", predicates.compile(
                decode(BlockPredicate.CODEC.encodeStart(ops, config.replaceableBlocks)).getAsJsonObject()));
        data.add("base", predicates.blocks(array(0, 0, 0), config.validBaseState.getBlock()));
        data.add("hat_block", predicates.blocks(array(0, 0, 0), config.hatState.getBlock()));
        data.add("replaceable", predicates.material(BlockState::canBeReplaced));
        data.add("air", predicates.material(BlockState::isAir));
        data.addProperty("stem_state", material(config.stemState));
        data.addProperty("hat_state", material(config.hatState));
        data.addProperty("decor_state", material(config.decorState));
        data.addProperty("air_state", material(Blocks.AIR.defaultBlockState()));
        data.addProperty("planted", config.planted);
        data.addProperty("generator_depth", context.getGenDepth());
        data.addProperty("vines", config.hatState.is(Blocks.NETHER_WART_BLOCK));
        data.addProperty("vine_plant", material(Blocks.WEEPING_VINES_PLANT.defaultBlockState()));
        var tips = new JsonArray();
        for (int age = 23; age <= 25; age++) {
            tips.add(material(Blocks.WEEPING_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, age)));
        }
        data.add("vine_tips", tips);
        return new Primitive(data, 4);
    }

    private Primitive glowstone() {
        var data = kind("neighbor_spread");
        var provider = new JsonObject();
        provider.addProperty("type", "state");
        provider.addProperty("material", material(Blocks.GLOWSTONE.defaultBlockState()));
        data.add("provider", provider);
        var air = predicates.material(BlockState::isAir);
        data.add("can_replace", air);
        data.add("origin", LegacyFeaturePredicates.all(air,
                predicates.blocks(array(0, 1, 0), Blocks.NETHERRACK, Blocks.BASALT, Blocks.BLACKSTONE)));
        data.add("neighbors", predicates.blocks(array(0, 0, 0), Blocks.GLOWSTONE));
        data.addProperty("attempts", 1500);
        var xz = new JsonObject();
        xz.addProperty("type", "trapezoid");
        xz.addProperty("min", -7);
        xz.addProperty("max", 7);
        xz.addProperty("plateau", 0);
        data.add("xz_offset", xz);
        var y = new JsonObject();
        y.addProperty("type", "uniform");
        y.addProperty("min_inclusive", 0);
        y.addProperty("max_inclusive", 11);
        data.add("y_offset", y);
        data.addProperty("invert_y", true);
        return new Primitive(data, 7);
    }

    private int material(BlockState state) {
        return LegacyTerrainProfile.material(palette, state);
    }

    private static JsonElement integer(IntProvider provider) {
        return LegacyFeaturePlacement.integer(decode(IntProvider.CODEC.encodeStart(JsonOps.INSTANCE, provider)));
    }

    private static JsonObject kind(String kind) {
        var data = new JsonObject();
        data.addProperty("kind", kind);
        return data;
    }

    private static JsonObject recipe(String source, int step, int order, JsonArray placement, Primitive primitive) {
        long salt = 0xcbf29ce484222325L;
        for (byte value : ("step/" + step + "/" + source).getBytes(StandardCharsets.UTF_8)) {
            salt = (salt ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        }
        var recipe = kind("nether_feature");
        recipe.addProperty("source", source);
        recipe.addProperty("root_order", order);
        recipe.addProperty("generation_step", step);
        recipe.addProperty("reach", primitive.reach());
        recipe.addProperty("salt", salt);
        recipe.addProperty("placement_salt", salt);
        recipe.addProperty("density", 1);
        recipe.addProperty("low_density", 1);
        recipe.addProperty("noise_count", false);
        recipe.addProperty("rarity", 1);
        recipe.addProperty("tries", 1);
        recipe.add("spread", array(0, 0, 0));
        recipe.add("placement", placement);
        recipe.add("nether", primitive.data());
        return recipe;
    }
}
