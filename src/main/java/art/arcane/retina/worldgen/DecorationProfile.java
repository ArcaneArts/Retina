package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.*;
import net.minecraft.world.level.levelgen.placement.*;

import java.util.*;

/** Projects registered vegetation features into compact Rust recipes, once per world. */
final class DecorationProfile {
    private final HolderLookup.Provider registry;
    private final LinkedHashMap<BlockState, Integer> materials;
    private final JsonArray recipes = new JsonArray();
    private final Map<String, Integer> ids = new LinkedHashMap<>();
    private final Set<String> unsupported = new TreeSet<>();
    private final ProviderNoiseProfile providerNoise;
    private DecorationProfile(HolderLookup.Provider registry, LinkedHashMap<BlockState, Integer> materials) {
        this.registry = registry;
        this.materials = materials;
        this.providerNoise = new ProviderNoiseProfile(registry);
    }
    static JsonArray export(HolderLookup.Provider registry, List<Holder<Biome>> biomes, JsonArray profiles, LinkedHashMap<BlockState, Integer> materials, JsonObject world) {
        var exporter = new DecorationProfile(registry, materials);
        for (int i = 0; i < biomes.size(); i++) {
            var selected = new JsonArray();
            var features = biomes.get(i).value().getGenerationSettings().features();
            int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
            if (features.size() > step) for (var holder : features.get(step)) {
                String name = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline/" + biomes.get(i).unwrapKey().map(k -> k.identifier().toString()).orElse(Integer.toString(i)) + "/" + selected.size());
                var before = new ArrayList<Integer>();
                exporter.placed(holder.value(), new Placement(), 1, name, before, 0);
                for (int id : before) selected.add(id);
            }
            profiles.get(i).getAsJsonObject().add("decorations", selected);
        }
        Retina.LOGGER.info("Exported {} registered decoration recipes; omitted feature kinds: {}", exporter.recipes.size(), exporter.unsupported);
        world.add("decoration_provider_noises", exporter.providerNoise.programs);
        return exporter.recipes;
    }

    private static final class Placement {
        double density = 1, lowDensity = 1, rarity = 1, tries = 1;
        int xSpread = 0, ySpread = 0, zSpread = 0;
        boolean surface = false, noiseCount = false;
        JsonArray waterOffsets = new JsonArray();
        JsonArray program = new JsonArray();
        long placementSalt;
        Placement copy() {
            var p = new Placement(); p.density = density; p.lowDensity = lowDensity; p.rarity = rarity; p.tries = tries;
            p.xSpread = xSpread; p.ySpread = ySpread; p.zSpread = zSpread; p.surface = surface; p.noiseCount = noiseCount; p.waterOffsets = waterOffsets.deepCopy(); p.program = program.deepCopy(); p.placementSalt = placementSalt; return p;
        }
    }
    private void placed(PlacedFeature placed, Placement previous, double chance, String path, List<Integer> selected, int depth) {
        if (chance <= 0) return;
        if (depth > 16) throw new IllegalArgumentException("Recursive decoration feature: " + path);
        var p = previous.copy();
        if (depth == 0) p.placementSalt = salt(path);
        for (var modifier : placed.placement()) {
            var json = PlacementModifier.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), modifier).getOrThrow().getAsJsonObject();
            switch (type(json)) {
                case "count" -> {
                    if (!supportedIntProvider(json.get("count"))) { unsupported.add("count:" + json.get("count")); return; }
                    double n = mean(json.get("count"));
                    if (p.surface) p.tries *= n;
                    else { p.density *= n; p.lowDensity *= n; }
                }
                case "count_on_every_layer" -> {
                    if (!supportedIntProvider(json.get("count"))) { unsupported.add("count_on_every_layer:" + json.get("count")); return; }
                }
                case "noise_threshold_count" -> {
                    p.density *= json.get("above_noise").getAsDouble();
                    p.lowDensity *= json.get("below_noise").getAsDouble(); p.noiseCount = true;
                }
                // Preserve the complete spatial count, including negative ratios.
                // The legacy mean is unused when the placement program is present.
                case "noise_based_count" -> { }
                case "rarity_filter" -> p.rarity *= json.get("chance").getAsDouble();
                case "heightmap" -> p.surface = true;
                case "offset" -> {
                    if (!supportedIntProvider(json.get("x")) || !supportedIntProvider(json.get("y")) || !supportedIntProvider(json.get("z"))) { unsupported.add("offset:" + json); return; }
                    p.xSpread += extent(json.get("x")); p.ySpread += extent(json.get("y")); p.zSpread += extent(json.get("z"));
                }
                case "block_predicate_filter" -> {
                    if (!supportedPredicate(json.getAsJsonObject("predicate"))) { unsupported.add("predicate:" + json.get("predicate")); return; }
                    waterOffsets(json.getAsJsonObject("predicate"), p.waterOffsets);
                }
                case "height_range" -> {
                    if (!supportedHeightProvider(json.get("height"))) { unsupported.add("height:" + json.get("height")); return; }
                }
                case "environment_scan" -> {
                    if (!supportedPredicate(json.getAsJsonObject("target_condition")) || json.has("allowed_search_condition") && !supportedPredicate(json.getAsJsonObject("allowed_search_condition"))) {
                        unsupported.add("environment_scan:predicate:"+json); return;
                    }
                }
                case "random_chance" -> { }
                case "in_square", "biome", "surface_water_depth_filter", "surface_relative_threshold_filter" -> { }
                default -> { unsupported.add("placement:" + type(json)); return; }
            }
            p.program.add(json.deepCopy());
        }
        var feature = placed.feature().value();
        if (feature instanceof RandomSelectorFeature random) {
            double remaining = chance;
            for (int i = 0; i < random.features().size(); i++) {
                var option = random.features().get(i);
                double low = 1 - remaining / chance;
                var branch = selection(p, low, low + remaining / chance * option.chance());
                placed(option.feature().value(), branch, remaining * option.chance(), path + "/choice" + i, selected, depth + 1);
                remaining *= 1 - option.chance();
            }
            placed(random.defaultFeature().value(), selection(p, 1 - remaining / chance, 1), remaining, path + "/default", selected, depth + 1);
        } else if (feature instanceof SimpleRandomSelectorFeature random) {
            for (int i = 0; i < random.features().size(); i++) placed(random.features().get(i).value(), selection(p, (double)i / random.features().size(), (double)(i+1) / random.features().size()), chance / random.features().size(), path + "/choice" + i, selected, depth + 1);
        } else if (feature instanceof RandomBooleanSelectorFeature random) {
            placed(random.featureTrue().value(), selection(p, 0, .5), chance * 0.5, path + "/true", selected, depth + 1);
            placed(random.featureFalse().value(), selection(p, .5, 1), chance * 0.5, path + "/false", selected, depth + 1);
        } else if (feature instanceof WeightedRandomSelectorFeature random) {
            double total = random.features().unwrap().stream().mapToInt(w -> w.weight()).sum();
            if (total <= 0) return;
            int i = 0; double low = 0;
            for (var w : random.features().unwrap()) {
                double high = low + w.weight() / total;
                placed(w.value().value(), selection(p, low, high), chance * w.weight() / total, path + "/choice" + i++, selected, depth + 1);
                low = high;
            }
        } else if (feature instanceof VegetationPatchFeature patch) {
            var data=patch(patch,depth+1);
            if(data!=null)emit(path,p,chance,"vegetation_patch",data,selected);
        } else if (feature instanceof TreeFeature tree) {
            var data = tree(tree);
            if (data != null) emit(path, p, chance, "tree", data, selected);
        } else if (feature instanceof AbstractHugeMushroomFeature mushroom) {
            var data=mushroom(mushroom);
            if(data != null)emit(path,p,chance,"huge_mushroom",data,selected);
        } else if (feature instanceof FallenTreeFeature fallen) {
            var data=fallen(fallen);
            if(data != null)emit(path,p,chance,"fallen_tree",data,selected);
        } else if (feature instanceof BlockColumnFeature column) {
            var data = column(column);
            if(data != null)emit(path,p,chance,"block_column",data,selected);
        } else if (feature instanceof BambooFeature bamboo) {
            var data = new JsonObject(); data.addProperty("probability",bamboo.probability());
            var trunk=Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.AGE,1).setValue(BambooStalkBlock.LEAVES,BambooLeaves.NONE).setValue(BambooStalkBlock.STAGE,0);
            data.addProperty("trunk",material(trunk)); var crown=new JsonArray();
            crown.add(material(trunk.setValue(BambooStalkBlock.LEAVES,BambooLeaves.LARGE).setValue(BambooStalkBlock.STAGE,1)));
            crown.add(material(trunk.setValue(BambooStalkBlock.LEAVES,BambooLeaves.LARGE)));
            crown.add(material(trunk.setValue(BambooStalkBlock.LEAVES,BambooLeaves.SMALL))); data.add("crown",crown);
            data.addProperty("podzol",material(Blocks.PODZOL.defaultBlockState()));
            data.add("survival",matching("matching_block_tag","tag",BlockTags.SUPPORTS_BAMBOO.location().toString(),0,-1,0));
            data.add("podzol_allowed",matching("matching_block_tag","tag",BlockTags.BENEATH_BAMBOO_PODZOL_REPLACEABLE.location().toString(),0,0,0));
            emit(path,p,chance,"bamboo",data,selected);
        } else if (feature instanceof SimpleBlockFeature block) {
            var encoded=BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), block.toPlace().value()).getOrThrow();
            if(contextProvider(encoded,0)) {
                var data=simple(block);
                if(data!=null)emit(path,p,chance,"simple_block",data,selected);
                else unsupported.add("SimpleBlockFeature:context-provider-or-survival");
                return;
            }
            var states = provider(encoded, 1, 0, 0);
            var variants = new JsonArray();
            var aquatic = new JsonArray();
            var drySimple = new JsonArray();
            for (var state : states) {
                if (state.state.is(Blocks.SEAGRASS) || state.state.is(Blocks.TALL_SEAGRASS) || state.state.is(Blocks.LILY_PAD)) {
                    var lower=state.state; int upper=0;
                    if(lower.getBlock() instanceof DoublePlantBlock) {
                        lower=lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER);
                        upper=material(lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER));
                    }
                    aquatic.add(simpleVariant(lower,upper,state.weight,state.band));continue;
                }
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
                drySimple.add(simpleVariant(lower,upper,state.weight,state.band));
            }
            if (!aquatic.isEmpty()) {
                aquatic.addAll(drySimple);
                var data=new JsonObject();data.add("states",aquatic);emit(path,p,chance,"aquatic",data,selected);
            } else if (!variants.isEmpty()) { var data = new JsonObject(); data.add("states", variants); emit(path, p, chance, "plant", data, selected); }
            else {
                var data=simple(block);
                if(data!=null)emit(path,p,chance,"simple_block",data,selected);
                else unsupported.add(feature.getClass().getSimpleName() + ":unsupported-survival");
            }
        } else unsupported.add(feature.getClass().getSimpleName());
    }

    private static Placement selection(Placement parent, double low, double high) {
        var result = parent.copy();
        var op = new JsonObject(); op.addProperty("type", "select"); op.addProperty("min", low); op.addProperty("max", high);
        result.program.add(op); return result;
    }

    static boolean supportedIntProvider(JsonElement value) {
        if (value == null || value.isJsonPrimitive()) return true;
        var object = value.getAsJsonObject();
        return switch (type(object)) {
            case "constant", "uniform", "biased_to_bottom", "trapezoid", "clamped_normal" -> true;
            case "clamped" -> supportedIntProvider(object.get("source"));
            case "weighted_list" -> object.getAsJsonArray("distribution").asList().stream().allMatch(e -> supportedIntProvider(e.getAsJsonObject().get("data")));
            default -> false;
        };
    }
    static boolean supportedHeightProvider(JsonElement value) {
        var object=value.getAsJsonObject();
        if(!object.has("type"))return object.has("absolute") || object.has("above_bottom") || object.has("below_top");
        return switch(type(object)) {
            case "constant", "uniform", "biased_to_bottom", "very_biased_to_bottom", "trapezoid" -> true;
            case "weighted_list" -> object.getAsJsonArray("distribution").asList().stream().allMatch(e->supportedHeightProvider(e.getAsJsonObject().get("data")));
            default -> false;
        };
    }

    static boolean supportedPredicate(JsonObject p) {
        return switch (type(p)) {
            case "all_of", "any_of" -> p.getAsJsonArray("predicates").asList().stream().allMatch(e -> supportedPredicate(e.getAsJsonObject()));
            case "not" -> supportedPredicate(p.getAsJsonObject("predicate"));
            case "matching_blocks", "matching_block_tag", "matching_fluids", "replaceable", "true", "has_sturdy_face", "solid" -> true;
            case "would_survive" -> BlockState.CODEC.parse(JsonOps.INSTANCE, p.get("state")).result().map(s -> s.getBlock() instanceof VegetationBlock || s.getBlock() instanceof CactusBlock || s.getBlock() instanceof SugarCaneBlock || s.getBlock() instanceof BambooStalkBlock).orElse(false);
            default -> false;
        };
    }

    /** Resolve tags/material predicates after every registry exporter has extended the palette. */
    static void finishPlacements(JsonArray recipes, LinkedHashMap<BlockState, Integer> palette) {
        for (var element : recipes) {
            var recipe = element.getAsJsonObject();
            finishProgram(recipe.getAsJsonArray("placement"),palette);
            finishFeature(recipe,palette);
        }
    }
    private static void finishProgram(JsonArray program,LinkedHashMap<BlockState,Integer> palette) {
        for (var value : program) {
                var op = value.getAsJsonObject();
                String kind = type(op);
                op.addProperty("type", kind);
                if (kind.equals("heightmap") || kind.equals("surface_relative_threshold_filter")) {
                    op.addProperty("map", Heightmap.Types.valueOf(op.remove("heightmap").getAsString()).ordinal());
                }
                if (kind.equals("block_predicate_filter")) op.add("predicate", predicate(op.getAsJsonObject("predicate"), palette));
                if (kind.equals("environment_scan")) {
                    var direction=Direction.valueOf(op.remove("direction_of_search").getAsString().toUpperCase(Locale.ROOT));
                    op.addProperty("direction",direction.getStepY());
                    op.add("target_condition",predicate(op.getAsJsonObject("target_condition"),palette));
                    if(op.has("allowed_search_condition"))op.add("allowed_search_condition",predicate(op.getAsJsonObject("allowed_search_condition"),palette));
                }
                normalizeTypes(op);
            }
    }
    private static void finishFeature(JsonObject recipe,LinkedHashMap<BlockState,Integer> palette) {
            switch(recipe.get("kind").getAsString()) {
                case "vegetation_patch" -> {
                    for(String key:List.of("replaceable","air","sturdy"))recipe.add(key,predicate(recipe.getAsJsonObject(key),palette));
                    var same=new JsonArray();
                    for(int id:programStates(recipe.getAsJsonObject("ground"))) {
                        var base=palette.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
                        var item=new JsonObject();item.addProperty("source",id);item.add("predicate",predicate(matching("matching_blocks","blocks",BuiltInRegistries.BLOCK.getKey(base.getBlock()).toString(),0,0,0),palette));same.add(item);
                    }
                    recipe.add("same_blocks",same);finishProvider(recipe.getAsJsonObject("ground"),palette);normalizeTypes(recipe.get("depth"));normalizeTypes(recipe.get("xz_radius"));
                    var enclosed=recipe.getAsJsonArray("enclosed");for(int i=0;i<enclosed.size();i++)enclosed.set(i,predicate(enclosed.get(i).getAsJsonObject(),palette));
                    var wet=new JsonArray();for(var e:palette.entrySet())if(e.getKey().hasProperty(BlockStateProperties.WATERLOGGED)) {
                        var target=palette.get(e.getKey().setValue(BlockStateProperties.WATERLOGGED,true));
                        if(target!=null && target.intValue()!=e.getValue().intValue()){var pair=new JsonArray();pair.add(e.getValue());pair.add(target);wet.add(pair);}
                    }
                    recipe.add("waterlogged_states",wet);finishNested(recipe.getAsJsonObject("vegetation"),palette);
                }
                case "simple_block" -> {
                    finishProvider(recipe.getAsJsonObject("provider"),palette);recipe.add("water",predicate(recipe.getAsJsonObject("water"),palette));
                    for(var value:recipe.getAsJsonArray("states"))for(String key:List.of("survival","upper_allowed")) {
                        var state=value.getAsJsonObject();state.add(key,predicate(state.getAsJsonObject(key),palette));
                    }
                }
                case "random_selector" -> {for(var v:recipe.getAsJsonArray("options"))finishNested(v.getAsJsonObject().getAsJsonObject("placed"),palette);finishNested(recipe.getAsJsonObject("default"),palette);}
                case "simple_selector" -> {for(var v:recipe.getAsJsonArray("features"))finishNested(v.getAsJsonObject(),palette);}
                case "boolean_selector" -> {finishNested(recipe.getAsJsonObject("when_true"),palette);finishNested(recipe.getAsJsonObject("when_false"),palette);}
                case "weighted_selector" -> {for(var v:recipe.getAsJsonArray("entries"))finishNested(v.getAsJsonObject().getAsJsonObject("placed"),palette);}
                case "fallen_tree" -> {
                    for(String key:List.of("clearance","sturdy","air","replaceable","water","shelf"))recipe.add(key,predicate(recipe.getAsJsonObject(key),palette));
                    finishProvider(recipe.getAsJsonObject("trunk"),palette);normalizeTypes(recipe.get("log_length"));
                    normalizeTypes(recipe.get("stump_decorators"));normalizeTypes(recipe.get("log_decorators"));
                    for(String key:List.of("stump_decorators","log_decorators"))for(var d:recipe.getAsJsonArray(key))if(d.getAsJsonObject().has("provider"))finishProvider(d.getAsJsonObject().getAsJsonObject("provider"),palette);
                }
                case "huge_mushroom" -> {
                    for(String key:List.of("support","clearance","replaceable"))recipe.add(key,predicate(recipe.getAsJsonObject(key),palette));
                    finishProvider(recipe.getAsJsonObject("cap"),palette);finishProvider(recipe.getAsJsonObject("stem"),palette);
                }
                case "block_column" -> {recipe.add("allowed",predicate(recipe.getAsJsonObject("allowed"),palette));normalizeTypes(recipe.get("layers"));for(var l:recipe.getAsJsonArray("layers"))finishProvider(l.getAsJsonObject().getAsJsonObject("provider"),palette);}
                case "bamboo" -> {for(String key:List.of("survival","podzol_allowed"))recipe.add(key,predicate(recipe.getAsJsonObject(key),palette));}
                case "aquatic" -> {for(var variant:recipe.getAsJsonArray("states"))for(String key:List.of("survival","upper_allowed")) {
                    var state=variant.getAsJsonObject();state.add(key,predicate(state.getAsJsonObject(key),palette));
                }}
            }
    }

    private static void finishProvider(JsonObject provider,LinkedHashMap<BlockState,Integer> palette) {
        switch(provider.get("type").getAsString()) {
            case "rule_based" -> {
                for(var e:provider.getAsJsonArray("rules")) {
                    var rule=e.getAsJsonObject();rule.add("predicate",predicate(rule.getAsJsonObject("predicate"),palette));
                    finishProvider(rule.getAsJsonObject("provider"),palette);
                }
                if(provider.has("fallback"))finishProvider(provider.getAsJsonObject("fallback"),palette);
            }
            case "weighted" -> {for(var e:provider.getAsJsonArray("entries"))finishProvider(e.getAsJsonObject().getAsJsonObject("provider"),palette);}
            case "rotated", "randomized_int" -> finishProvider(provider.getAsJsonObject("source"),palette);
        }
        normalizeTypes(provider);
    }
    private static void finishNested(JsonObject placed,LinkedHashMap<BlockState,Integer> palette) {
        finishProgram(placed.getAsJsonArray("placement"),palette);finishFeature(placed.getAsJsonObject("feature"),palette);
    }
    private static void normalizeTypes(JsonElement value) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("type")) object.addProperty("type", type(object));
            for (var child : object.entrySet()) normalizeTypes(child.getValue());
        } else if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) normalizeTypes(child);
        }
    }

    private static TagKey<Block> survivalSoil(BlockState state) {
        if(state.getBlock() instanceof AzaleaBlock)return BlockTags.SUPPORTS_AZALEA;
        if (state.getBlock() instanceof MangrovePropaguleBlock) {
            return state.getValue(MangrovePropaguleBlock.HANGING)
                    ? BlockTags.SUPPORTS_HANGING_MANGROVE_PROPAGULE : BlockTags.SUPPORTS_MANGROVE_PROPAGULE;
        }
        return state.getBlock() instanceof DryVegetationBlock ? BlockTags.SUPPORTS_DRY_VEGETATION : BlockTags.SUPPORTS_VEGETATION;
    }

    private static JsonObject matching(String type,String key,String value,int x,int y,int z) {
        var p=new JsonObject();p.addProperty("type",type);p.addProperty(key,value);p.add("offset",new Gson().toJsonTree(new int[]{x,y,z}));return p;
    }
    private static JsonObject test(String type,int x,int y,int z) {
        var p=new JsonObject();p.addProperty("type",type);p.add("offset",new Gson().toJsonTree(new int[]{x,y,z}));return p;
    }
    private static JsonObject combine(String type,JsonObject... children) {
        var p=new JsonObject();p.addProperty("type",type);var all=new JsonArray();for(var c:children)all.add(c);p.add("predicates",all);return p;
    }
    private static JsonObject not(JsonObject child) {var p=new JsonObject();p.addProperty("type","not");p.add("predicate",child);return p;}
    private static void shiftPredicate(JsonObject p,int[] offset) {
        if(p.has("predicates")) {for(var child:p.getAsJsonArray("predicates"))shiftPredicate(child.getAsJsonObject(),offset);}
        else if(p.has("predicate"))shiftPredicate(p.getAsJsonObject("predicate"),offset);
        else {var v=p.has("offset")?new Gson().fromJson(p.get("offset"),int[].class):new int[3];for(int i=0;i<3;i++)v[i]+=offset[i];p.add("offset",new Gson().toJsonTree(v));}
    }
    private JsonObject simpleVariant(BlockState lower,int upper,double weight,int band) {
        var variant=new JsonObject();variant.addProperty("lower",material(lower));variant.addProperty("upper",upper);
        variant.addProperty("weight",weight);variant.addProperty("band",band);variant.add("survival",survivalPredicate(lower));
        var above=new JsonObject();above.addProperty("type","same_fluid_replaceable");above.add("state",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,lower).getOrThrow());
        variant.add("upper_allowed",combine("any_of",matching("matching_block_tag","tag","minecraft:air",0,0,0),above));return variant;
    }
    private static JsonObject survivalPredicate(BlockState state) {
        if(state.getBlock() instanceof SmallDripleafBlock)return combine("any_of",matching("matching_block_tag","tag",BlockTags.SUPPORTS_SMALL_DRIPLEAF.location().toString(),0,-1,0),combine("all_of",test("source_water",0,0,0),matching("matching_block_tag","tag",BlockTags.SUPPORTS_VEGETATION.location().toString(),0,-1,0)));
        if(state.getBlock() instanceof SeaPickleBlock)return test("supports_sea_pickle",0,-1,0);
        if(state.getBlock() instanceof CarpetBlock)return not(matching("matching_block_tag","tag","minecraft:air",0,-1,0));
        if(state.getBlock() instanceof SnowLayerBlock)return test("supports_snow",0,-1,0);
        if(defaultSurvival(state)){var result=new JsonObject();result.addProperty("type","true");return result;}
        if(state.getBlock() instanceof CactusBlock) {
            var neighbors=new ArrayList<JsonObject>();
            for(var d:Direction.Plane.HORIZONTAL)neighbors.add(not(test("solid_or_lava",d.getStepX(),0,d.getStepZ())));
            neighbors.add(not(test("liquid",0,1,0)));
            neighbors.add(combine("any_of",matching("matching_blocks","blocks",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),0,-1,0),matching("matching_block_tag","tag",BlockTags.SUPPORTS_CACTUS.location().toString(),0,-1,0)));
            return combine("all_of",neighbors.toArray(JsonObject[]::new));
        }
        if(state.getBlock() instanceof SugarCaneBlock) {
            var neighbors=new ArrayList<JsonObject>();
            for(var d:Direction.Plane.HORIZONTAL)neighbors.add(combine("any_of",matching("matching_fluids","fluids", "#"+FluidTags.SUPPORTS_SUGAR_CANE_ADJACENTLY.location(),d.getStepX(),-1,d.getStepZ()),matching("matching_block_tag","tag",BlockTags.SUPPORTS_SUGAR_CANE_ADJACENTLY.location().toString(),d.getStepX(),-1,d.getStepZ())));
            return combine("any_of",matching("matching_blocks","blocks",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),0,-1,0),combine("all_of",matching("matching_block_tag","tag",BlockTags.SUPPORTS_SUGAR_CANE.location().toString(),0,-1,0),combine("any_of",neighbors.toArray(JsonObject[]::new))));
        }
        if(state.getBlock() instanceof BambooStalkBlock)return matching("matching_block_tag","tag",BlockTags.SUPPORTS_BAMBOO.location().toString(),0,-1,0);
        if(state.is(Blocks.LILY_PAD))return combine("all_of",combine("any_of",matching("matching_fluids","fluids","#"+FluidTags.SUPPORTS_LILY_PAD.location(),0,-1,0),matching("matching_block_tag","tag",BlockTags.SUPPORTS_LILY_PAD.location().toString(),0,-1,0)),matching("matching_fluids","fluids","minecraft:empty",0,0,0));
        if(state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS)) {
            var floor=combine("all_of",matching("has_sturdy_face","direction","up",0,-1,0),not(matching("matching_block_tag","tag",BlockTags.CANNOT_SUPPORT_SEAGRASS.location().toString(),0,-1,0)));
            return state.is(Blocks.TALL_SEAGRASS)?combine("all_of",floor,test("full_water",0,0,0)):floor;
        }
        return matching("matching_block_tag","tag",survivalSoil(state).location().toString(),0,state.getBlock() instanceof MangrovePropaguleBlock && state.getValue(MangrovePropaguleBlock.HANGING)?1:-1,0);
    }

    private JsonObject column(BlockColumnFeature feature) {
        long maximum=feature.layers().stream().mapToLong(l->l.height().maxInclusive()).sum();
        if(feature.layers().size()>128 || maximum>(feature.direction().getAxis().isHorizontal()?15:4096)) {unsupported.add("block_column:geometry_exceeds_halo_or_height");return null;}
        var json=Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),feature).getOrThrow().getAsJsonObject();
        if(!supportedPredicate(json.getAsJsonObject("allowed_placement"))) {unsupported.add("block_column:predicate:"+json.get("allowed_placement"));return null;}
        var result=new JsonObject();result.add("direction",new Gson().toJsonTree(new int[]{feature.direction().getStepX(),feature.direction().getStepY(),feature.direction().getStepZ()}));
        result.add("allowed",json.get("allowed_placement").deepCopy());result.add("prioritize_tip",json.get("prioritize_tip"));var layers=new JsonArray();
        for(var e:json.getAsJsonArray("layers")) {
            var layer=e.getAsJsonObject();if(!supportedIntProvider(layer.get("height"))) {unsupported.add("block_column:height:"+layer.get("height"));return null;}
            var provider=stateProgram(layer.get("provider"),0);if(provider==null)return null;
            var output=new JsonObject();output.add("height",layer.get("height").deepCopy());output.add("provider",provider);layers.add(output);
        }
        result.add("layers",layers);
        // Registered waterlogged patches postprocess any placed column base.
        for(var layer:layers)for(int id:programStates(layer.getAsJsonObject().getAsJsonObject("provider"))) {
            var state=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
            if(state.hasProperty(BlockStateProperties.WATERLOGGED))material(state.setValue(BlockStateProperties.WATERLOGGED,true));
        }
        return result;
    }
    private static boolean defaultSurvival(BlockState state) {
        for(Class<?> c=state.getBlock().getClass();c!=null;c=c.getSuperclass())for(var m:c.getDeclaredMethods())
            if(m.getName().equals("canSurvive") && m.getParameterCount()==3 && m.getParameterTypes()[0]==BlockState.class)
                return c==net.minecraft.world.level.block.state.BlockBehaviour.class;
        return false;
    }
    private JsonObject simple(SimpleBlockFeature feature) {
        var provider=stateProgram(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),feature.toPlace().value()).getOrThrow(),0);
        if(provider==null)return null;
        var states=new JsonArray();
        for(int id:programStates(provider)) {
            var state=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
            if(state.getBlock() instanceof MossyCarpetBlock || state.getBlock() instanceof MushroomBlock || !(state.getBlock() instanceof VegetationBlock || state.getBlock() instanceof CarpetBlock || state.getBlock() instanceof SnowLayerBlock || defaultSurvival(state))) {
                unsupported.add("simple_block:survival:"+BuiltInRegistries.BLOCK.getKey(state.getBlock()));return null;
            }
            if(state.getBlock() instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF)!=DoubleBlockHalf.LOWER) {unsupported.add("simple_block:upper-half-provider");return null;}
            var entry=new JsonObject();entry.addProperty("source",id);var lower=new JsonArray();var upper=new JsonArray();
            for(boolean wet:List.of(false,true)) {
                var lo=state;BlockState up=null;
                if(state.getBlock() instanceof DoublePlantBlock) {
                    up=state.setValue(DoublePlantBlock.HALF,DoubleBlockHalf.UPPER);
                    if(state.hasProperty(BlockStateProperties.WATERLOGGED)){lo=lo.setValue(BlockStateProperties.WATERLOGGED,wet);up=up.setValue(BlockStateProperties.WATERLOGGED,wet);}
                }
                lower.add(material(lo));upper.add(up==null?0:material(up));
                // Pool postprocessing can waterlog single blocks too.
                if(state.hasProperty(BlockStateProperties.WATERLOGGED))material(state.setValue(BlockStateProperties.WATERLOGGED,wet));
            }
            entry.add("lower",lower);entry.add("upper",upper);entry.add("survival",survivalPredicate(state));
            var same=new JsonObject();same.addProperty("type","same_fluid_replaceable");same.add("state",BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());
            entry.add("upper_allowed",combine("any_of",matching("matching_block_tag","tag","minecraft:air",0,0,0),same));states.add(entry);
        }
        var result=new JsonObject();result.addProperty("kind","simple_block");result.add("provider",provider);result.add("states",states);
        result.add("water",matching("matching_fluids","fluids","#"+FluidTags.WATER.location(),0,0,0));result.addProperty("reach",0);return result;
    }
    private JsonObject patch(VegetationPatchFeature feature,int depth) {
        if(depth>16)throw new IllegalArgumentException("Recursive vegetation patch");
        var ops=registry.createSerializationContext(JsonOps.INSTANCE);
        var json=Feature.DIRECT_CODEC.encodeStart(ops,feature).getOrThrow().getAsJsonObject();
        if(!supportedIntProvider(json.get("xz_radius")) || !supportedIntProvider(json.get("depth"))) {unsupported.add("vegetation_patch:int_provider");return null;}
        int radius=net.minecraft.util.valueproviders.IntProviders.CODEC.parse(JsonOps.INSTANCE,json.get("xz_radius")).getOrThrow().maxInclusive()+1;
        var ground=stateProgram(json.get("ground_state"),0);if(ground==null)return null;
        var vegetation=nested(PlacedFeature.CODEC.parse(ops,json.get("vegetation_feature")).getOrThrow().value(),depth+1);
        if(vegetation==null)return null;
        if(radius<1 || radius+vegetation.get("reach").getAsInt()>15) {unsupported.add("vegetation_patch:geometry_exceeds_halo");return null;}
        int direction=json.get("surface").getAsString().equals("floor")?-1:1;
        var result=new JsonObject();result.addProperty("kind","vegetation_patch");result.add("ground",ground);result.add("vegetation",vegetation);
        var replaceable=new JsonObject();replaceable.addProperty("type","matching_blocks");replaceable.add("blocks",json.get("replaceable").deepCopy());result.add("replaceable",replaceable);
        result.add("air",matching("matching_block_tag","tag","minecraft:air",0,0,0));result.add("sturdy",matching("has_sturdy_face","direction",direction<0?"up":"down",0,0,0));result.addProperty("direction",direction);
        for(String key:List.of("depth","xz_radius","vertical_range","extra_bottom_block_chance","extra_edge_column_chance","vegetation_chance"))result.add(key,json.get(key).deepCopy());
        boolean water=feature instanceof WaterloggedVegetationPatchFeature;result.addProperty("water_pool",water);var enclosed=new JsonArray();
        if(water)for(var d:List.of(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST,Direction.DOWN))enclosed.add(matching("has_sturdy_face","direction",d.getOpposite().getName(),d.getStepX(),d.getStepY(),d.getStepZ()));
        result.add("enclosed",enclosed);result.addProperty("reach",radius+vegetation.get("reach").getAsInt());return result;
    }
    private JsonObject nested(PlacedFeature placed,int depth) {
        if(depth>16)throw new IllegalArgumentException("Recursive nested patch feature");
        var program=new JsonArray();int reach=0;
        for(var modifier:placed.placement()) {
            var json=PlacementModifier.CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),modifier).getOrThrow().getAsJsonObject();
            boolean supported=switch(type(json)) {
                case "count", "count_on_every_layer" -> supportedIntProvider(json.get("count"));
                case "offset" -> supportedIntProvider(json.get("x")) && supportedIntProvider(json.get("y")) && supportedIntProvider(json.get("z"));
                case "height_range" -> supportedHeightProvider(json.get("height"));
                case "block_predicate_filter" -> supportedPredicate(json.getAsJsonObject("predicate"));
                case "environment_scan" -> supportedPredicate(json.getAsJsonObject("target_condition")) && (!json.has("allowed_search_condition") || supportedPredicate(json.getAsJsonObject("allowed_search_condition")));
                case "in_square", "heightmap", "rarity_filter", "random_chance", "surface_water_depth_filter", "surface_relative_threshold_filter" -> true;
                default -> false;
            };
            if(!supported){unsupported.add("vegetation_patch:nested_placement:"+type(json));return null;}
            if(type(json).equals("in_square") || type(json).equals("count_on_every_layer"))reach+=15;
            if(type(json).equals("offset"))for(String axis:List.of("x","z")) {
                var provider=net.minecraft.util.valueproviders.IntProviders.CODEC.parse(JsonOps.INSTANCE,json.get(axis)).getOrThrow();reach+=Math.max(Math.abs(provider.minInclusive()),Math.abs(provider.maxInclusive()));
            }
            program.add(json);
        }
        var feature=placed.feature().value();JsonObject data=null;
        if(feature instanceof BlockColumnFeature column) {
            data=column(column);if(data!=null){data.addProperty("kind","block_column");data.addProperty("reach",column.direction().getAxis().isHorizontal()?Math.max(0,column.layers().stream().mapToInt(l->l.height().maxInclusive()).sum()-1):0);}
        } else if(feature instanceof SimpleBlockFeature block)data=simple(block);
        else if(feature instanceof VegetationPatchFeature patch)data=patch(patch,depth+1);
        else if(feature instanceof RandomSelectorFeature random) {
            data=new JsonObject();data.addProperty("kind","random_selector");var options=new JsonArray();int extent=0;
            for(var option:random.features()) {
                var child=nested(option.feature().value(),depth+1);if(child==null)return null;
                var item=new JsonObject();item.addProperty("chance",option.chance());item.add("placed",child);options.add(item);extent=Math.max(extent,child.get("reach").getAsInt());
            }
            var child=nested(random.defaultFeature().value(),depth+1);if(child==null)return null;
            data.add("options",options);data.add("default",child);data.addProperty("reach",Math.max(extent,child.get("reach").getAsInt()));
        } else if(feature instanceof SimpleRandomSelectorFeature random) {
            data=new JsonObject();data.addProperty("kind","simple_selector");var choices=new JsonArray();int extent=0;
            for(var option:random.features()){var child=nested(option.value(),depth+1);if(child==null)return null;choices.add(child);extent=Math.max(extent,child.get("reach").getAsInt());}
            data.add("features",choices);data.addProperty("reach",extent);
        } else if(feature instanceof RandomBooleanSelectorFeature random) {
            var a=nested(random.featureTrue().value(),depth+1);var b=nested(random.featureFalse().value(),depth+1);if(a==null || b==null)return null;
            data=new JsonObject();data.addProperty("kind","boolean_selector");data.add("when_true",a);data.add("when_false",b);data.addProperty("reach",Math.max(a.get("reach").getAsInt(),b.get("reach").getAsInt()));
        } else if(feature instanceof WeightedRandomSelectorFeature random) {
            data=new JsonObject();data.addProperty("kind","weighted_selector");var choices=new JsonArray();int extent=0;
            for(var option:random.features().unwrap()){var child=nested(option.value().value(),depth+1);if(child==null)return null;var item=new JsonObject();item.addProperty("weight",option.weight());item.add("placed",child);choices.add(item);extent=Math.max(extent,child.get("reach").getAsInt());}
            data.add("entries",choices);data.addProperty("reach",extent);
        } else {unsupported.add("vegetation_patch:nested_feature:"+feature.getClass().getSimpleName());return null;}
        if(data==null)return null;
        var result=new JsonObject();result.add("placement",program);result.add("feature",data);result.addProperty("reach",reach+data.get("reach").getAsInt());return result;
    }
    private JsonObject fallen(FallenTreeFeature feature) {
        var json=Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),feature).getOrThrow().getAsJsonObject();
        if(!supportedIntProvider(json.get("log_length")) || extent(json.get("log_length"))>15) {unsupported.add("fallen_tree:log_length_exceeds_halo");return null;}
        var trunk=stateProgram(json.get("trunk_provider"),0);if(trunk==null)return null;
        if(nullableProvider(trunk)) {unsupported.add("fallen_tree:nullable_trunk_axis_transform");return null;}
        var stump=fallenDecorators(json.getAsJsonArray("stump_decorators"));var logs=fallenDecorators(json.getAsJsonArray("log_decorators"));
        if(stump==null || logs==null)return null;
        var result=new JsonObject();result.add("trunk",trunk);result.add("log_length",json.get("log_length").deepCopy());
        result.add("stump_decorators",stump);result.add("log_decorators",logs);
        var axes=new JsonArray();
        for(int id:programStates(trunk)) {
            var base=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
            var states=new JsonArray();for(var axis:List.of(Direction.Axis.X,Direction.Axis.Z))states.add(material(base.trySetValue(RotatedPillarBlock.AXIS,axis)));
            var a=new JsonObject();a.addProperty("source",id);a.add("states",states);axes.add(a);
        }
        result.add("axes",axes);
        var air=matching("matching_block_tag","tag","minecraft:air",0,0,0);
        result.add("clearance",combine("any_of",air.deepCopy(),matching("matching_block_tag","tag",BlockTags.REPLACEABLE_BY_TREES.location().toString(),0,0,0)));
        result.add("sturdy",matching("has_sturdy_face","direction","up",0,-1,0));result.add("air",air);
        result.add("replaceable",test("replaceable",0,0,0));
        result.add("water",matching("matching_blocks","blocks","minecraft:water",0,0,0));
        result.add("shelf",matching("matching_blocks","blocks","minecraft:shelf_mushroom",0,0,0));return result;
    }
    private JsonArray fallenDecorators(JsonArray input) {
        var output=new JsonArray();
        for(var value:input) {
            var json=value.getAsJsonObject();var d=new JsonObject();d.addProperty("type",type(json));
            switch(type(json)) {
                case "attached_to_logs" -> {
                    var provider=stateProgram(json.get("block_provider"),0);if(provider==null)return null;
                    d.add("probability",json.get("probability"));d.add("provider",provider);var directions=new JsonArray();
                    for(var v:json.getAsJsonArray("directions")) {var direction=Direction.valueOf(v.getAsString().toUpperCase(Locale.ROOT));directions.add(new Gson().toJsonTree(new int[]{direction.getStepX(),direction.getStepY(),direction.getStepZ()}));}
                    d.add("directions",directions);
                }
                case "trunk_vine" -> {
                    var states=new JsonArray();for(var property:List.of(VineBlock.EAST,VineBlock.WEST,VineBlock.SOUTH,VineBlock.NORTH))states.add(material(Blocks.VINE.defaultBlockState().setValue(property,true)));d.add("states",states);
                }
                case "shelf_mushroom" -> {
                    d.add("probability",json.get("probability"));var states=new JsonArray();
                    for(var direction:Direction.Plane.HORIZONTAL) {var ages=new JsonArray();for(int age=0;age<2;age++)ages.add(material(Blocks.SHELF_MUSHROOM.defaultBlockState().setValue(ShelfMushroomBlock.FACING,direction).setValue(ShelfMushroomBlock.AGE,age)));states.add(ages);}
                    d.add("states",states);
                }
                default -> {unsupported.add("fallen_tree:decorator:"+type(json));return null;}
            }
            output.add(d);
        }
        return output;
    }
    private JsonObject mushroom(AbstractHugeMushroomFeature feature) {
        if(feature.foliageRadius()<0 || feature.foliageRadius()>15) {unsupported.add("huge_mushroom:radius_exceeds_halo");return null;}
        var json=Feature.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),feature).getOrThrow().getAsJsonObject();
        if(!supportedPredicate(json.getAsJsonObject("can_place_on"))) {unsupported.add("huge_mushroom:can_place_on");return null;}
        var cap=stateProgram(json.get("cap_provider"),0);var stem=stateProgram(json.get("stem_provider"),0);
        if(cap==null || stem==null)return null;
        if(nullableProvider(cap)) {unsupported.add("huge_mushroom:nullable_cap_face_transform");return null;}
        boolean red=feature instanceof HugeRedMushroomFeature;
        var result=new JsonObject();result.addProperty("red",red);result.addProperty("foliage_radius",feature.foliageRadius());
        result.add("cap",cap);result.add("stem",stem);result.add("support",json.get("can_place_on").deepCopy());
        result.add("clearance",combine("any_of",matching("matching_block_tag","tag","minecraft:air",0,0,0),matching("matching_block_tag","tag",BlockTags.LEAVES.location().toString(),0,0,0)));
        result.add("replaceable",combine("any_of",matching("matching_block_tag","tag","minecraft:air",0,0,0),matching("matching_block_tag","tag",BlockTags.REPLACEABLE_BY_MUSHROOMS.location().toString(),0,0,0)));
        var variants=new JsonArray();
        for(int id:programStates(cap)) {
            var base=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
            boolean faces=base.hasProperty(HugeMushroomBlock.WEST)&&base.hasProperty(HugeMushroomBlock.EAST)&&base.hasProperty(HugeMushroomBlock.NORTH)&&base.hasProperty(HugeMushroomBlock.SOUTH)&&(!red || base.hasProperty(HugeMushroomBlock.UP));
            var states=new JsonArray();
            for(int mask=0;mask<32;mask++) {
                var state=base;
                if(faces) {
                    state=state.setValue(HugeMushroomBlock.WEST,(mask&1)!=0).setValue(HugeMushroomBlock.EAST,(mask&2)!=0)
                            .setValue(HugeMushroomBlock.NORTH,(mask&4)!=0).setValue(HugeMushroomBlock.SOUTH,(mask&8)!=0);
                    if(red)state=state.setValue(HugeMushroomBlock.UP,(mask&16)!=0);
                }
                states.add(material(state));
            }
            var v=new JsonObject();v.addProperty("source",id);v.add("states",states);variants.add(v);
        }
        result.add("faces",variants);return result;
    }
    private JsonObject stateProgram(JsonElement input,int depth) {
        if(depth>16)throw new IllegalArgumentException("Recursive block state provider");
        if(input.isJsonPrimitive() && input.getAsString().contains(":")) {
            var holder=registry.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(ResourceKey.create(Registries.BLOCK_STATE_PROVIDER,Identifier.parse(input.getAsString())));
            if(holder.isPresent())return stateProgram(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),holder.get().value()).getOrThrow(),depth+1);
        }
        var state=BlockState.CODEC.parse(JsonOps.INSTANCE,input).result();
        if(state.isPresent()) {var result=new JsonObject();result.addProperty("type","state");result.addProperty("material",material(state.get()));return result;}
        if(!input.isJsonObject()) {unsupported.add("block_provider:"+input);return null;}
        var object=input.getAsJsonObject();var result=new JsonObject();
        switch(type(object)) {
            case "simple", "simple_state_provider" -> {return stateProgram(object.get("state"),depth+1);}
            case "weighted", "weighted_state_provider" -> {
                result.addProperty("type","weighted");var entries=new JsonArray();
                for(var e:object.getAsJsonArray("entries")) {var entry=e.getAsJsonObject();var child=stateProgram(entry.get("data"),depth+1);if(child==null)return null;
                    if(nullableProvider(child)) {unsupported.add("block_provider:weighted_nullable_current_state");return null;}
                    var out=new JsonObject();out.add("weight",entry.get("weight"));out.add("provider",child);entries.add(out);}
                result.add("entries",entries);
            }
            case "randomized_int", "randomized_int_state_provider" -> {
                if(!supportedIntProvider(object.get("values"))) {unsupported.add("block_provider:int_values:"+object.get("values"));return null;}
                var source=stateProgram(object.get("source"),depth+1);if(source==null)return null;
                if(nullableProvider(source)) {unsupported.add("block_provider:randomized_nullable_current_state");return null;}
                result.addProperty("type","randomized_int");result.add("source",source);result.add("values",object.get("values").deepCopy());var variants=new JsonArray();
                for(int id:programStates(source)) {
                    var base=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
                    var property=base.getBlock().getStateDefinition().getProperty(object.get("property").getAsString());
                    if(!(property instanceof IntegerProperty integer)) {unsupported.add("block_provider:int_property:"+object.get("property"));return null;}
                    var values=integer.getPossibleValues().stream().sorted().toList();var states=new JsonArray();for(int v:values)states.add(material(base.setValue(integer,v)));
                    var variant=new JsonObject();variant.addProperty("source",id);variant.addProperty("minimum",values.getFirst());variant.add("states",states);variants.add(variant);
                }
                result.add("variants",variants);
            }
            case "rule_based" -> {
                result.addProperty("type","rule_based");var rules=new JsonArray();
                for(var e:object.getAsJsonArray("rules")) {
                    var rule=e.getAsJsonObject();
                    if(!supportedPredicate(rule.getAsJsonObject("if_true"))) {unsupported.add("block_provider:rule_predicate:"+rule.get("if_true"));return null;}
                    var child=stateProgram(rule.get("then"),depth+1);if(child==null)return null;
                    var out=new JsonObject();out.add("predicate",rule.get("if_true").deepCopy());out.add("provider",child);rules.add(out);
                }
                result.add("rules",rules);
                if(object.has("fallback")) {var child=stateProgram(object.get("fallback"),depth+1);if(child==null)return null;result.add("fallback",child);}
            }
            case "rotated" -> {
                var source=stateProgram(object.get("state"),depth+1);if(source==null)return null;
                if(nullableProvider(source)) {unsupported.add("block_provider:rotated_nullable_current_state");return null;}
                result.addProperty("type","rotated");result.add("source",source);var variants=new JsonArray();
                if(object.has("direction"))result.addProperty("direction",Direction.valueOf(object.get("direction").getAsString().toUpperCase(Locale.ROOT)).ordinal());
                for(int id:programStates(source)) {
                    var base=materials.entrySet().stream().filter(e->e.getValue()==id).findFirst().orElseThrow().getKey();
                    var states=new JsonArray();for(var direction:Direction.values()) {
                        var rotated=base.trySetValue(BlockStateProperties.AXIS,direction.getAxis()).trySetValue(BlockStateProperties.FACING,direction);
                        if(direction.getAxis().isHorizontal())rotated=rotated.trySetValue(BlockStateProperties.HORIZONTAL_FACING,direction);
                        states.add(material(rotated));
                    }
                    var v=new JsonObject();v.addProperty("source",id);v.add("states",states);variants.add(v);
                }
                result.add("variants",variants);
            }
            case "random_block" -> {
                var decoded=(RandomBlockProvider)BlockStateProvider.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),object).getOrThrow();
                result.addProperty("type","random_block");var states=new JsonArray();for(var block:decoded.blocks())states.add(material(block.value().defaultBlockState()));result.add("states",states);
            }
            case "noise", "noise_provider", "dual_noise", "dual_noise_provider" -> {
                boolean dual=type(object).startsWith("dual_noise");result.addProperty("type",dual?"dual_noise":"noise");
                result.addProperty(dual?"fast":"program",providerNoise.register(object,false));
                if(dual) {
                    result.addProperty("slow",providerNoise.register(object,true));var variety=new JsonArray();
                    var bounds=net.minecraft.util.InclusiveRange.codec(com.mojang.serialization.Codec.INT,1,64).parse(JsonOps.INSTANCE,object.get("variety")).getOrThrow();
                    variety.add(bounds.minInclusive());variety.add(bounds.maxInclusive());result.add("variety",variety);
                }
                result.add("states",noiseStates(object.getAsJsonArray("states")));
            }
            case "noise_threshold" -> {
                result.addProperty("type","noise_threshold");result.addProperty("program",providerNoise.register(object,false));
                result.add("threshold",object.get("threshold"));result.add("high_chance",object.get("high_chance"));
                result.addProperty("default_state",material(BlockState.CODEC.parse(JsonOps.INSTANCE,object.get("default_state")).getOrThrow()));
                for(String key:List.of("low_states","high_states"))result.add(key,noiseStates(object.getAsJsonArray(key)));
            }
            default -> {unsupported.add("block_provider:"+type(object));return null;}
        }
        return result;
    }
    private JsonArray noiseStates(JsonArray states) {
        var ids=new JsonArray();for(var state:states)ids.add(material(BlockState.CODEC.parse(JsonOps.INSTANCE,state).getOrThrow()));return ids;
    }
    private boolean contextProvider(JsonElement input,int depth) {
        if(depth>16)throw new IllegalArgumentException("Recursive block state provider");
        if(input.isJsonPrimitive() && input.getAsJsonPrimitive().isString()) {
            var holder=registry.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(ResourceKey.create(Registries.BLOCK_STATE_PROVIDER,Identifier.parse(input.getAsString())));
            return holder.isPresent() && contextProvider(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),holder.get().value()).getOrThrow(),depth+1);
        }
        if(!input.isJsonObject())return false;
        var object=input.getAsJsonObject();
        return switch(type(object)) {
            case "rule_based", "rotated", "random_block", "noise", "noise_provider", "dual_noise", "dual_noise_provider", "noise_threshold" -> true;
            case "weighted", "weighted_state_provider" -> object.getAsJsonArray("entries").asList().stream().anyMatch(e->contextProvider(e.getAsJsonObject().get("data"),depth+1));
            case "randomized_int", "randomized_int_state_provider" -> contextProvider(object.get("source"),depth+1);
            default -> false;
        };
    }
    private static boolean nullableProvider(JsonObject provider) {
        return switch(provider.get("type").getAsString()) {
            case "rule_based" -> !provider.has("fallback") || nullableProvider(provider.getAsJsonObject("fallback"));
            case "random_block" -> provider.getAsJsonArray("states").isEmpty();
            default -> false;
        };
    }
    static Set<Integer> featureMaterials(JsonObject recipe) {
        var out=new LinkedHashSet<Integer>();
        switch(recipe.get("kind").getAsString()) {
            case "vegetation_patch" -> {out.addAll(programStates(recipe.getAsJsonObject("ground")));out.addAll(featureMaterials(recipe.getAsJsonObject("vegetation").getAsJsonObject("feature")));}
            case "block_column" -> {for(var layer:recipe.getAsJsonArray("layers"))out.addAll(programStates(layer.getAsJsonObject().getAsJsonObject("provider")));}
            case "simple_block" -> out.addAll(programStates(recipe.getAsJsonObject("provider")));
            case "simple_selector" -> {for(var child:recipe.getAsJsonArray("features"))out.addAll(featureMaterials(child.getAsJsonObject().getAsJsonObject("feature")));}
            case "random_selector" -> {for(var child:recipe.getAsJsonArray("options"))out.addAll(featureMaterials(child.getAsJsonObject().getAsJsonObject("placed").getAsJsonObject("feature")));out.addAll(featureMaterials(recipe.getAsJsonObject("default").getAsJsonObject("feature")));}
            case "boolean_selector" -> {for(String key:List.of("when_true","when_false"))out.addAll(featureMaterials(recipe.getAsJsonObject(key).getAsJsonObject("feature")));}
            case "weighted_selector" -> {for(var child:recipe.getAsJsonArray("entries"))out.addAll(featureMaterials(child.getAsJsonObject().getAsJsonObject("placed").getAsJsonObject("feature")));}
        }
        return out;
    }
    static Set<Integer> programStates(JsonObject p) {
        var out=new LinkedHashSet<Integer>();
        switch(p.get("type").getAsString()) {
            case "state" -> out.add(p.get("material").getAsInt());
            case "weighted" -> {for(var e:p.getAsJsonArray("entries"))out.addAll(programStates(e.getAsJsonObject().getAsJsonObject("provider")));}
            case "randomized_int" -> {for(var e:p.getAsJsonArray("variants"))for(var id:e.getAsJsonObject().getAsJsonArray("states"))out.add(id.getAsInt());}
            case "rotated" -> {for(var e:p.getAsJsonArray("variants"))for(var id:e.getAsJsonObject().getAsJsonArray("states"))out.add(id.getAsInt());}
            case "random_block", "noise", "dual_noise" -> {for(var id:p.getAsJsonArray("states"))out.add(id.getAsInt());}
            case "noise_threshold" -> {out.add(p.get("default_state").getAsInt());for(String key:List.of("low_states","high_states"))for(var id:p.getAsJsonArray(key))out.add(id.getAsInt());}
            case "rule_based" -> {for(var rule:p.getAsJsonArray("rules"))out.addAll(programStates(rule.getAsJsonObject().getAsJsonObject("provider")));if(p.has("fallback"))out.addAll(programStates(p.getAsJsonObject("fallback")));}
        }
        return out;
    }

    private static JsonObject predicate(JsonObject input, LinkedHashMap<BlockState, Integer> palette) {
        String kind = type(input);
        if(kind.equals("would_survive")) {
            var state=BlockState.CODEC.parse(JsonOps.INSTANCE,input.get("state")).getOrThrow();
            var p=survivalPredicate(state);shiftPredicate(p,input.has("offset")?new Gson().fromJson(input.get("offset"),int[].class):new int[3]);
            return predicate(p,palette);
        }
        var output = new JsonObject();
        output.addProperty("type", kind);
        if (kind.equals("all_of") || kind.equals("any_of")) {
            var children = new JsonArray();
            for (var child : input.getAsJsonArray("predicates")) children.add(predicate(child.getAsJsonObject(), palette));
            output.add("predicates", children);
            return output;
        }
        if (kind.equals("not")) {
            output.add("predicate", predicate(input.getAsJsonObject("predicate"), palette));
            return output;
        }
        if (kind.equals("true")) return output;
        int[] offset = input.has("offset") ? new Gson().fromJson(input.get("offset"), int[].class) : new int[3];
        var allowed = new JsonArray();
        for (var entry : palette.entrySet()) {
            var state = entry.getKey();
            boolean matches = switch (kind) {
                case "matching_blocks" -> matchesBlock(input.get("blocks"), state);
                case "matching_block_tag" -> state.is(TagKey.create(Registries.BLOCK, Identifier.parse(input.get("tag").getAsString())));
                case "matching_fluids" -> matchesFluid(input.get("fluids"), state);
                case "replaceable" -> state.canBeReplaced();
                case "has_sturdy_face" -> state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.valueOf(input.get("direction").getAsString().toUpperCase(Locale.ROOT)));
                case "full_water" -> state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isFull();
                case "source_water" -> state.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);
                case "supports_sea_pickle" -> !state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).getFaceShape(Direction.UP).isEmpty() || state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP);
                case "supports_snow" -> supportsSnow(state);
                case "same_fluid_replaceable" -> state.canBeReplaced() && state.getFluidState().equals(BlockState.CODEC.parse(JsonOps.INSTANCE,input.get("state")).getOrThrow().getFluidState());
                case "solid_or_lava" -> state.isSolid() || state.getFluidState().is(FluidTags.LAVA);
                case "solid" -> state.isSolid();
                case "liquid" -> state.liquid();
                default -> throw new IllegalArgumentException("Unsupported decoration predicate " + input);
            };
            if (matches) allowed.add(entry.getValue());
        }
        output.addProperty("type", "material");
        output.add("offset", new Gson().toJsonTree(offset));
        output.add("allowed", allowed);
        return output;
    }

    static boolean matchesBlock(JsonElement choices, BlockState state) {
        if (choices.isJsonArray()) return choices.getAsJsonArray().asList().stream().anyMatch(c -> matchesBlock(c, state));
        String id = choices.getAsString();
        return id.startsWith("#") ? state.is(TagKey.create(Registries.BLOCK, Identifier.parse(id.substring(1))))
                : BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(Identifier.parse(id));
    }

    static boolean supportsSnow(BlockState state) {
        return !state.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER)
                && (state.is(BlockTags.SUPPORT_OVERRIDE_SNOW_LAYER)
                || Block.isFaceFull(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP)
                || state.is(Blocks.SNOW) && state.getValue(SnowLayerBlock.LAYERS) == 8);
    }

    private static boolean matchesFluid(JsonElement choices, BlockState state) {
        if (choices.isJsonArray()) return choices.getAsJsonArray().asList().stream().anyMatch(c -> matchesFluid(c, state));
        String id = choices.getAsString();
        return id.startsWith("#") ? state.getFluidState().is(TagKey.create(Registries.FLUID, Identifier.parse(id.substring(1))))
                : BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).equals(Identifier.parse(id));
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
            data.addProperty("placement_salt", p.placementSalt); data.add("placement", p.program.deepCopy());
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
        if(type(object).equals("noise") || type(object).equals("dual_noise")) {
            providerNoise.register(object,false);
            if(type(object).equals("dual_noise"))providerNoise.register(object,true);
        }
        switch (type(object)) {
            case "weighted", "weighted_state_provider" -> { for (var e : object.getAsJsonArray("entries")) { var entry = e.getAsJsonObject(); result.addAll(provider(entry.get("data"), weight * entry.get("weight").getAsDouble(), band, depth + 1)); } }
            case "noise_threshold" -> {
                providerNoise.register(object,false);
                var low = object.getAsJsonArray("low_states"); var high = object.getAsJsonArray("high_states");
                for (var e : low) result.addAll(provider(e, weight / low.size(), 1, depth + 1));
                double chance = object.get("high_chance").getAsDouble();
                result.addAll(provider(object.get("default_state"), weight * (1 - chance), 2, depth + 1));
                for (var e : high) result.addAll(provider(e, weight * chance / high.size(), 2, depth + 1));
            }
            case "noise_provider", "dual_noise_provider" -> { providerNoise.register(object,false);if(type(object).startsWith("dual_noise"))providerNoise.register(object,true);for (var e : object.getAsJsonArray("states")) result.addAll(provider(e, weight, band, depth + 1)); }
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
    static double mean(JsonElement value) {
        if (value.isJsonPrimitive()) return value.getAsDouble();
        var object = value.getAsJsonObject();
        if (object.has("distribution")) { double sum = 0, weight = 0; for (var e : object.getAsJsonArray("distribution")) { var w = e.getAsJsonObject(); double n = w.get("weight").getAsDouble(); sum += n * mean(w.get("data")); weight += n; } return sum / weight; }
        if (type(object).equals("constant")) return object.get("value").getAsDouble();
        if (type(object).equals("clamped_normal")) return Math.clamp(object.get("mean").getAsDouble(), object.get("min_inclusive").getAsDouble(), object.get("max_inclusive").getAsDouble());
        if (type(object).equals("clamped")) {
            double min = object.get("min_inclusive").getAsDouble(), max = object.get("max_inclusive").getAsDouble();
            var source = object.get("source");
            if (source.isJsonObject() && type(source.getAsJsonObject()).equals("uniform")) { var distribution = source.getAsJsonObject(); int low = distribution.get("min_inclusive").getAsInt(), high = distribution.get("max_inclusive").getAsInt(); double sum = 0; for (int i = low; i <= high; i++) sum += Math.clamp(i, min, max); return sum / (high - low + 1); }
            return Math.clamp(mean(source), min, max);
        }
        if (object.has("min_inclusive")) return (object.get("min_inclusive").getAsDouble() + object.get("max_inclusive").getAsDouble()) / 2;
        if (object.has("min")) return (object.get("min").getAsDouble() + object.get("max").getAsDouble()) / 2;
        throw new IllegalArgumentException("Unsupported decoration count: " + value);
    }

    static void materialFlags(JsonObject profile, LinkedHashMap<BlockState, Integer> palette) {
        profile.addProperty("ordered_decorations",true);
        profile.addProperty("decoration_biome_3d",true);
        boolean patchHalo = false;
        for (var recipe : profile.getAsJsonArray("decorations")) {
            if (recipe.getAsJsonObject().get("kind").getAsString().equals("vegetation_patch")) patchHalo = true;
        }
        profile.addProperty("decoration_patch_halo", patchHalo);
        var heightmaps = new JsonArray(); var flags = new JsonArray(); var halves = new JsonArray(); var floorMasks = new JsonArray(); var snowSupport = new JsonArray();
        var plantTypes = new LinkedHashMap<Block, Integer>();
        for (var state : palette.keySet()) {
            int mask = 0; for (var type : Heightmap.Types.values()) if (type.isOpaque().test(state)) mask |= 1 << type.ordinal();
            heightmaps.add(mask);
            snowSupport.add(supportsSnow(state));
            int flag = state.is(BlockTags.SUPPORTS_VEGETATION) ? 1 : 0;
            if (state.is(BlockTags.SUPPORTS_DRY_VEGETATION)) flag |= 2;
            if (state.is(BlockTags.LEAVES)) flag |= 4;
            if (state.is(BlockTags.LOGS)) flag |= 8;
            if (state.getBlock() instanceof VegetationBlock) flag |= 16;
            if (state.is(BlockTags.JUNGLE_LOGS)) flag |= 32;
            if (state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA)) flag |= 64;
            if (state.is(Blocks.BEDROCK)) flag |= 128;
            flags.add(flag);
            // Partner validation depends on block identity and opposite halves;
            // facing/waterlogging may differ. Export registered classes, not names.
            int half = 0;
            if (state.getBlock() instanceof DoublePlantBlock) {
                half = plantTypes.computeIfAbsent(state.getBlock(), ignored -> plantTypes.size() + 1);
                if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) half = -half;
            }
            halves.add(half);
            // These registered classes use VegetationBlock's loaded soil tag.
            // Specialized aquatics/dripleaf and custom survival overrides keep
            // their own adapters; upper halves depend on the matching lower half.
            boolean ordinaryFloor = state.getBlock().getClass() == TallGrassBlock.class
                    || state.getBlock().getClass() == DoublePlantBlock.class && half > 0;
            floorMasks.add(ordinaryFloor ? 1 : 0);
        }
        profile.add("heightmap_masks", heightmaps); profile.add("material_flags", flags);
        profile.add("plant_halves", halves);
        profile.add("plant_floor_masks", floorMasks);
        profile.add("snow_support", snowSupport);
        profile.add("decoration_noise", new DecorationNoise().export());
    }

    /** Minecraft's actual seeded placement permutation; spatial sampling stays on the GPU. */
    private static final class DecorationNoise extends net.minecraft.world.level.levelgen.synth.SimplexNoise {
        private DecorationNoise() {
            super(new net.minecraft.world.level.levelgen.WorldgenRandom(
                    new net.minecraft.world.level.levelgen.LegacyRandomSource(2345L)), true);
        }
        JsonObject export() {
            var result = new JsonObject(); var permutation = new JsonArray();
            for (byte p : perms) permutation.add(Byte.toUnsignedInt(p));
            result.add("permutation", permutation);
            return result;
        }
    }
}
