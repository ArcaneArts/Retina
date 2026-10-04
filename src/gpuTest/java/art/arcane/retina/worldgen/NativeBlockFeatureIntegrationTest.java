package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.SimpleBitStorage;
import java.lang.foreign.ValueLayout;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.feature.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Compare exported recipes with Minecraft's actual feature implementations.
 * Both implementations receive the same controlled random stream and terrain;
 * this tests geometry/providers, not equivalence with Minecraft's seed RNG. */
public final class NativeBlockFeatureIntegrationTest {
    private static final Set<String> ADAPTERS=Set.of("block_column","bamboo","aquatic","huge_mushroom","fallen_tree","vegetation_patch","simple_block");
    public static void main(String[] args) throws Exception {
        Path vanilla=Path.of("build/registered-columns-vanilla.json");
        NativeProfileExport.main(new String[]{vanilla.toString()});
        check(vanilla,null);
        if(args.length>0) {
            Path packed=Path.of("build/registered-columns-terralith.json");
            NativeProfileExport.main(new String[]{packed.toString(),args[0]});
            check(packed,Path.of(args[0]));
        }
    }
    private static void check(Path file,Path pack) throws Exception {
        var stack=new ArrayList<PackResources>();stack.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if(pack!=null)stack.add((PackResources)new FilePackResources.FileResourcesSupplier(pack).openMetadata(new PackLocationInfo("reference-pack",Component.literal("Reference pack"),PackSource.DEFAULT,Optional.empty())));
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,stack)) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var json=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            var materials=json.getAsJsonArray("materials").asList().stream().map(e->BlockState.CODEC.parse(JsonOps.INSTANCE,e).getOrThrow()).toArray(BlockState[]::new);
            var placementCases=placementCases(registry,json,materials);
            var fixtures=List.of(new Fixture(json,materials,96,384),new Fixture(json,materials,32,384),new Fixture(json,materials,58,384),new Fixture(json,materials,96,176),new Fixture(json,materials,96,168),new Fixture(json,materials,96,384,true),new Fixture(json,materials,90,384,false,18),new Fixture(json,materials,90,384,false,30));
            var factory=PalettedContainerFactory.create(registry);
            for(var fixture:fixtures) {
                fixture.factory=factory;
                var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
                fixture.generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),fixture.request.minY(),(fixture.request.height()+15)/16*16,fixture.base,0,.008F,"mca");
            }
            checkPlantPartners(json, materials, fixtures.getFirst());
            checkPlacements(registry, placementCases, fixtures);
            var kinds=new TreeMap<String,Integer>();int cases=0,placed=0,empty=0,ruggedMushrooms=0,ruggedRejected=0;
            var tipAges=new TreeSet<Integer>();var bambooHeights=new TreeSet<Integer>();var mushroomHeights=new TreeSet<Integer>();
            var fallenLengths=new TreeSet<Integer>();var fallenAxes=new HashSet<Direction.Axis>();int fallenDecorated=0,fallenStumps=0,ruggedFallenLogs=0;
            var recipes=json.getAsJsonArray("decorations");
            for(int id=0;id<recipes.size();id++) {
                var recipe=recipes.get(id).getAsJsonObject();String kind=recipe.get("kind").getAsString();
                if(!ADAPTERS.contains(kind))continue;
                String name=recipe.get("source").getAsString();Feature feature=resolve(registry,name);
                require(feature!=null,"registered source resolves: "+name);kinds.merge(kind,1,Integer::sum);
                for(var fixture:fixtures)for(long seed=0;seed<32;seed++) {
                    if(fixture.cavern && !kind.equals("vegetation_patch") && !kind.equals("simple_block"))continue;
                    // Straddle negative chunk boundaries; all decisions use the same
                    // actual material/height substrate as the Rust feature sampler.
                    int[] at={-17,fixture.originHeight,-17};
                    if(kind.equals("aquatic") && feature instanceof SimpleBlockFeature simple && simple.toPlace().value().getState(new World(fixture).level,new Stream(1,true),BlockPos.ZERO).is(Blocks.LILY_PAD))at[1]=63;
                    var world=new World(fixture);var origin=new BlockPos(at[0],at[1],at[2]);
                    feature.place(world.level,fixture.generator,new Stream(seed,kind.equals("aquatic")),origin);
                    int[] output=NativeTerrain.instance().decorationFeature(fixture.request,id,at,seed);
                    var actual=new HashMap<BlockPos,BlockState>();
                    for(int i=0;i<output.length;i+=4)actual.put(new BlockPos(output[i],output[i+1],output[i+2]),materials[output[i+3]]);
                    require(world.changed.equals(actual),"Minecraft feature differs: "+name+"/base="+fixture.base+"/height="+fixture.request.height()+"/seed="+seed+" differences="+differences(world.changed,actual));
                    cases++;if(actual.isEmpty())empty++;else placed++;
                    for(var state:actual.values())if(state.is(Blocks.KELP))tipAges.add(state.getValue(KelpBlock.AGE));
                    if(kind.equals("bamboo") && !actual.isEmpty())bambooHeights.add(actual.keySet().stream().filter(p->actual.get(p).is(Blocks.BAMBOO)).mapToInt(BlockPos::getY).max().orElse(96)-96);
                    if(kind.equals("huge_mushroom")) {
                        if(!actual.isEmpty())mushroomHeights.add(actual.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow()-at[1]);
                        if(fixture.rugged){if(actual.isEmpty())ruggedRejected++;else ruggedMushrooms++;}
                    }
                    if(kind.equals("fallen_tree")) {
                        var logs=actual.values().stream().filter(s->s.is(BlockTags.LOGS)).toList();
                        int horizontal=0;
                        for(var state:logs)if(state.hasProperty(RotatedPillarBlock.AXIS)) {
                            var axis=state.getValue(RotatedPillarBlock.AXIS);if(axis!=Direction.Axis.Y){horizontal++;fallenAxes.add(axis);}
                        }
                        fallenLengths.add(horizontal);if(horizontal==0)fallenStumps++;
                        if(fixture.rugged && horizontal>0)ruggedFallenLogs++;
                        if(actual.size()>logs.size())fallenDecorated++;
                    }
                }
            }
            require(kinds.getOrDefault("block_column",0)>0 && kinds.getOrDefault("bamboo",0)>0 && kinds.getOrDefault("aquatic",0)>0 && kinds.getOrDefault("huge_mushroom",0)>0 && kinds.getOrDefault("fallen_tree",0)>0,"common registered adapters exported: "+kinds);
            require(tipAges.equals(new TreeSet<>(List.of(20,21,22,23))),"randomized kelp tip age distribution retained: "+tipAges);
            require(bambooHeights.size()>5 && placed>100 && empty>100,"varied heights, survival and truncation exercised");
            require(mushroomHeights.equals(new TreeSet<>(List.of(4,5,6,8,10,12))) && ruggedMushrooms>0 && ruggedRejected>0,"mushroom heights and terrain clearance exercised: "+mushroomHeights+"/"+ruggedMushrooms+"/"+ruggedRejected);
            require(fallenLengths.size()>8 && fallenAxes.equals(Set.of(Direction.Axis.X,Direction.Axis.Z)) && fallenDecorated>0 && fallenStumps>0 && ruggedFallenLogs>0,"fallen log lengths, axes, decorators and rejected runs exercised: "+fallenLengths+"/"+fallenStumps+"/"+ruggedFallenLogs);
            System.out.println("QA_EVT {\"event\":\"registered_block_features_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+(pack!=null)+",\"recipes\":"+new Gson().toJson(kinds)+",\"cases\":"+cases+",\"placed\":"+placed+",\"empty\":"+empty+",\"mushroom_heights\":"+new Gson().toJson(mushroomHeights)+",\"rugged_mushrooms\":"+ruggedMushrooms+",\"rugged_rejections\":"+ruggedRejected+"}}");
            System.out.println("QA_EVT {\"event\":\"registered_fallen_trees_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+(pack!=null)+",\"horizontal_lengths\":"+new Gson().toJson(fallenLengths)+",\"decorated\":"+fallenDecorated+",\"stump_only\":"+fallenStumps+",\"rugged_runs\":"+ruggedFallenLogs+"}}");
            for(String biome:List.of("bamboo_jungle","desert","ocean","mushroom_fields","dark_forest","forest","dappled_forest","old_growth_birch_forest","lush_caves"))checkRegion(json,materials,biome);
            checkStateProviders(registry,json,factory,pack!=null);

        }
    }
    private static void checkStateProviders(RegistryAccess registry,JsonObject original,PalettedContainerFactory factory,boolean packed) throws Exception {
        var profile=original.deepCopy();profile.remove("ores");profile.remove("cave_noises");
        var palette=new LinkedHashMap<BlockState,Integer>();
        for(var value:profile.getAsJsonArray("materials"))palette.put(BlockState.CODEC.parse(JsonOps.INSTANCE,value).getOrThrow(),palette.size());
        var constructor=DecorationProfile.class.getDeclaredConstructor(HolderLookup.Provider.class,LinkedHashMap.class);constructor.setAccessible(true);
        var exporter=constructor.newInstance(registry,palette);
        var placementClass=Arrays.stream(DecorationProfile.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Placement")).findFirst().orElseThrow();
        var placementConstructor=placementClass.getDeclaredConstructor();placementConstructor.setAccessible(true);
        var export=DecorationProfile.class.getDeclaredMethod("placed",net.minecraft.world.level.levelgen.placement.PlacedFeature.class,placementClass,double.class,String.class,List.class,int.class);export.setAccessible(true);
        var recipeField=DecorationProfile.class.getDeclaredField("recipes");recipeField.setAccessible(true);
        var rules=List.of(
            "{\"type\":\"minecraft:rule_based\",\"rules\":[{\"if_true\":{\"type\":\"minecraft:matching_blocks\",\"blocks\":\"minecraft:grass_block\",\"offset\":[0,-1,0]},\"then\":\"minecraft:stone\"},{\"if_true\":{\"type\":\"minecraft:matching_blocks\",\"blocks\":\"minecraft:stone\",\"offset\":[0,-1,0]},\"then\":\"minecraft:dirt\"}]}",
            "{\"type\":\"minecraft:rule_based\",\"fallback\":\"minecraft:birch_log\",\"rules\":[{\"if_true\":{\"type\":\"minecraft:true\"},\"then\":{\"type\":\"minecraft:rule_based\",\"rules\":[]}},{\"if_true\":{\"type\":\"minecraft:matching_block_tag\",\"tag\":\"minecraft:supports_vegetation\",\"offset\":[0,-1,0]},\"then\":{\"type\":\"minecraft:random_block\",\"blocks\":[\"minecraft:oak_log\",\"minecraft:spruce_log\"]}}]}",
            "{\"type\":\"minecraft:rule_based\",\"rules\":[{\"if_true\":{\"type\":\"minecraft:matching_fluids\",\"fluids\":\"minecraft:water\"},\"then\":\"minecraft:stone\"}]}",
            "{\"type\":\"minecraft:random_block\",\"blocks\":[]}",
            "{\"type\":\"minecraft:random_block\",\"blocks\":\"#minecraft:logs\"}",
            "{\"type\":\"minecraft:rotated\",\"state\":{\"type\":\"minecraft:weighted\",\"entries\":[{\"weight\":2,\"data\":\"minecraft:oak_log\"},{\"weight\":3,\"data\":\"minecraft:birch_log\"}]}}",
            "{\"type\":\"minecraft:rotated\",\"state\":{\"type\":\"minecraft:random_block\",\"blocks\":[\"minecraft:oak_stairs\",\"minecraft:furnace\"]}}"
        );
        var providers=new ArrayList<JsonElement>();for(var rule:rules)providers.add(providerStates(JsonParser.parseString(rule)));
        for(var direction:Direction.values()) {
            var provider=providerStates(JsonParser.parseString(rules.get(5))).getAsJsonObject();provider.addProperty("direction",direction.getName());providers.add(provider);
        }
        var synthetic=(JsonArray)recipeField.get(exporter);var features=new ArrayList<Feature>();
        for(var provider:providers)for(String kind:List.of("block_column","simple_block")) {
            var featureJson=new JsonObject();featureJson.addProperty("type","minecraft:"+kind);
            if(kind.equals("block_column")) {
                var layers=new JsonArray();var layer=new JsonObject();layer.add("height",JsonParser.parseString("{\"type\":\"minecraft:uniform\",\"min_inclusive\":3,\"max_inclusive\":9}"));layer.add("provider",provider.deepCopy());layers.add(layer);
                // A second randomized layer exposes any extra/missing provider RNG draws.
                var tail=new JsonObject();tail.addProperty("height",2);tail.add("provider",providerStates(JsonParser.parseString(rules.get(5))));layers.add(tail);
                featureJson.add("layers",layers);featureJson.addProperty("direction","up");featureJson.addProperty("prioritize_tip",false);featureJson.add("allowed_placement",JsonParser.parseString("{\"type\":\"minecraft:true\"}"));
            } else featureJson.add("to_place",provider.deepCopy());
            var feature=Feature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),featureJson).getOrThrow();
            var selected=new ArrayList<Integer>();var placed=new net.minecraft.world.level.levelgen.placement.PlacedFeature(Holder.direct(feature),List.of(
                net.minecraft.world.level.levelgen.placement.InSquarePlacement.spread(),net.minecraft.world.level.levelgen.placement.HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING)));
            export.invoke(exporter,placed,placementConstructor.newInstance(),1.0,"test:provider"+features.size(),selected,0);
            require(selected.size()==1 && synthetic.get(selected.getFirst()).getAsJsonObject().get("kind").getAsString().equals(kind),"registered provider feature exports through production dispatch: "+featureJson);
            features.add(feature);
        }
        DecorationProfile.finishPlacements(synthetic,palette);DecorationProfile.materialFlags(profile,palette);
        var materials=palette.keySet().toArray(BlockState[]::new);var serialized=new JsonArray();for(var state:materials)serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());profile.add("materials",serialized);
        int offset=profile.getAsJsonArray("decorations").size();profile.getAsJsonArray("decorations").addAll(synthetic);
        int checked=0,empty=0;
        for(int[] terrain:List.of(new int[]{32,384},new int[]{58,384},new int[]{96,384},new int[]{96,168})) {
            int base=terrain[0];var fixture=new Fixture(profile,materials,base,terrain[1]);fixture.factory=factory;
            var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
            fixture.generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),-64,384,base,0,.008F,"mca");
            for(int i=0;i<features.size();i++)for(long seed=0;seed<64;seed++) {
                var at=new BlockPos(-17,fixture.originHeight,-17);var world=new World(fixture);
                features.get(i).place(world.level,fixture.generator,new Stream(seed,false),at);
                int[] blocks=NativeTerrain.instance().decorationFeature(fixture.request,offset+i,new int[]{at.getX(),at.getY(),at.getZ()},seed);
                var actual=new HashMap<BlockPos,BlockState>();for(int k=0;k<blocks.length;k+=4)actual.put(new BlockPos(blocks[k],blocks[k+1],blocks[k+2]),materials[blocks[k+3]]);
                require(world.changed.equals(actual),"state-provider reference mismatch: "+synthetic.get(i)+" base="+base+" seed="+seed+" differences="+differences(world.changed,actual));
                checked++;if(actual.isEmpty())empty++;
            }
        }
        require(empty>0,"nullable providers skip simple-block placement");
        var ids=new JsonArray();for(int i=0;i<features.size();i++)ids.add(offset+i);
        for(var b:profile.getAsJsonArray("biomes"))if(b.getAsJsonObject().get("id").getAsString().equals("minecraft:forest"))b.getAsJsonObject().add("decorations",ids);
        // This is a controlled, cave-free carrier for the provider recipes; its
        // imported replacement tables describe the old, smaller palette.
        for(var b:profile.getAsJsonArray("biomes"))b.getAsJsonObject().remove("cave_features");
        checkRegion(profile,materials,"forest");
        System.out.println("QA_EVT {\"event\":\"registered_state_providers_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"features\":"+features.size()+",\"cases\":"+checked+",\"empty\":"+empty+"}}");
    }
    private static JsonElement providerStates(JsonElement value) {
        if(value.isJsonPrimitive()) {
            var state=BuiltInRegistries.BLOCK.getValue(Identifier.parse(value.getAsString())).defaultBlockState();
            return BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow();
        }
        var object=value.getAsJsonObject();
        for(String key:List.of("state","source","fallback"))if(object.has(key))object.add(key,providerStates(object.get(key)));
        for(String key:List.of("entries","rules"))if(object.has(key))for(var child:object.getAsJsonArray(key)) {
            var e=child.getAsJsonObject();var slot=key.equals("rules")?"then":"data";e.add(slot,providerStates(e.get(slot)));
        }
        return object;
    }
    private record PlacementCase(int recipe,List<net.minecraft.world.level.levelgen.placement.PlacementModifier> modifiers) { }
    private static List<PlacementCase> placementCases(RegistryAccess registry,JsonObject profile,BlockState[] materials) {
        var ops=registry.createSerializationContext(JsonOps.INSTANCE);
        var raw=new LinkedHashMap<String,List<net.minecraft.world.level.levelgen.placement.PlacementModifier>>();
        for(var holder:registry.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList())for(var modifier:holder.value().placement()) {
            var json=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.encodeStart(ops,modifier).getOrThrow().getAsJsonObject();
            String type=json.get("type").getAsString().replace("minecraft:","");
            boolean supported=switch(type) {
                case "height_range" -> DecorationProfile.supportedHeightProvider(json.get("height"));
                case "random_chance", "surface_relative_threshold_filter" -> true;
                case "environment_scan" -> DecorationProfile.supportedPredicate(json.getAsJsonObject("target_condition")) && (!json.has("allowed_search_condition") || DecorationProfile.supportedPredicate(json.getAsJsonObject("allowed_search_condition")));
                default -> false;
            };
            if(supported)raw.putIfAbsent(json.toString(),List.of(modifier));
        }
        // Exercise every supported height codec even when the active pack does
        // not use that distribution, with anchors relative to these build bounds.
        for(String height:List.of("{\"above_bottom\":7}","{\"below_top\":9}",
                "{\"type\":\"minecraft:biased_to_bottom\",\"min_inclusive\":{\"above_bottom\":5},\"max_inclusive\":{\"below_top\":4},\"inner\":3}",
                "{\"type\":\"minecraft:very_biased_to_bottom\",\"min_inclusive\":{\"above_bottom\":5},\"max_inclusive\":{\"below_top\":4},\"inner\":3}",
                "{\"type\":\"minecraft:trapezoid\",\"min_inclusive\":{\"above_bottom\":5},\"max_inclusive\":{\"below_top\":4},\"plateau\":17}",
                "{\"type\":\"minecraft:weighted_list\",\"distribution\":[{\"weight\":2,\"data\":{\"absolute\":22}},{\"weight\":3,\"data\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":{\"above_bottom\":5},\"max_inclusive\":{\"below_top\":4}}}]}")) {
            var json=new JsonObject();json.addProperty("type","minecraft:height_range");json.add("height",JsonParser.parseString(height));
            raw.put(json.toString(),List.of(net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,json).getOrThrow()));
        }
        for(int direction:new int[]{-1,1})for(int limit:new int[]{1,2,12,32}) {
            var scan=new JsonObject();scan.addProperty("type","minecraft:environment_scan");scan.addProperty("direction_of_search",direction<0?"down":"up");scan.addProperty("max_steps",limit);
            scan.add("allowed_search_condition",JsonParser.parseString("{\"type\":\"minecraft:matching_block_tag\",\"tag\":\"minecraft:air\"}"));
            scan.add("target_condition",JsonParser.parseString("{\"type\":\"minecraft:solid\"}"));
            var modifier=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,scan).getOrThrow();
            var offset=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,JsonParser.parseString("{\"type\":\"minecraft:offset\",\"x\":0,\"y\":1,\"z\":0}")).getOrThrow();
            raw.put(scan+"/offset",List.of(modifier,offset));
        }
        for(var map:net.minecraft.world.level.levelgen.Heightmap.Types.values())for(int[] range:List.of(
                new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE},new int[]{0,0},new int[]{-1,1},
                new int[]{Integer.MIN_VALUE,-1},new int[]{1,Integer.MAX_VALUE},new int[]{1,-1})) {
            var modifier=net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter.of(map,range[0],range[1]);
            raw.put("surface_relative/"+map+"/"+Arrays.toString(range),List.of(modifier));
        }
        var palette=new LinkedHashMap<BlockState,Integer>();for(int i=0;i<materials.length;i++)palette.put(materials[i],i);
        var result=new ArrayList<PlacementCase>();
        for(var modifiers:raw.values()) {
            var recipe=JsonParser.parseString("{\"source\":\"test:placement\",\"salt\":0,\"density\":1,\"low_density\":1,\"noise_count\":false,\"rarity\":1,\"tries\":1,\"spread\":[0,0,0],\"kind\":\"plant\",\"states\":[{\"lower\":0,\"upper\":0,\"weight\":1,\"band\":0,\"dry\":false}]}").getAsJsonObject();
            var program=new JsonArray();for(var m:modifiers)program.add(net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.encodeStart(ops,m).getOrThrow());recipe.add("placement",program);
            var singleton=new JsonArray();singleton.add(recipe);DecorationProfile.finishPlacements(singleton,palette);
            int index=profile.getAsJsonArray("decorations").size();profile.getAsJsonArray("decorations").add(recipe);result.add(new PlacementCase(index,modifiers));
        }
        return result;
    }
    private static void checkPlacements(RegistryAccess registry,List<PlacementCase> cases,List<Fixture> fixtures) {
        int checked=0,accepted=0,rejected=0;
        var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
        for(var fixture:fixtures) {
            var world=new World(fixture);
            var generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),fixture.request.minY(),(fixture.request.height()+15)/16*16,64,0,.008F,"mca");
            var context=new net.minecraft.world.level.levelgen.placement.PlacementContext(world.level,generator,Optional.empty());
            for(var test:cases)for(int seed=0;seed<64;seed++) {
                var origin=new BlockPos(-17,fixture.originHeight+(seed%9)-4,-17);
                var stream=new Stream(seed,false);var expected=new ArrayList<BlockPos>();expected.add(origin);
                for(var modifier:test.modifiers) {
                    var next=new ArrayList<BlockPos>();for(var p:expected)modifier.modify(context,stream,p,q->next.add(q.immutable()));expected=next;
                }
                // The native feature planner also discards final out-of-build
                // candidates before attempting geometry, beyond raw modifiers.
                expected.removeIf(p->p.getY()<fixture.request.minY() || p.getY()>=fixture.request.minY()+fixture.request.height());
                var actual=NativeTerrain.instance().decorationPlacement(fixture.request,test.recipe,new int[]{origin.getX(),origin.getY(),origin.getZ()},seed);
                require(actual.length==expected.size()*4,"registered placement cardinality "+test.modifiers+" seed="+seed+" actual="+Arrays.toString(actual)+" expected="+expected);
                for(int i=0;i<expected.size();i++)require(expected.get(i).equals(new BlockPos(actual[i*4],actual[i*4+1],actual[i*4+2])),"registered placement position matches Minecraft "+test.modifiers+" seed="+seed);
                checked++;if(expected.isEmpty())rejected++;else accepted++;
            }
        }
        require(accepted>100 && rejected>100,"spatial placements exercise surviving and rejected candidates");
        System.out.println("QA_EVT {\"event\":\"registered_vertical_placements_minecraft_reference\",\"status\":\"pass\",\"context\":{\"programs\":"+cases.size()+",\"cases\":"+checked+",\"accepted\":"+accepted+",\"rejected\":"+rejected+"}}");
    }
    private static void checkPlantPartners(JsonObject json, BlockState[] materials, Fixture fixture) {
        var halves=json.getAsJsonArray("plant_halves");
        require(halves.size()==materials.length,"registered pair metadata covers palette");
        var world=new World(fixture);var pos=new BlockPos(-18,fixture.originHeight,-31);
        int checked=0;
        for(int i=0;i<materials.length;i++) {
            var state=materials[i];int half=halves.get(i).getAsInt();
            require((half!=0)==(state.getBlock() instanceof DoublePlantBlock),"pair metadata follows registered block class");
            if(half==0)continue;
            var direction=half>0?Direction.UP:Direction.DOWN;
            BlockState ground=null;
            for(var candidate:materials) {
                world.changed.clear();world.changed.put(pos,state);world.changed.put(pos.below(),candidate);
                if(state.canSurvive(world.level,pos)){ground=candidate;break;}
            }
            require(ground!=null,"registered palette has surviving support for "+state);
            for(int j=0;j<materials.length;j++) {
                world.changed.clear();world.changed.put(pos,state);world.changed.put(pos.below(),ground);world.changed.put(pos.relative(direction),materials[j]);
                var actual=state.updateShape(world.level,world.level,pos,direction,pos.relative(direction),materials[j],new LegacyRandomSource(7));
                boolean valid=halves.get(j).getAsInt()==-half;
                require(valid?actual.equals(state):actual.isAir(),"registered partner table matches Minecraft updateShape: "+state+"/"+materials[j]);
                checked++;
            }
        }
        require(checked>100,"paired flowers, grasses and aquatics exercised");
        System.out.println("QA_EVT {\"event\":\"registered_plant_partner_minecraft_reference\",\"status\":\"pass\",\"context\":{\"state_pairs\":"+checked+"}}");
    }
    private static void checkRegion(JsonObject original,BlockState[] materials,String name)throws Exception {
        var json=original.deepCopy();
        for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
        var biome=original.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(b->b.get("id").getAsString().equals("minecraft:"+name)).findFirst().orElseThrow().deepCopy();
        biome.add("terrain",JsonParser.parseString("[0,0,0]"));biome.add("ores",new JsonArray());biome.add("carvers",new JsonArray());
        var allowed=new JsonArray();
        for(var id:biome.getAsJsonArray("decorations"))if(ADAPTERS.contains(original.getAsJsonArray("decorations").get(id.getAsInt()).getAsJsonObject().get("kind").getAsString()))allowed.add(id);
        biome.add("decorations",allowed);var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
        for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
        if(name.equals("lush_caves"))json.add("registry_program",cavernProgram(json,96));
        var nativeTerrain=NativeTerrain.instance();int profile=nativeTerrain.registerProfile(json.toString());
        int base=name.equals("ocean")?32:96;
        var request=new TerrainRequest(123456789L,-32,0,-64,384,base,0,.008f,profile);
        var directory=Files.createTempDirectory("retina-block-feature-regions-");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        int compared=0,vegetation=0,activeChunks=0;
        try {
            nativeTerrain.generateRegion(request,directory.resolve("r.-1.0.mca"),net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:"+name);
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-block-features",Level.OVERWORLD,"chunk"),directory,false)) {
                var positions=new LinkedHashSet<ChunkPos>();
                for(int z:new int[]{0,15,31})for(int x:new int[]{-32,-17,-1})positions.add(new ChunkPos(x,z));
                for(int z=0;z<32;z++)for(int x=-32;x<0;x++) {
                    var pos=new ChunkPos(x,z);var tag=storage.read(pos);require(tag!=null,"every feature MCA slot decodes");boolean active=false;
                    for(var entry:tag.getListOrEmpty("sections"))for(var item:((net.minecraft.nbt.CompoundTag)entry).getCompoundOrEmpty("block_states").getListOrEmpty("palette")) {
                        var state=BlockState.CODEC.parse(NbtOps.INSTANCE,item).getOrThrow();
                        active|=isFeature(state);
                    }
                    if(active){activeChunks++;if(positions.size()<12)positions.add(pos);}
                }
                for(var pos:positions) {
                    int x=pos.x(),z=pos.z();var tag=storage.read(pos);
                    var r=new TerrainRequest(request.seed(),x,z,-64,384,base,0,.008f,profile);
                    try(var data=nativeTerrain.generate(r)) {
                        var bytes=data.blocks().toArray(ValueLayout.JAVA_SHORT);var maps=new int[6][256];
                        for(int section=0;section<24;section++) {
                            var blocks=codec.parse(NbtOps.INSTANCE,tag.getListOrEmpty("sections").getCompound(section).orElseThrow().getCompoundOrEmpty("block_states")).getOrThrow();
                            for(int y=0;y<16;y++)for(int bz=0;bz<16;bz++)for(int bx=0;bx<16;bx++) {
                                int layer=section*16+y,c=bz*16+bx;var state=blocks.get(bx,y,bz);
                                require(state.equals(materials[Short.toUnsignedInt(bytes[layer*256+c])]),"ordered feature chunk/MCA mismatch: "+name+"/"+pos+"/"+bx+","+(layer-64)+","+bz);
                                for(var type:Heightmap.Types.values())if(type.isOpaque().test(state))maps[type.ordinal()][c]=layer+1;
                                if(isFeature(state))vegetation++;
                            }
                        }
                        for(var type:Heightmap.Types.values()) {
                            var packed=new SimpleBitStorage(9,256,tag.getCompoundOrEmpty("Heightmaps").getLongArray(type.getSerializationKey()).orElseThrow());
                            for(int c=0;c<256;c++)require(packed.get(c)==maps[type.ordinal()][c],"final feature heightmap reflects block writes");
                        }
                    }
                    compared++;
                }
            }
            require(activeChunks>10 && vegetation>0,"registered "+name+" features reach final chunk data");
            System.out.println("QA_EVT {\"event\":\"registered_block_features_chunk_mca\",\"status\":\"pass\",\"context\":{\"biome\":\""+name+"\",\"chunks\":"+compared+",\"feature_blocks\":"+vegetation+",\"active_region_chunks\":"+activeChunks+"}}");
        }finally{try(var files=Files.walk(directory)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}}
    }
    private static boolean isFeature(BlockState state) {
        return state.is(BlockTags.LOGS)||state.is(Blocks.BAMBOO)||state.is(Blocks.KELP)||state.is(Blocks.KELP_PLANT)||state.is(Blocks.SEAGRASS)||state.is(Blocks.TALL_SEAGRASS)||state.is(Blocks.CACTUS)
                ||state.is(Blocks.MOSS_BLOCK)||state.is(Blocks.CLAY)||state.is(Blocks.CAVE_VINES)||state.is(Blocks.CAVE_VINES_PLANT)||state.is(Blocks.SMALL_DRIPLEAF)||state.is(Blocks.BIG_DRIPLEAF)
                ||state.is(Blocks.MUSHROOM_STEM)||state.is(Blocks.RED_MUSHROOM_BLOCK)||state.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }
    private static Feature resolve(RegistryAccess registry,String source) {
        var holder=registry.lookupOrThrow(Registries.PLACED_FEATURE).listElements()
                .filter(h->{String name=h.key().identifier().toString();return source.equals(name) || source.startsWith(name+"/");})
                .max(Comparator.comparingInt(h->h.key().identifier().toString().length()));
        if(holder.isEmpty())return null;
        String root=holder.get().key().identifier().toString();
        var path=source.substring(root.length()).split("/");
        Feature feature=holder.get().value().feature().value();
        for(int i=1;i<path.length;i++) {
            String branch=path[i];
            if(feature instanceof RandomSelectorFeature f)feature=(branch.equals("default")?f.defaultFeature():f.features().get(Integer.parseInt(branch.substring(6))).feature()).value().feature().value();
            else if(feature instanceof SimpleRandomSelectorFeature f)feature=f.features().get(Integer.parseInt(branch.substring(6))).value().feature().value();
            else if(feature instanceof RandomBooleanSelectorFeature f)feature=(branch.equals("true")?f.featureTrue():f.featureFalse()).value().feature().value();
            else if(feature instanceof WeightedRandomSelectorFeature f)feature=f.features().unwrap().get(Integer.parseInt(branch.substring(6))).value().value().feature().value();
            else throw new AssertionError("Unresolved feature branch "+source);
        }
        return feature;
    }
    private static final class Fixture {
        final TerrainRequest request;final BlockState[] column;final int base,originHeight;final boolean rugged,cavern;
        final BlockState[] materials;final Map<ChunkPos,short[]> terrain=new HashMap<>();
        PalettedContainerFactory factory; RetinaChunkGenerator generator;
        Fixture(JsonObject original,BlockState[] materials,int base,int height) {
            this(original,materials,base,height,false);
        }
        Fixture(JsonObject original,BlockState[] materials,int base,int height,boolean rugged) {
            this(original,materials,base,height,rugged,-1);
        }
        Fixture(JsonObject original,BlockState[] materials,int base,int height,boolean rugged,int caveOrigin) {
            this.rugged=rugged;this.cavern=caveOrigin>=0;this.materials=materials;
            this.base=base;var json=original.deepCopy();
            for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
            var biome=JsonParser.parseString("{\"id\":\"test:uniform\",\"climate\":[0,0,0,0],\"terrain\":[0,0,0],\"flags\":0}").getAsJsonObject();
            biome.addProperty("top",index(materials,Blocks.GRASS_BLOCK.defaultBlockState()));biome.addProperty("filler",index(materials,Blocks.DIRT.defaultBlockState()));biome.addProperty("underwater",index(materials,Blocks.DIRT.defaultBlockState()));
            if(rugged)biome.add("terrain",JsonParser.parseString("[0,1,1]"));
            var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
            for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
            if(cavern)json.add("registry_program",cavernProgram(json,base));
            int id=NativeTerrain.instance().registerProfile(json.toString());
            request=new TerrainRequest(123456789L,-2,-2,-64,height,base,rugged?14:0,rugged?.15f:.008f,id);
            originHeight=cavern?caveOrigin:NativeTerrain.instance().sampleHeights(request)[255];
            var raw=NativeTerrain.instance().column(request,255);column=new BlockState[height];
            for(int y=0;y<height;y++)column[y]=materials[Short.toUnsignedInt(raw[y])];
            if(!cavern)require(column[originHeight-1+64].is(base<63?Blocks.DIRT:Blocks.GRASS_BLOCK),"controlled feature substrate has registered surface");
            else require(column[18+64].isAir() && column[30+64].isAir() && column[17+64].is(Blocks.STONE) && column[31+64].is(Blocks.STONE),"controlled GPU cave substrate has floor/ceiling");
        }
        BlockState base(BlockPos pos) {
            int y=pos.getY()-request.minY();
            if(y<0 || y>=request.height())return Blocks.AIR.defaultBlockState();
            if(!rugged)return column[y];
            var chunkPos=new ChunkPos(Math.floorDiv(pos.getX(),16),Math.floorDiv(pos.getZ(),16));
            var blocks=terrain.computeIfAbsent(chunkPos,p->{
                var r=new TerrainRequest(request.seed(),p.x(),p.z(),request.minY(),request.height(),request.baseHeight(),request.amplitude(),request.frequency(),request.profile());
                try(var data=NativeTerrain.instance().generate(r)){return data.blocks().toArray(ValueLayout.JAVA_SHORT);}
            });
            return materials[Short.toUnsignedInt(blocks[y*256+Math.floorMod(pos.getZ(),16)*16+Math.floorMod(pos.getX(),16)])];
        }
    }
    private static JsonObject cavernProgram(JsonObject json,int base) {
        var graphs=new JsonArray();graphs.add(constant(0));graphs.add(constant(base-1+.25F));
        graphs.add(JsonParser.parseString("{\"nodes\":[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":[0,0,0,0]},{\"op\":3,\"a\":1,\"b\":0,\"c\":0,\"p\":[-64,320,-88,296]},{\"op\":11,\"a\":1,\"b\":0,\"c\":0,\"p\":[0,0,0,0]},{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":[6.25,0,0,0]},{\"op\":5,\"a\":2,\"b\":3,\"c\":0,\"p\":[0,0,0,0]}],\"roots\":[4]}"));
        graphs.add(constant(json.get("stone").getAsInt()+1));
        var gpu=JsonParser.parseString("{\"surface\":[-64,8,1],\"terrain_cell\":[4,8],\"surface_noises\":[0,0,0],\"material_layers\":true,\"material_halo\":true,\"points\":[],\"noises\":[{\"frequency\":1,\"amplitude\":0,\"salt\":0,\"coefficients\":[1]}]}").getAsJsonObject();
        gpu.add("programs",graphs);return gpu;
    }
    private static JsonElement constant(float v) {return JsonParser.parseString("{\"nodes\":[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":["+v+",0,0,0]}],\"roots\":[0]}");}
    private static int index(BlockState[] states,BlockState state) {for(int i=0;i<states.length;i++)if(states[i].equals(state))return i;throw new AssertionError("Missing material "+state);}
    private static final class World implements InvocationHandler {
        final Fixture fixture;final Map<BlockPos,BlockState> changed=new HashMap<>();
        final Map<ChunkPos,ProtoChunk> chunks=new HashMap<>();
        final WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
        World(Fixture fixture){this.fixture=fixture;}
        BlockState get(BlockPos pos) {return changed.getOrDefault(pos,base(pos));}
        BlockState base(BlockPos pos) {return fixture.base(pos);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments)throws Throwable {
            var args=arguments==null?new Object[0]:arguments;
            return switch(method.getName()) {
                case "getBlockState" -> get((BlockPos)args[0]);
                case "isStateAtPosition" -> ((java.util.function.Predicate<BlockState>)args[1]).test(get((BlockPos)args[0]));
                case "getFluidState" -> get((BlockPos)args[0]).getFluidState();
                case "getChunk" -> {
                    var pos=args[0] instanceof BlockPos p?ChunkPos.containing(p):new ChunkPos((int)args[0],(int)args[1]);
                    yield chunks.computeIfAbsent(pos,p->new ProtoChunk(p,UpgradeData.EMPTY,level,fixture.factory,null));
                }
                case "setBlock" -> {
                    var pos=((BlockPos)args[0]).immutable();int y=pos.getY()-fixture.request.minY();
                    if(y<0 || y>=fixture.column.length)yield false;
                    changed.put(pos,(BlockState)args[1]);yield true;
                }
                case "isEmptyBlock" -> get((BlockPos)args[0]).isAir();
                case "getHeight" -> {
                    if(args.length==0)yield fixture.column.length;
                    var type=(Heightmap.Types)args[0];int x=(int)args[1],z=(int)args[2];
                    int y=fixture.request.minY()+fixture.column.length;
                    while(y>fixture.request.minY() && !type.isOpaque().test(get(new BlockPos(x,y-1,z))))y--;
                    yield y;
                }
                case "getMinY" -> fixture.request.minY();
                case "getSeed" -> fixture.request.seed();
                case "getRandom" -> new Stream(123,false);
                case "scheduleTick" -> null;
                case "toString" -> "MinecraftFeatureReferenceWorld";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> {
                    if(method.isDefault())yield InvocationHandler.invokeDefault(proxy,method,args);
                    throw new UnsupportedOperationException("Actual feature called "+method);
                }
            };
        }
    }
    /** Controlled SplitMix stream, using Retina's integer and floating draws. */
    private static final class Stream extends LegacyRandomSource {
        long state;final boolean floatingWeights;
        Stream(long seed,boolean floatingWeights){super(0);state=seed;this.floatingWeights=floatingWeights;}
        @Override public long nextLong(){long x=state+=0x9e3779b97f4a7c15L;x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}
        @Override public int nextInt(int bound){return bound<=1?0:floatingWeights?(int)(nextDouble()*bound):(int)Long.remainderUnsigned(nextLong(),bound);}
        @Override public int nextInt(){return (int)nextLong();}
        @Override public double nextDouble(){return (nextLong()>>>11)/(double)(1L<<53);}
        @Override public float nextFloat(){return (float)nextDouble();}
        @Override public double nextGaussian(){return Math.sqrt(-2*Math.log(Math.max(Double.MIN_VALUE,nextDouble())))*Math.cos(2*Math.PI*nextDouble());}
        @Override public boolean nextBoolean(){return nextDouble()<.5;}
    }
    private static String differences(Map<BlockPos,BlockState> expected,Map<BlockPos,BlockState> actual) {
        var positions=new TreeSet<BlockPos>();positions.addAll(expected.keySet());positions.addAll(actual.keySet());
        return "expected="+expected.size()+", actual="+actual.size()+", "+positions.stream().filter(p->!Objects.equals(expected.get(p),actual.get(p))).limit(10).map(p->p+":"+expected.get(p)+" -> "+actual.get(p)).toList();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
