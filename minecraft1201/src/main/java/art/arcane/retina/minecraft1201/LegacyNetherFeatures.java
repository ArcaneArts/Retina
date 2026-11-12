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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.LevelHeightAccessor;
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
import net.minecraft.world.level.levelgen.feature.configurations.ColumnFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NetherForestVegetationConfig;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ReplaceSphereConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TwistingVinesConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.TreeSet;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

/** Translate loaded Nether features to the shared ordered decoration engine. */
final class LegacyNetherFeatures {
    private record Primitive(JsonObject data, int reach) { }

    private final RegistryOps<JsonElement> ops;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final WorldGenerationContext context;
    private final LevelHeightAccessor storage;
    private final LegacyFeaturePredicates predicates = new LegacyFeaturePredicates();
    private final LegacyNetherPlants plants;
    private final LegacyFeatureTicks ticks = new LegacyFeatureTicks();

    private LegacyNetherFeatures(RegistryAccess registries, LinkedHashMap<BlockState, Integer> palette,
                                 WorldGenerationContext context, LevelHeightAccessor storage) {
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.palette = palette;
        this.context = context;
        this.storage = storage;
        this.plants = new LegacyNetherPlants(ops, predicates, this::material);
    }

    static LegacyFeaturePredicates export(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                                          JsonArray biomeEntries, LinkedHashMap<BlockState, Integer> palette,
                                          WorldGenerationContext context, LevelHeightAccessor storage, JsonObject world) {
        var exporter = new LegacyNetherFeatures(registries, palette, context, storage);
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
                    recipes.add(LegacyFeaturePlacement.recipe(source, step, rootOrder, placement,
                            nativeFeature(primitive.data()), primitive.reach()));
                } catch (IllegalArgumentException unsupported) {
                    omissions.add(source + ":" + unsupported.getMessage());
                }
            }
        }
        var report = new JsonArray();
        omissions.forEach(report::add);
        world.add("decorations", recipes);
        world.add("decoration_omissions", report);
        world.add("decoration_ticks", ticks.export());
        world.addProperty("ordered_decorations", true);
        world.addProperty("decoration_source_order", true);
        world.addProperty("decoration_biome_3d", true);
    }

    private Primitive primitive(ConfiguredFeature<?, ?> configured) {
        var feature = configured.feature();
        if (feature == Feature.SPRING) {
            return new Primitive(spring((SpringConfiguration) configured.config(), predicates, this::material, ticks), 0);
        }
        if (feature == Feature.DELTA_FEATURE) return delta((DeltaFeatureConfiguration) configured.config());
        if (feature == Feature.REPLACE_BLOBS) return blobs((ReplaceSphereConfiguration) configured.config());
        if (feature == Feature.HUGE_FUNGUS) return fungus((HugeFungusConfiguration) configured.config());
        if (feature == Feature.GLOWSTONE_BLOB) return glowstone();
        if (feature == Feature.RANDOM_PATCH) return randomPatch((RandomPatchConfiguration) configured.config());
        if (feature == Feature.NETHER_FOREST_VEGETATION) return vegetation((NetherForestVegetationConfig) configured.config());
        if (feature == Feature.WEEPING_VINES) return weeping();
        if (feature == Feature.TWISTING_VINES) return twisting((TwistingVinesConfig) configured.config());
        if (feature == Feature.BASALT_PILLAR) return pillar();
        if (feature == Feature.BASALT_COLUMNS) return columns((ColumnFeatureConfiguration) configured.config());
        throw new IllegalArgumentException(BuiltInRegistries.FEATURE.getKey(feature).toString());
    }

    static JsonObject spring(SpringConfiguration config, LegacyFeaturePredicates predicates,
                             ToIntFunction<BlockState> material, LegacyFeatureTicks ticks) {
        var data = kind("spring");
        data.addProperty("state", material.applyAsInt(config.state.createLegacyBlock()));
        data.addProperty("requires_block_below", config.requiresBlockBelow);
        data.addProperty("rock_count", config.rockCount);
        data.addProperty("hole_count", config.holeCount);
        data.add("valid_blocks", predicates.material(state -> config.validBlocks.contains(state.getBlock().builtInRegistryHolder())));
        data.add("air", predicates.material(BlockState::isAir));
        String fluid = BuiltInRegistries.FLUID.getKey(config.state.getType()).toString();
        data.addProperty("tick", ticks.fluid(fluid));
        return data;
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

    private Primitive randomPatch(RandomPatchConfiguration config) {
        var placed = config.feature().value();
        var leaf = placed.feature().value();
        if (leaf.feature() != Feature.SIMPLE_BLOCK) throw new IllegalArgumentException("random-patch:child-feature");
        var program = LegacyFeaturePlacement.encode(placed, ops);
        for (var value : program) {
            var op = value.getAsJsonObject();
            if (!op.get("type").getAsString().equals("block_predicate_filter")) {
                throw new IllegalArgumentException("random-patch:child-placement");
            }
            op.add("predicate", predicates.compile(op.getAsJsonObject("predicate")));
        }
        var simple = plants.simple(((SimpleBlockConfiguration) leaf.config()).toPlace(), null);
        return spread(config.tries(), config.xzSpread(), config.ySpread(), program, simple, null);
    }

    private Primitive vegetation(NetherForestVegetationConfig config) {
        if (config.spreadWidth <= 0 || config.spreadWidth > 16 || config.spreadHeight <= 0 || config.spreadHeight > 257) {
            throw new IllegalArgumentException("forest-vegetation:spread");
        }
        int min = storage.getMinBuildHeight() + 1;
        int max = storage.getMaxBuildHeight();
        var bounds = LegacyFeaturePredicates.insideY(min, max - 1);
        var nylium = predicates.material(array(0, -1, 0), state -> state.is(BlockTags.NYLIUM));
        var target = LegacyFeaturePredicates.all(predicates.material(BlockState::isAir),
                LegacyFeaturePredicates.insideY(min, max));
        var simple = plants.simple(config.stateProvider, target);
        return spread(config.spreadWidth * config.spreadWidth, config.spreadWidth - 1, config.spreadHeight - 1,
                new JsonArray(), simple, LegacyFeaturePredicates.all(bounds, nylium));
    }

    private static Primitive spread(int tries, int xz, int y, JsonArray placement, JsonObject feature, JsonObject origin) {
        return new Primitive(spreadPatch(tries, xz, y, LegacyFeaturePlacement.placed(placement, feature), origin), xz);
    }

    static JsonObject spreadPatch(int tries, int xz, int y, JsonObject child, JsonObject origin) {
        if (tries < 1 || tries > 4096 || xz < 0 || xz > 15 || y < 0 || y > 256) {
            throw new IllegalArgumentException("spread-patch:bounds");
        }
        var data = kind("spread_patch");
        data.addProperty("tries", tries);
        data.addProperty("xz_spread", xz);
        data.addProperty("y_spread", y);
        data.add("child", child);
        if (origin != null) data.add("origin", origin);
        return data;
    }

    private Primitive weeping() {
        var data = vines("weeping_vines", Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT);
        data.add("support", predicates.blocks(array(0, 0, 0), Blocks.NETHERRACK, Blocks.NETHER_WART_BLOCK));
        data.addProperty("wart", material(Blocks.NETHER_WART_BLOCK.defaultBlockState()));
        return new Primitive(data, 7);
    }

    private Primitive twisting(TwistingVinesConfig config) {
        if (config.spreadWidth() > 15 || config.spreadHeight() > 256 || config.maxHeight() > 256) {
            throw new IllegalArgumentException("twisting-vines:bounds");
        }
        var data = vines("twisting_vines", Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT);
        data.add("support", predicates.blocks(array(0, 0, 0), Blocks.NETHERRACK, Blocks.WARPED_NYLIUM, Blocks.WARPED_WART_BLOCK));
        data.addProperty("spread_width", config.spreadWidth());
        data.addProperty("spread_height", config.spreadHeight());
        data.addProperty("max_height", config.maxHeight());
        return new Primitive(data, config.spreadWidth());
    }

    private JsonObject vines(String kind, net.minecraft.world.level.block.Block tip, net.minecraft.world.level.block.Block plant) {
        var data = kind(kind);
        data.add("air", predicates.material(BlockState::isAir));
        data.addProperty("plant", material(plant.defaultBlockState()));
        var tips = new JsonArray();
        for (int age = 17; age <= 25; age++) {
            tips.add(material(tip.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, age)));
        }
        data.add("tips", tips);
        return data;
    }

    private Primitive pillar() {
        var data = kind("basalt_pillar");
        data.add("air", predicates.material(BlockState::isAir));
        data.addProperty("state", material(Blocks.BASALT.defaultBlockState()));
        return new Primitive(data, 3);
    }

    private Primitive columns(ColumnFeatureConfiguration config) {
        var data = kind("column_cluster");
        var provider = new JsonObject();
        provider.addProperty("type", "state");
        provider.addProperty("material", material(Blocks.BASALT.defaultBlockState()));
        data.add("provider", provider);
        data.add("continue_through", predicates.blocks(array(0, 0, 0), Blocks.BASALT));
        var air = predicates.material(BlockState::isAir);
        data.add("can_replace", air);
        data.add("air", air);
        data.add("ocean_fluid", predicates.blocks(array(0, 0, 0), Blocks.LAVA));
        data.add("cannot_place_on", predicates.blocks(array(0, 0, 0), Blocks.LAVA, Blocks.BEDROCK,
                Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND, Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE,
                Blocks.NETHER_BRICK_STAIRS, Blocks.NETHER_WART, Blocks.CHEST, Blocks.SPAWNER));
        data.add("height", integer(config.height()));
        data.add("column_reach", integer(config.reach()));
        data.addProperty("cluster_reach", 5);
        data.addProperty("column_count", 50);
        var secondary = new JsonObject();
        secondary.addProperty("chance", 0.9F);
        secondary.addProperty("reach", 8);
        secondary.addProperty("count", 15);
        data.add("secondary_cluster", secondary);
        return new Primitive(data, Math.min(8, config.height().getMaxValue()) + config.reach().getMaxValue());
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

    static JsonObject nativeFeature(JsonObject feature) {
        var recipe = kind("nether_feature");
        recipe.add("nether", feature);
        return recipe;
    }
}
