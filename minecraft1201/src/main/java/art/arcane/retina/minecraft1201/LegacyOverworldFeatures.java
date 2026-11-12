package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralWallFanBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.CountConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.HugeMushroomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.MultifaceGrowthConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.ProbabilityFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleRandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.UnderwaterMagmaConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;
import static art.arcane.retina.minecraft1201.LegacyNetherFeatures.nativeFeature;

final class LegacyOverworldFeatures {
    private record Recipe(JsonObject data, int reach) { }
    private record Nested(JsonObject data, int reach) { }
    private record Root(String source, int step, int order) {
        JsonObject branch(String path, JsonArray placement, Recipe recipe, int placementReach) {
            var row = LegacyFeaturePlacement.recipe(path, step, order, placement, recipe.data(), placementReach + recipe.reach());
            row.addProperty("root_source", source);
            row.addProperty("placement_salt", LegacyFeaturePlacement.salt(source, step));
            return row;
        }
    }

    private final RegistryOps<JsonElement> ops;
    private final WorldGenerationContext context;
    private final LinkedHashMap<BlockState, Integer> palette;
    private final LegacyFeaturePredicates predicates;
    private final boolean flat;
    private final int height;
    private final LegacyDecorationNoise noise = new LegacyDecorationNoise();
    private final LegacyBlockProviders providers;
    private final LegacyOverworldPlants plants;
    private final LegacyTreeFeatures trees;
    private final TreeSet<String> approximations = new TreeSet<>();
    private final LegacyFeatureTicks ticks = new LegacyFeatureTicks();
    private final List<JsonArray> poolWaterlogging = new ArrayList<>();
    private boolean surfaceFreeze;

    LegacyOverworldFeatures(RegistryAccess registries, WorldGenerationContext context,
                            LevelHeightAccessor storage, LinkedHashMap<BlockState, Integer> palette,
                            boolean skylight, boolean flat) {
        this.flat = flat;
        this.height = storage.getHeight();
        this.predicates = new LegacyFeaturePredicates(storage);
        this.ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        this.context = context;
        this.palette = palette;
        this.providers = new LegacyBlockProviders(ops, predicates, this::material, noise);
        this.plants = new LegacyOverworldPlants(predicates, this::material, skylight);
        predicates.bindSurvival(plants::survival);
        this.trees = new LegacyTreeFeatures(ops, predicates, providers, this::material, approximations);
    }

    void export(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                JsonArray biomeEntries, JsonObject world) {
        export(registries, sourceOrder, biomes, biomeEntries, world, biome -> biome.value().getGenerationSettings(), ignored -> false);
    }

    void export(RegistryAccess registries, List<Holder<Biome>> sourceOrder, List<Holder<Biome>> biomes,
                JsonArray biomeEntries, JsonObject world, Function<Holder<Biome>, BiomeGenerationSettings> settings,
                Predicate<PlacedFeature> deferredLayer) {
        var features = registries.registryOrThrow(Registries.PLACED_FEATURE);
        var steps = FeatureSorter.buildFeaturesPerStep(sourceOrder, biome -> settings.apply(biome).features(), true);
        var recipes = new JsonArray();
        var omissions = new TreeSet<String>();
        for (var entry : biomeEntries) entry.getAsJsonObject().add("decorations", new JsonArray());
        int order = 0;
        for (int step = 0; step < steps.size(); step++) {
            var members = membership(biomes, step, settings);
            var roots = steps.get(step);
            for (var root : roots.features()) {
                int rootOrder = order++;
                var configured = root.feature().value();
                if (configured.feature() == Feature.ORE || configured.feature() == Feature.SCATTERED_ORE
                        || deferredLayer.test(root)) continue;
                int index = roots.indexMapping().applyAsInt(root);
                String source = features.getResourceKey(root).map(key -> key.location().toString())
                        .orElse("inline/overworld/" + step + "/" + index);
                try {
                    var placed = placement(root, false);
                    var rows = new ArrayList<JsonObject>();
                    if (isSelector(configured) && configured.getFeatures().anyMatch(feature -> isTree(feature.feature()))) {
                        compileTreeBranches(configured, placed, source, new Root(source, step, rootOrder), 0, 0, rows);
                    } else {
                        var recipe = compile(configured, 0);
                        rows.add(LegacyFeaturePlacement.recipe(source, step, rootOrder, placed, recipe.data(), recipe.reach()));
                    }
                    var biomesForRoot = members.get(root);
                    for (var row : rows) {
                        for (int biome = biomesForRoot.nextSetBit(0); biome >= 0; biome = biomesForRoot.nextSetBit(biome + 1)) {
                            biomeEntries.get(biome).getAsJsonObject().getAsJsonArray("decorations").add(recipes.size());
                        }
                        recipes.add(row);
                    }
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
        world.addProperty("decoration_patch_halo", true);
        noise.export(world);
        if (flat) {
            var approximationReport = new JsonArray();
            approximations.forEach(approximationReport::add);
            world.add("decoration_approximations", approximationReport);
        } else {
            approximations.forEach(world.getAsJsonObject("registry_program").getAsJsonArray("approximations")::add);
        }
    }

    private static IdentityHashMap<PlacedFeature, BitSet> membership(List<Holder<Biome>> biomes, int step,
                                                                    Function<Holder<Biome>, BiomeGenerationSettings> settings) {
        var result = new IdentityHashMap<PlacedFeature, BitSet>();
        for (int index = 0; index < biomes.size(); index++) {
            var features = settings.apply(biomes.get(index)).features();
            if (step >= features.size()) continue;
            for (var holder : features.get(step)) {
                result.computeIfAbsent(holder.value(), ignored -> new BitSet()).set(index);
            }
        }
        return result;
    }

    void reserveWaterlogging() {
        if (!poolWaterlogging.isEmpty()) {
            for (var state : List.copyOf(palette.keySet())) {
                if (state.hasProperty(BlockStateProperties.WATERLOGGED) && !state.getValue(BlockStateProperties.WATERLOGGED)) {
                    var pair = array(material(state), material(state.setValue(BlockStateProperties.WATERLOGGED, true)));
                    poolWaterlogging.forEach(table -> table.add(pair));
                }
            }
        }
    }

    void reserveSurfaceFreeze() {
        if (surfaceFreeze) LegacyFreezeFeatures.reserve(palette);
    }

    void finish(JsonObject world, List<Holder<Biome>> biomes) {
        if (surfaceFreeze) LegacyFreezeFeatures.export(world, biomes, palette);
        predicates.finish(palette);
        var halves = new JsonArray();
        var plants = new LinkedHashMap<Block, Integer>();
        for (var state : palette.keySet()) {
            int half = 0;
            if (state.getBlock() instanceof DoublePlantBlock) {
                half = plants.computeIfAbsent(state.getBlock(), ignored -> plants.size() + 1);
                if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) half = -half;
            }
            halves.add(half);
        }
        world.add("plant_halves", halves);
    }

    // Top-level tree and mushroom operations write geometry into the region overlay.
    // Sibling recipes replay the same placement seed and select disjoint intervals.
    private void compileTreeBranches(ConfiguredFeature<?, ?> feature, JsonArray placement, String path, Root root,
                                     int depth, int reach, List<JsonObject> rows) {
        if (depth > 16) throw new IllegalArgumentException("feature:recursion");
        if (feature.config() instanceof RandomFeatureConfiguration config) {
            double lower = 0;
            int index = 0;
            for (var option : config.features) {
                double upper = lower + (1 - lower) * option.chance;
                compileChoice(option.feature.value(), placement, path + "/" + index++, root,
                        depth, reach, lower, upper, rows);
                lower = upper;
            }
            compileChoice(config.defaultFeature.value(), placement, path + "/default", root,
                    depth, reach, lower, 1, rows);
        } else if (feature.config() instanceof SimpleRandomFeatureConfiguration config) {
            int count = config.features.size();
            for (int index = 0; index < count; index++) {
                compileChoice(config.features.get(index).value(), placement, path + "/" + index, root,
                        depth, reach, (double) index / count, (double) (index + 1) / count, rows);
            }
        } else if (feature.config() instanceof RandomBooleanFeatureConfiguration config) {
            compileChoice(config.featureTrue.value(), placement, path + "/true", root, depth, reach, 0, 0.5, rows);
            compileChoice(config.featureFalse.value(), placement, path + "/false", root, depth, reach, 0.5, 1, rows);
        } else {
            rows.add(root.branch(path, placement, compile(feature, depth), reach));
        }
    }

    private void compileChoice(PlacedFeature child, JsonArray parent, String path, Root root, int depth,
                               int reach, double lower, double upper, List<JsonObject> rows) {
        if (lower == upper) return;
        // Predicate material IDs are bound after all recipes have populated the palette.
        var branch = new JsonArray();
        branch.addAll(parent);
        var select = LegacyBlockProviders.operation("select");
        select.addProperty("min", lower);
        select.addProperty("max", upper);
        branch.add(select);
        var childPlacement = placement(child, true);
        branch.addAll(childPlacement);
        compileTreeBranches(child.feature().value(), branch, path, root, depth + 1,
                reach + LegacyFeaturePlacement.nestedHorizontalReach(childPlacement), rows);
    }

    private static boolean isSelector(ConfiguredFeature<?, ?> feature) {
        return feature.feature() == Feature.RANDOM_SELECTOR || feature.feature() == Feature.SIMPLE_RANDOM_SELECTOR
                || feature.feature() == Feature.RANDOM_BOOLEAN_SELECTOR;
    }

    private static boolean isTree(Feature<?> feature) {
        return feature == Feature.TREE || feature == Feature.HUGE_RED_MUSHROOM || feature == Feature.HUGE_BROWN_MUSHROOM;
    }

    private Recipe compile(ConfiguredFeature<?, ?> configured, int depth) {
        if (depth > 16) throw new IllegalArgumentException("feature:recursion");
        var feature = configured.feature();
        var config = configured.config();
        if (feature == Feature.RANDOM_PATCH || feature == Feature.FLOWER || feature == Feature.NO_BONEMEAL_FLOWER) {
            var patch = (RandomPatchConfiguration) config;
            var child = nested(patch.feature().value(), depth);
            var spread = LegacyNetherFeatures.spreadPatch(patch.tries(), patch.xzSpread(), patch.ySpread(), child.data(), null);
            return new Recipe(nativeFeature(spread), patch.xzSpread() + child.reach());
        }
        if (isTree(feature)) {
            var tree = feature == Feature.TREE ? trees.tree((TreeConfiguration) config)
                    : trees.mushroom((HugeMushroomFeatureConfiguration) config, feature == Feature.HUGE_RED_MUSHROOM);
            return new Recipe(tree.data(), tree.reach());
        }
        if (feature == Feature.SIMPLE_BLOCK) {
            var simple = (SimpleBlockConfiguration) config;
            return new Recipe(plants.simple(providers.encode(simple.toPlace())), 0);
        }
        if (feature == Feature.VINES) {
            return new Recipe(LegacyAttachmentFeatures.vines(predicates, this::material), 0);
        }
        if (feature == Feature.MULTIFACE_GROWTH) {
            return new Recipe(LegacyAttachmentFeatures.multiface((MultifaceGrowthConfiguration) config, predicates, this::material),
                    LegacyAttachmentFeatures.REACH);
        }
        if (depth == 0 && feature == Feature.FREEZE_TOP_LAYER) {
            surfaceFreeze = true;
            return new Recipe(kind("freeze_top_layer"), 15);
        }
        if (feature == Feature.LAKE) return lake((LakeFeature.Configuration) config);
        if (flat && depth == 0 && feature == Feature.VOID_START_PLATFORM) {
            var data = kind("void_platform");
            data.add("platform", array(material(Blocks.STONE.defaultBlockState()), material(Blocks.COBBLESTONE.defaultBlockState())));
            return new Recipe(data, 0);
        }
        if (feature == Feature.BLOCK_COLUMN) return column((BlockColumnConfiguration) config);
        if (feature == Feature.DISK) return disk((DiskConfiguration) config);
        if (feature == Feature.BAMBOO) return bamboo((ProbabilityFeatureConfiguration) config);
        if (feature == Feature.FOREST_ROCK || feature == Feature.BLUE_ICE || feature == Feature.ICE_SPIKE
                || feature == Feature.ICEBERG) {
            var formation = LegacySurfaceFormations.compile(configured, predicates, this::material);
            return new Recipe(formation.data(), formation.reach());
        }
        if (feature == Feature.POINTED_DRIPSTONE || feature == Feature.DRIPSTONE_CLUSTER || feature == Feature.LARGE_DRIPSTONE) {
            var dripstone = LegacyDripstoneFeatures.compile(configured, ops, predicates, this::material, height);
            return new Recipe(dripstone.data(), dripstone.reach());
        }
        if (feature == Feature.KELP || feature == Feature.SEAGRASS || feature == Feature.SEA_PICKLE
                || feature == Feature.UNDERWATER_MAGMA || feature == Feature.CORAL_TREE
                || feature == Feature.CORAL_CLAW || feature == Feature.CORAL_MUSHROOM) return submerged(configured);
        if (feature == Feature.VEGETATION_PATCH || feature == Feature.WATERLOGGED_VEGETATION_PATCH) {
            return vegetationPatch((VegetationPatchConfiguration) config, feature == Feature.WATERLOGGED_VEGETATION_PATCH, depth);
        }
        if (feature == Feature.SPRING) {
            return new Recipe(nativeFeature(LegacyNetherFeatures.spring((SpringConfiguration) config, predicates,
                    this::material, ticks)), 0);
        }
        if (feature == Feature.RANDOM_SELECTOR) return random((RandomFeatureConfiguration) config, depth);
        if (feature == Feature.SIMPLE_RANDOM_SELECTOR) return choices((SimpleRandomFeatureConfiguration) config, depth);
        if (feature == Feature.RANDOM_BOOLEAN_SELECTOR) return either((RandomBooleanFeatureConfiguration) config, depth);
        throw new IllegalArgumentException(BuiltInRegistries.FEATURE.getKey(feature).toString());
    }

    private Recipe submerged(ConfiguredFeature<?, ?> configured) {
        var data = kind("submerged");
        var growth = new JsonObject();
        data.add("submerged", growth);
        growth.add("water", predicates.blocks(array(0, 0, 0), Blocks.WATER));
        var below = array(0, -1, 0);
        int reach;
        if (configured.feature() == Feature.KELP) {
            growth.addProperty("growth", "kelp");
            growth.addProperty("body", material(Blocks.KELP_PLANT.defaultBlockState()));
            var tips = new JsonArray();
            for (int age = 20; age <= 23; age++) tips.add(material(Blocks.KELP.defaultBlockState().setValue(KelpBlock.AGE, age)));
            growth.add("tips", tips);
            growth.add("support", predicates.material(below, state -> !state.is(Blocks.MAGMA_BLOCK)
                    && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT) || LegacyOverworldPlants.sturdyUp(state))));
            growth.add("below_is_not_tip", predicates.material(below, state -> !state.is(Blocks.KELP)));
            reach = 0;
        } else if (configured.feature() == Feature.SEAGRASS) {
            var config = (ProbabilityFeatureConfiguration) configured.config();
            growth.addProperty("growth", "seagrass");
            growth.addProperty("tall_probability", config.probability);
            growth.addProperty("short", material(Blocks.SEAGRASS.defaultBlockState()));
            var tall = Blocks.TALL_SEAGRASS.defaultBlockState();
            growth.add("tall", array(material(tall.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER)),
                    material(tall.setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER))));
            growth.add("support", predicates.material(below, state -> !state.is(Blocks.MAGMA_BLOCK) && LegacyOverworldPlants.sturdyUp(state)));
            growth.add("full_water", predicates.material(state -> state.getFluidState().is(FluidTags.WATER)
                    && state.getFluidState().getAmount() == 8));
            reach = 7;
        } else if (configured.feature() == Feature.SEA_PICKLE) {
            growth.addProperty("growth", "sea_pickle");
            growth.add("count", integer(((CountConfiguration) configured.config()).count()));
            growth.add("pickles", waterPickles());
            growth.add("support", predicates.material(below, state -> LegacyOverworldPlants.sturdyUp(state)
                    || !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).getFaceShape(Direction.UP).isEmpty()));
            reach = 7;
        } else if (configured.feature() == Feature.UNDERWATER_MAGMA) {
            var config = (UnderwaterMagmaConfiguration) configured.config();
            growth.addProperty("growth", "underwater_magma");
            growth.addProperty("material", material(Blocks.MAGMA_BLOCK.defaultBlockState()));
            growth.addProperty("floor_search_range", config.floorSearchRange);
            growth.addProperty("radius", config.placementRadiusAroundFloor);
            growth.addProperty("probability", config.placementProbabilityPerValidPosition);
            growth.add("solid", predicates.material(state -> !state.isAir() && !state.is(Blocks.WATER)));
            reach = config.placementRadiusAroundFloor + 1;
        } else {
            growth.addProperty("growth", "coral");
            var coral = new JsonObject();
            growth.add("coral", coral);
            String shape = configured.feature() == Feature.CORAL_TREE ? "tree"
                    : configured.feature() == Feature.CORAL_CLAW ? "claw" : "mushroom";
            coral.addProperty("shape", shape);
            coral.add("blocks", coralMaterials(BlockTags.CORAL_BLOCKS, null));
            coral.add("tops", coralMaterials(BlockTags.CORALS, null));
            var walls = new JsonArray();
            for (var direction : Direction.Plane.HORIZONTAL) walls.add(coralMaterials(BlockTags.WALL_CORALS, direction));
            coral.add("wall_fans", walls);
            coral.add("pickles", waterPickles());
            coral.add("replaceable", predicates.material(state -> state.is(Blocks.WATER) || state.is(BlockTags.CORALS)));
            reach = switch (shape) {
                case "tree" -> 5;
                case "claw" -> 7;
                default -> 6;
            };
        }
        return new Recipe(data, reach);
    }

    private JsonArray waterPickles() {
        var pickles = new JsonArray();
        for (int count = 1; count <= 4; count++) {
            pickles.add(material(Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, count)
                    .setValue(BlockStateProperties.WATERLOGGED, true)));
        }
        return pickles;
    }

    private JsonArray coralMaterials(TagKey<Block> tag, Direction facing) {
        var materials = new JsonArray();
        BuiltInRegistries.BLOCK.getTag(tag).ifPresent(blocks -> {
            for (var block : blocks) {
                var state = block.value().defaultBlockState();
                if (facing != null && state.hasProperty(BaseCoralWallFanBlock.FACING)) {
                    state = state.setValue(BaseCoralWallFanBlock.FACING, facing);
                }
                materials.add(material(state));
            }
        });
        return materials;
    }

    private Recipe lake(LakeFeature.Configuration config) {
        var data = kind("flat_lake");
        data.add("fluid", providers.encode(config.fluid()).data());
        data.add("barrier", providers.encode(config.barrier()).data());
        data.add("can_place_feature", LegacyFeaturePredicates.always());
        data.add("can_replace_with_air_or_fluid", predicates.material(state -> !state.is(BlockTags.FEATURES_CANNOT_REPLACE)));
        data.add("can_replace_with_barrier", predicates.material(state -> !state.is(BlockTags.LAVA_POOL_STONE_CANNOT_REPLACE)));
        data.add("solid", predicates.material(BlockState::isSolid));
        data.add("liquid", predicates.material(BlockState::liquid));
        data.add("non_air", predicates.material(state -> !state.isAir()));
        data.addProperty("air", material(Blocks.CAVE_AIR.defaultBlockState()));
        data.addProperty("air_tick", ticks.block(BuiltInRegistries.BLOCK.getKey(Blocks.CAVE_AIR).toString()));
        data.addProperty("mark_above", true);
        surfaceFreeze = true;
        data.addProperty("freeze_water", true);
        // The 1.20.1 blob begins at the placement origin in X and Z.
        data.addProperty("horizontal_offset", 0);
        return new Recipe(data, 15);
    }

    private JsonArray placement(PlacedFeature feature, boolean nested) {
        var program = LegacyFeaturePlacement.encode(feature, ops);
        LegacyFeaturePlacement.bindOverworld(program, context, predicates, nested);
        return program;
    }

    private Nested nested(PlacedFeature feature, int depth) {
        if (isTree(feature.feature().value().feature())) throw new IllegalArgumentException("feature:nested-tree");
        var placement = placement(feature, true);
        var recipe = compile(feature.feature().value(), depth + 1);
        int reach = LegacyFeaturePlacement.nestedHorizontalReach(placement) + recipe.reach();
        return new Nested(LegacyFeaturePlacement.placed(placement, recipe.data()), reach);
    }

    private Recipe random(RandomFeatureConfiguration config, int depth) {
        var data = kind("random_selector");
        var options = new JsonArray();
        var fallback = nested(config.defaultFeature.value(), depth);
        int reach = fallback.reach();
        for (var option : config.features) {
            var child = nested(option.feature.value(), depth);
            var row = new JsonObject();
            row.addProperty("chance", option.chance);
            row.add("placed", child.data());
            options.add(row);
            reach = Math.max(reach, child.reach());
        }
        data.add("options", options);
        data.add("default", fallback.data());
        return composite(data, reach);
    }

    private Recipe choices(SimpleRandomFeatureConfiguration config, int depth) {
        var data = kind("simple_selector");
        var choices = new JsonArray();
        int reach = 0;
        for (var option : config.features) {
            var child = nested(option.value(), depth);
            choices.add(child.data());
            reach = Math.max(reach, child.reach());
        }
        data.add("features", choices);
        return composite(data, reach);
    }

    private Recipe either(RandomBooleanFeatureConfiguration config, int depth) {
        var data = kind("boolean_selector");
        var yes = nested(config.featureTrue.value(), depth);
        var no = nested(config.featureFalse.value(), depth);
        data.add("when_true", yes.data());
        data.add("when_false", no.data());
        return composite(data, Math.max(yes.reach(), no.reach()));
    }

    private static Recipe composite(JsonObject selector, int reach) {
        var data = kind("sequence");
        var features = new JsonArray();
        features.add(LegacyFeaturePlacement.placed(new JsonArray(), selector));
        data.add("features", features);
        return new Recipe(data, reach);
    }

    private Recipe column(BlockColumnConfiguration config) {
        var direction = config.direction();
        long maxLength = config.layers().stream().mapToLong(layer -> layer.height().getMaxValue()).sum();
        boolean horizontal = direction.getAxis().isHorizontal();
        if (maxLength > (horizontal ? 15 : 4096) || config.layers().isEmpty() || config.layers().size() > 128) {
            throw new IllegalArgumentException("block-column:layers");
        }
        var data = kind("block_column");
        data.add("direction", array(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
        data.addProperty("prioritize_tip", config.prioritizeTip());
        data.add("allowed", predicate(config.allowedPlacement()));
        var layers = new JsonArray();
        for (var layer : config.layers()) {
            var row = new JsonObject();
            row.add("height", integer(layer.height()));
            row.add("provider", providers.encode(layer.state()).data());
            layers.add(row);
        }
        data.add("layers", layers);
        return new Recipe(data, horizontal ? (int) maxLength : 0);
    }

    private Recipe disk(DiskConfiguration config) {
        int radius = config.radius().getMaxValue();
        if (radius > 8 || config.halfHeight() > 4) throw new IllegalArgumentException("disk:bounds");
        var data = kind("disk");
        var source = decode(RuleBasedBlockStateProvider.CODEC.encodeStart(ops, config.stateProvider())).getAsJsonObject();
        data.add("provider", providers.ruleBased(source).data());
        data.add("target", predicate(config.target()));
        data.add("radius", integer(config.radius()));
        data.addProperty("half_height", config.halfHeight());
        return new Recipe(data, radius);
    }

    private Recipe bamboo(ProbabilityFeatureConfiguration config) {
        var data = kind("bamboo");
        data.addProperty("probability", config.probability);
        var trunk = Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.AGE, 1)
                .setValue(BambooStalkBlock.LEAVES, BambooLeaves.NONE).setValue(BambooStalkBlock.STAGE, 0);
        var large = trunk.setValue(BambooStalkBlock.LEAVES, BambooLeaves.LARGE);
        data.addProperty("trunk", material(trunk));
        data.add("crown", array(material(large.setValue(BambooStalkBlock.STAGE, 1)), material(large),
                material(trunk.setValue(BambooStalkBlock.LEAVES, BambooLeaves.SMALL))));
        data.addProperty("podzol", material(Blocks.PODZOL.defaultBlockState()));
        data.add("survival", plants.survival(trunk, array(0, 0, 0)));
        data.add("podzol_allowed", predicates.material(state -> state.is(BlockTags.DIRT)));
        return new Recipe(data, 4);
    }

    private Recipe vegetationPatch(VegetationPatchConfiguration config, boolean waterPool, int depth) {
        var child = nested(config.vegetationFeature.value(), depth);
        int reach = config.xzRadius.getMaxValue() + 1 + child.reach();
        if (reach > 15 || config.depth.getMinValue() < 1 || config.depth.getMaxValue() > 128
                || config.verticalRange < 1 || config.verticalRange > 256) {
            throw new IllegalArgumentException("vegetation-patch:bounds");
        }
        var data = kind("vegetation_patch");
        var ground = providers.encode(config.groundState);
        data.add("ground", ground.data());
        var sameBlocks = new JsonArray();
        for (var state : ground.states()) {
            var same = new JsonObject();
            same.addProperty("source", material(state));
            same.add("predicate", predicates.blocks(array(0, 0, 0), state.getBlock()));
            sameBlocks.add(same);
        }
        data.add("same_blocks", sameBlocks);
        data.add("vegetation", child.data());
        data.add("replaceable", predicates.material(state -> state.is(config.replaceable)));
        data.add("air", predicates.material(BlockState::isAir));
        var direction = config.surface.getDirection();
        data.add("sturdy", sturdy(array(0, 0, 0), direction.getOpposite()));
        data.addProperty("direction", direction.getStepY());
        data.add("depth", integer(config.depth));
        data.add("xz_radius", integer(config.xzRadius));
        data.addProperty("vertical_range", config.verticalRange);
        data.addProperty("extra_bottom_block_chance", config.extraBottomBlockChance);
        data.addProperty("extra_edge_column_chance", config.extraEdgeColumnChance);
        data.addProperty("vegetation_chance", config.vegetationChance);
        data.addProperty("water_pool", waterPool);
        var enclosed = new JsonArray();
        var waterlogged = new JsonArray();
        if (waterPool) {
            for (var face : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.DOWN)) {
                enclosed.add(sturdy(array(face.getStepX(), face.getStepY(), face.getStepZ()), face.getOpposite()));
            }
            poolWaterlogging.add(waterlogged);
        }
        data.add("enclosed", enclosed);
        data.add("waterlogged_states", waterlogged);
        return new Recipe(data, reach);
    }

    private JsonObject sturdy(JsonArray offset, Direction face) {
        return predicates.material(offset, state -> state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, face));
    }

    private JsonObject predicate(BlockPredicate predicate) {
        return predicates.compile(decode(BlockPredicate.CODEC.encodeStart(ops, predicate)).getAsJsonObject());
    }

    private static JsonElement integer(IntProvider value) {
        return LegacyFeaturePlacement.integer(decode(IntProvider.CODEC.encodeStart(JsonOps.INSTANCE, value)));
    }

    private static JsonObject kind(String name) {
        var data = new JsonObject();
        data.addProperty("kind", name);
        return data;
    }

    private int material(BlockState state) {
        return LegacyTerrainProfile.material(palette, state);
    }
}
