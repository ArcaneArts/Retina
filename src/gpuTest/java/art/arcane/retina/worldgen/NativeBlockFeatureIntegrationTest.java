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
    private static final Set<String> ADAPTERS=Set.of("block_column","bamboo","aquatic","huge_mushroom","fallen_tree","vegetation_patch","simple_block","attachment_growth");
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
                    if(fixture.cavern && !Set.of("vegetation_patch","simple_block","attachment_growth").contains(kind))continue;
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
            checkAttachments(registry,json,factory,pack!=null);

        }
    }
    private static void checkAttachments(RegistryAccess registry,JsonObject original,PalettedContainerFactory factory,boolean packed) throws Exception {
        var profile=original.deepCopy();profile.remove("ores");
        // Controlled geometry fixtures extend the palette after the full-profile
        // ore/cave dressing replacement tables were built. Those independent
        // features are exercised by the original profile checks above.
        for(var biome:profile.getAsJsonArray("biomes"))biome.getAsJsonObject().remove("cave_features");
        var palette=new LinkedHashMap<BlockState,Integer>();
        for(var value:profile.getAsJsonArray("materials"))palette.put(BlockState.CODEC.parse(JsonOps.INSTANCE,value).getOrThrow(),palette.size());
        var constructor=DecorationProfile.class.getDeclaredConstructor(HolderLookup.Provider.class,LinkedHashMap.class);constructor.setAccessible(true);
        var exporter=constructor.newInstance(registry,palette);
        var placementType=Class.forName("art.arcane.retina.worldgen.DecorationProfile$Placement");
        var pc=placementType.getDeclaredConstructor();pc.setAccessible(true);
        var export=DecorationProfile.class.getDeclaredMethod("placed",net.minecraft.world.level.levelgen.placement.PlacedFeature.class,placementType,double.class,String.class,List.class,int.class);export.setAccessible(true);
        var field=DecorationProfile.class.getDeclaredField("recipes");field.setAccessible(true);
        var recipes=(JsonArray)field.get(exporter);var features=new ArrayList<Feature>();
        var inputs=new ArrayList<JsonObject>();
        for(String block:List.of("glow_lichen","sculk_vein"))for(float chance:List.of(0F,.37F,1F))for(int flags:List.of(1,2,4,7)) {
            var feature=new JsonObject();feature.addProperty("type","minecraft:multiface_growth");feature.addProperty("block","minecraft:"+block);
            feature.addProperty("search_range",flags==7?64:5);feature.addProperty("chance_of_spreading",chance);
            feature.addProperty("can_place_on_floor",(flags&1)!=0);feature.addProperty("can_place_on_ceiling",(flags&2)!=0);feature.addProperty("can_place_on_wall",(flags&4)!=0);
            feature.add("can_be_placed_on",JsonParser.parseString("[\"minecraft:grass_block\",\"minecraft:dirt\",\"minecraft:stone\",\"minecraft:moss_block\"]"));inputs.add(feature);
            if(flags==7 && chance==.37F) {
                var patch=cuboidPatch("floor",true,true);var nested=new JsonObject();nested.add("feature",feature.deepCopy());
                nested.add("placement",JsonParser.parseString("[{\"type\":\"minecraft:count\",\"count\":3},{\"type\":\"minecraft:random_chance\",\"chance\":0.65}]"));
                patch.add("vegetation_feature",nested);inputs.add(patch);
            }
        }
        inputs.add(JsonParser.parseString("{\"type\":\"minecraft:vines\"}").getAsJsonObject());
        for(var input:inputs) {
            var feature=Feature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),input).getOrThrow();
            var selected=new ArrayList<Integer>();var placed=new net.minecraft.world.level.levelgen.placement.PlacedFeature(Holder.direct(feature),List.of(
                net.minecraft.world.level.levelgen.placement.InSquarePlacement.spread(),net.minecraft.world.level.levelgen.placement.HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING)));
            export.invoke(exporter,placed,pc.newInstance(),1.0,"test:attachment"+features.size(),selected,0);
            require(selected.size()==1,"registered attachment exports: "+input);features.add(feature);
        }
        var flowing=Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,5);
        palette.computeIfAbsent(flowing,ignored->palette.size());
        DecorationProfile.finishPlacements(recipes,palette);DecorationProfile.materialFlags(profile,palette);
        var materials=palette.keySet().toArray(BlockState[]::new);var serialized=new JsonArray();for(var state:materials)serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());profile.add("materials",serialized);
        var carveable=new JsonArray();for(var state:materials)carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.UNCARVABLE));profile.add("carveable",carveable);
        int offset=profile.getAsJsonArray("decorations").size();profile.getAsJsonArray("decorations").addAll(recipes);
        var fixtures=List.of(new Fixture(profile,materials,96,384),new Fixture(profile,materials,32,384),new Fixture(profile,materials,58,384),new Fixture(profile,materials,90,384,false,18),new Fixture(profile,materials,90,384,false,30),new Fixture(profile,materials,96,384,true),new Fixture(profile,materials,96,384,false,-1,flowing));
        int checked=0,placedCount=0,spreadCount=0,wetCount=0,flowCount=0,vines=0;var faces=new TreeSet<String>();
        for(var fixture:fixtures) {
            fixture.factory=factory;var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
            fixture.generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),-64,384,fixture.base,0,.008F,"mca");
            for(int i=0;i<features.size();i++)for(long seed=0;seed<32;seed++)for(int dy:List.of(-1,0,1,2)) {
                var at=new BlockPos(-17,fixture.originHeight+dy,-17);var world=new World(fixture);
                features.get(i).place(world.level,fixture.generator,new Stream(seed,false),at);
                var actual=new HashMap<BlockPos,BlockState>();var blocks=NativeTerrain.instance().decorationFeature(fixture.request,offset+i,new int[]{at.getX(),at.getY(),at.getZ()},seed);
                for(int k=0;k<blocks.length;k+=4)actual.put(new BlockPos(blocks[k],blocks[k+1],blocks[k+2]),materials[blocks[k+3]]);
                require(world.changed.equals(actual),"attachment reference mismatch: "+inputs.get(i)+" seed="+seed+" origin="+at+" differences="+differences(world.changed,actual));
                checked++;if(!actual.isEmpty())placedCount++;if(features.get(i) instanceof MultifaceGrowthFeature && actual.size()>1)spreadCount++;
                for(var state:actual.values()) {
                    if(state.is(Blocks.VINE))vines++;
                    if(state.getValueOrElse(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,false))wetCount++;
                    else if(fixture.column[fixture.originHeight-1+64].equals(flowing))flowCount++;
                    if(state.getBlock() instanceof MultifaceBlock)for(var d:Direction.values())if(MultifaceBlock.hasFace(state,d))faces.add(d.getName());
                }
            }
        }
        require(placedCount>500 && spreadCount>100 && wetCount>500 && flowCount>100 && vines>0 && faces.size()>=5,"nonvacuous attachment directions/spreading/fluids: "+faces+"/"+placedCount+"/"+spreadCount+"/"+wetCount+"/"+flowCount+"/"+vines);
        // A GPU-carved vertical wall reaches the highest buildable layer, so
        // wrap-around spread can find a supported face outside the build range.
        // Minecraft rejects that write and continues with the next direction.
        var boundary=new Fixture(profile,materials,320,384,true,-1,Blocks.STONE.defaultBlockState(),true);
        boundary.factory=factory;boundary.generator=fixtures.getFirst().generator;
        int boundaryCases=0,rejectedWrites=0,recoveredWrites=0;
        for(int i=0;i<features.size();i++)for(long seed=0;seed<64;seed++)for(int y:List.of(-65,-64,-63,317,318,319,320,321)) {
            var at=new BlockPos(-17,y,-17);var world=new World(boundary);
            features.get(i).place(world.level,boundary.generator,new Stream(seed,false),at);
            var actual=new HashMap<BlockPos,BlockState>();var blocks=NativeTerrain.instance().decorationFeature(boundary.request,offset+i,new int[]{at.getX(),at.getY(),at.getZ()},seed);
            for(int k=0;k<blocks.length;k+=4)actual.put(new BlockPos(blocks[k],blocks[k+1],blocks[k+2]),materials[blocks[k+3]]);
            require(world.changed.equals(actual),"attachment build-boundary mismatch: "+inputs.get(i)+" seed="+seed+" origin="+at+" differences="+differences(world.changed,actual));
            boundaryCases++;rejectedWrites+=world.rejectedWrites;if(world.rejectedWrites>0 && world.changed.size()>1)recoveredWrites++;
        }
        require(rejectedWrites>100 && recoveredWrites>0,"attachment build-boundary checks reject writes and recover: "+rejectedWrites+"/"+recoveredWrites);
        System.out.println("QA_EVT {\"event\":\"registered_attachment_build_bounds\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"cases\":"+boundaryCases+",\"rejected_writes\":"+rejectedWrites+",\"recovered_cases\":"+recoveredWrites+"}}");
        var ids=new JsonArray();for(int i=0;i<features.size();i++)ids.add(offset+i);
        for(var b:profile.getAsJsonArray("biomes"))if(b.getAsJsonObject().get("id").getAsString().equals("minecraft:lush_caves"))b.getAsJsonObject().add("decorations",ids);
        checkRegion(profile,materials,"lush_caves");
        System.out.println("QA_EVT {\"event\":\"registered_attachment_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"cases\":"+checked+",\"placed\":"+placedCount+",\"spread\":"+spreadCount+",\"waterlogged\":"+wetCount+",\"flowing_water_dry\":"+flowCount+",\"vines\":"+vines+",\"faces\":"+new Gson().toJson(faces)+"}}");
    }
    private static void checkStateProviders(RegistryAccess registry,JsonObject original,PalettedContainerFactory factory,boolean packed) throws Exception {
        var profile=original.deepCopy();profile.remove("ores");profile.remove("cave_noises");
        var palette=new LinkedHashMap<BlockState,Integer>();
        for(var value:profile.getAsJsonArray("materials"))palette.put(BlockState.CODEC.parse(JsonOps.INSTANCE,value).getOrThrow(),palette.size());
        var constructor=DecorationProfile.class.getDeclaredConstructor(HolderLookup.Provider.class,LinkedHashMap.class);constructor.setAccessible(true);
        var exporter=constructor.newInstance(registry,palette);
        var noiseField=DecorationProfile.class.getDeclaredField("providerNoise");noiseField.setAccessible(true);
        var noise=(ProviderNoiseProfile)noiseField.get(exporter);
        noise.programs.addAll(profile.getAsJsonArray("decoration_provider_noises"));
        profile.add("decoration_provider_noises",noise.programs);
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
            "{\"type\":\"minecraft:rotated\",\"state\":{\"type\":\"minecraft:random_block\",\"blocks\":[\"minecraft:oak_stairs\",\"minecraft:furnace\"]}}",
            "{\"type\":\"minecraft:noise\",\"seed\":347,\"noise\":{\"base_octave\":-2,\"octave_count\":2},\"scale\":0.23,\"states\":[\"minecraft:oak_log\",\"minecraft:birch_log\",\"minecraft:stone\"]}",
            "{\"type\":\"minecraft:dual_noise\",\"seed\":-977,\"noise\":{\"base_octave\":-1,\"octave_count\":2},\"scale\":0.75,\"slow_noise\":{\"base_octave\":-3,\"octave_count\":3},\"slow_scale\":0.013,\"variety\":{\"min_inclusive\":1,\"max_inclusive\":8},\"states\":[\"minecraft:oak_log\",\"minecraft:birch_log\",\"minecraft:stone\",\"minecraft:dirt\"]}",
            "{\"type\":\"minecraft:noise_threshold\",\"seed\":9123,\"noise\":{\"base_octave\":-1,\"octave_count\":3},\"scale\":0.21,\"threshold\":0.1,\"high_chance\":0.4,\"default_state\":\"minecraft:stone\",\"low_states\":[\"minecraft:oak_log\",\"minecraft:birch_log\",\"minecraft:spruce_log\"],\"high_states\":[\"minecraft:dirt\",\"minecraft:grass_block\"]}"
        );
        var providers=new ArrayList<JsonElement>();for(var rule:rules)providers.add(providerStates(JsonParser.parseString(rule)));
        for(var direction:Direction.values()) {
            var provider=providerStates(JsonParser.parseString(rules.get(5))).getAsJsonObject();provider.addProperty("direction",direction.getName());providers.add(provider);
        }
        for(float threshold:new float[]{-.8F,0,.8F}) {
            var provider=providerStates(JsonParser.parseString(rules.getLast())).getAsJsonObject();provider.addProperty("threshold",threshold);providers.add(provider);
        }
        var wrapped=new JsonObject();wrapped.addProperty("type","minecraft:rotated");wrapped.add("state",providerStates(JsonParser.parseString(rules.get(7))));providers.add(wrapped);
        int firstCopy=providers.size();
        for(var state:List.of(Blocks.OAK_LOG.defaultBlockState(),Blocks.FURNACE.defaultBlockState(),Blocks.OAK_STAIRS.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY,true),
                Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,7))) {
            providers.add(copyProperties(BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow()));
        }
        providers.add(copyProperties(providerStates(JsonParser.parseString(rules.get(7)))));
        providers.add(copyProperties(copyProperties(providerStates(new JsonPrimitive("minecraft:oak_log")))));
        var rotateCopy=new JsonObject();rotateCopy.addProperty("type","minecraft:rotated");rotateCopy.addProperty("direction","east");rotateCopy.add("state",providers.get(firstCopy).deepCopy());providers.add(rotateCopy);
        var copyRotation=copyProperties(providerStates(JsonParser.parseString(rules.get(5))));providers.add(copyRotation);
        int endCopy=providers.size();
        for(String property:List.of("missing_property","axis"))providers.add(randomizedProperty(providerStates(new JsonPrimitive("minecraft:oak_log")),property));
        providers.add(randomizedProperty(providerStates(new JsonPrimitive("minecraft:furnace")),"lit"));
        var mixed=new JsonObject();mixed.addProperty("type","minecraft:random_block");mixed.add("blocks",JsonParser.parseString("[\"minecraft:water\",\"minecraft:stone\"]"));
        providers.add(randomizedProperty(mixed,"level"));
        var spatial=providerStates(JsonParser.parseString(rules.get(7))).getAsJsonObject();spatial.add("states",providerStates(JsonParser.parseString("[\"minecraft:water\",\"minecraft:stone\"]")));
        providers.add(randomizedProperty(spatial,"level"));
        providers.add(copyProperties(randomizedProperty(mixed.deepCopy(),"level")));
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
        int firstCuboid=features.size();
        for(String surface:List.of("floor","ceiling"))for(boolean edges:List.of(false,true))for(boolean interior:List.of(false,true)) {
            var json=cuboidPatch(surface,edges,interior);
            var feature=Feature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),json).getOrThrow();
            var selected=new ArrayList<Integer>();
            var placed=new net.minecraft.world.level.levelgen.placement.PlacedFeature(Holder.direct(feature),List.of(
                net.minecraft.world.level.levelgen.placement.InSquarePlacement.spread(),net.minecraft.world.level.levelgen.placement.HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING)));
            export.invoke(exporter,placed,placementConstructor.newInstance(),1.0,"test:cuboid_patch"+features.size(),selected,0);
            require(selected.size()==1 && synthetic.get(selected.getFirst()).getAsJsonObject().get("kind").getAsString().equals("vegetation_patch"),"nested cuboid exports through production patch dispatch");
            features.add(feature);
        }
        // Add substrate states after provider export, as structures/geology do.
        // The final copy tables must see their actual compatible properties.
        var copyInputs=List.of(
            Blocks.QUARTZ_PILLAR.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.X),
            Blocks.POLISHED_BASALT.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z),
            Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,net.minecraft.world.level.block.state.properties.Half.TOP)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true),
            Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,Direction.EAST),
            Blocks.GRASS_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY,false),
            Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL,5),
            Blocks.BEDROCK.defaultBlockState());
        for(var input:copyInputs)palette.computeIfAbsent(input,ignored->palette.size());
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
        int cuboidCases=0,floorLogs=0,ceilingLogs=0;
        // The provider-only carrier disables caves. Restore the actual registered
        // cave inputs before replacing its density graph with the controlled void.
        var caveProfile=profile.deepCopy();caveProfile.add("cave_noises",original.get("cave_noises").deepCopy());
        var carveable=new JsonArray();for(var state:materials)carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(BlockTags.UNCARVABLE));
        caveProfile.add("carveable",carveable);
        for(var fixture:List.of(new Fixture(caveProfile,materials,90,384,false,18),new Fixture(caveProfile,materials,90,384,false,30),new Fixture(profile,materials,96,384,true))) {
            fixture.factory=factory;
            var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
            fixture.generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),-64,384,fixture.base,0,.008F,"mca");
            for(int i=firstCuboid;i<features.size();i++) {
                int direction=synthetic.get(i).getAsJsonObject().get("direction").getAsInt();
                if(fixture.cavern && (direction<0)!=(fixture.originHeight==18))continue;
                for(long seed=0;seed<64;seed++) {
                    var at=new BlockPos(-17,fixture.originHeight,-17);var world=new World(fixture);
                    features.get(i).place(world.level,fixture.generator,new Stream(seed,false),at);
                    int[] blocks=NativeTerrain.instance().decorationFeature(fixture.request,offset+i,new int[]{at.getX(),at.getY(),at.getZ()},seed);
                    var actual=new HashMap<BlockPos,BlockState>();for(int k=0;k<blocks.length;k+=4)actual.put(new BlockPos(blocks[k],blocks[k+1],blocks[k+2]),materials[blocks[k+3]]);
                    require(world.changed.equals(actual),"nested cuboid cave/rugged reference mismatch: "+synthetic.get(i)+" seed="+seed+" differences="+differences(world.changed,actual));
                    if(fixture.cavern) {
                        int logs=(int)actual.values().stream().filter(s->s.is(BlockTags.LOGS)).count();
                        if(direction<0)floorLogs+=logs;else ceilingLogs+=logs;
                    }
                    cuboidCases++;checked++;if(actual.isEmpty())empty++;
                }
            }
        }
        require(floorLogs>0 && ceilingLogs>0,"nested cuboids place on both carved cave surfaces");
        System.out.println("QA_EVT {\"event\":\"registered_cuboid_patches_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"cases\":"+cuboidCases+",\"floor_logs\":"+floorLogs+",\"ceiling_logs\":"+ceilingLogs+"}}");
        int copiedCases=0,changedProperties=0;
        for(var input:copyInputs) {
            var fixture=new Fixture(profile,materials,96,384,false,-1,input);fixture.factory=factory;
            var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.parse("minecraft:plains")));
            fixture.generator=new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),-64,384,96,0,.008F,"mca");
            for(int i=firstCopy*2;i<endCopy*2;i++)for(long seed=0;seed<32;seed++) {
                var at=new BlockPos(-17,fixture.originHeight-1,-17);var world=new World(fixture);
                features.get(i).place(world.level,fixture.generator,new Stream(seed,false),at);
                int[] blocks=NativeTerrain.instance().decorationFeature(fixture.request,offset+i,new int[]{at.getX(),at.getY(),at.getZ()},seed);
                var actual=new HashMap<BlockPos,BlockState>();for(int k=0;k<blocks.length;k+=4)actual.put(new BlockPos(blocks[k],blocks[k+1],blocks[k+2]),materials[blocks[k+3]]);
                require(world.changed.equals(actual),"property-copy reference mismatch: "+synthetic.get(i)+" input="+input+" seed="+seed+" differences="+differences(world.changed,actual));
                var placed=actual.get(at);
                if(placed!=null && !placed.equals(placed.getBlock().defaultBlockState()))changedProperties++;
                copiedCases++;
            }
        }
        require(changedProperties>256,"copied live axes, facing, half, waterlogging and levels are exercised");
        System.out.println("QA_EVT {\"event\":\"registered_property_copy_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"cases\":"+copiedCases+",\"nondefault_placements\":"+changedProperties+"}}");
        var ids=new JsonArray();for(int i=0;i<features.size();i++)ids.add(offset+i);
        for(var b:profile.getAsJsonArray("biomes"))if(b.getAsJsonObject().get("id").getAsString().equals("minecraft:forest"))b.getAsJsonObject().add("decorations",ids);
        // This is a controlled, cave-free carrier for the provider recipes; its
        // imported replacement tables describe the old, smaller palette.
        for(var b:profile.getAsJsonArray("biomes"))b.getAsJsonObject().remove("cave_features");
        checkRegion(profile,materials,"forest",true);
        var layered=synthetic.get(0).getAsJsonObject().deepCopy();
        layered.addProperty("source","test:layered_noise_counts");layered.addProperty("placement_salt",917);
        layered.add("placement",JsonParser.parseString("[{\"type\":\"count_on_every_layer\",\"count\":{\"type\":\"uniform\",\"min_inclusive\":2,\"max_inclusive\":4}},{\"type\":\"noise_based_count\",\"noise_to_count_ratio\":2,\"noise_factor\":30,\"noise_offset\":1}]"));
        var only=new JsonArray();only.add(profile.getAsJsonArray("decorations").size());profile.getAsJsonArray("decorations").add(layered);
        for(var b:profile.getAsJsonArray("biomes"))if(b.getAsJsonObject().get("id").getAsString().equals("minecraft:lush_caves"))b.getAsJsonObject().add("decorations",only);
        checkRegion(profile,materials,"lush_caves");
        System.out.println("QA_EVT {\"event\":\"registered_state_providers_minecraft_reference\",\"status\":\"pass\",\"context\":{\"datapack\":"+packed+",\"features\":"+features.size()+",\"cases\":"+checked+",\"empty\":"+empty+"}}");
    }
    private static JsonObject copyProperties(JsonElement source) {
        var value=new JsonObject();value.addProperty("type","minecraft:copy_properties");value.add("source",source);return value;
    }
    private static JsonObject randomizedProperty(JsonElement source,String property) {
        var value=new JsonObject();value.addProperty("type","minecraft:randomized_int");value.add("source",source);value.addProperty("property",property);
        value.add("values",JsonParser.parseString("{\"type\":\"minecraft:uniform\",\"min_inclusive\":0,\"max_inclusive\":6}"));return value;
    }
    private static JsonObject cuboidPatch(String surface,boolean edges,boolean interior) {
        var value=JsonParser.parseString("{\"type\":\"minecraft:vegetation_patch\",\"replaceable\":\"#minecraft:moss_replaceable\",\"ground_state\":\"minecraft:moss_block\",\"surface\":\"floor\",\"depth\":1,\"vertical_range\":12,\"extra_bottom_block_chance\":0.2,\"extra_edge_column_chance\":0.35,\"vegetation_chance\":1,\"xz_radius\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":0,\"max_inclusive\":1}}").getAsJsonObject();
        value.addProperty("surface",surface);value.add("ground_state",providerStates(new JsonPrimitive("minecraft:moss_block")));
        var nested=new JsonObject();var feature=new JsonObject();feature.addProperty("type","minecraft:block_column");feature.addProperty("direction","up");feature.addProperty("prioritize_tip",false);feature.add("allowed_placement",JsonParser.parseString("{\"type\":\"minecraft:true\"}"));
        var layer=new JsonObject();layer.add("height",JsonParser.parseString("{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":3}"));
        layer.add("provider",providerStates(JsonParser.parseString("{\"type\":\"minecraft:weighted\",\"entries\":[{\"weight\":2,\"data\":\"minecraft:oak_log\"},{\"weight\":3,\"data\":\"minecraft:birch_log\"}]}")));
        var layers=new JsonArray();layers.add(layer);feature.add("layers",layers);nested.add("feature",feature);
        var cuboid=JsonParser.parseString("{\"type\":\"minecraft:cuboid\",\"xz_size\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":3},\"y_size\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":4}}").getAsJsonObject();
        cuboid.addProperty("include_edges",edges);cuboid.addProperty("include_interior",interior);
        var placement=new JsonArray();placement.add(cuboid);placement.add(JsonParser.parseString("{\"type\":\"minecraft:random_chance\",\"chance\":0.65}"));
        placement.add(JsonParser.parseString("{\"type\":\"minecraft:offset\",\"x\":{\"type\":\"minecraft:uniform\",\"min_inclusive\":-1,\"max_inclusive\":1},\"y\":0,\"z\":0}"));
        nested.add("placement",placement);value.add("vegetation_feature",nested);return value;
    }
    private static JsonElement providerStates(JsonElement value) {
        if(value.isJsonArray()) {
            var states=new JsonArray();for(var state:value.getAsJsonArray())states.add(providerStates(state));return states;
        }
        if(value.isJsonPrimitive()) {
            var state=BuiltInRegistries.BLOCK.getValue(Identifier.parse(value.getAsString())).defaultBlockState();
            return BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow();
        }
        var object=value.getAsJsonObject();
        for(String key:List.of("state","source","fallback","default_state"))if(object.has(key))object.add(key,providerStates(object.get(key)));
        for(String key:List.of("states","low_states","high_states"))if(object.has(key)) {
            var states=new JsonArray();for(var state:object.getAsJsonArray(key))states.add(providerStates(state));object.add(key,states);
        }
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
                case "random_chance", "surface_relative_threshold_filter", "count_on_every_layer", "cuboid" -> true;
                case "environment_scan" -> DecorationProfile.supportedPredicate(json.getAsJsonObject("target_condition")) && (!json.has("allowed_search_condition") || DecorationProfile.supportedPredicate(json.getAsJsonObject("allowed_search_condition")));
                default -> false;
            };
            if(supported)raw.putIfAbsent(json.toString(),List.of(modifier));
        }
        for(String size:List.of("1","3","16","{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":16}",
                "{\"type\":\"minecraft:biased_to_bottom\",\"min_inclusive\":1,\"max_inclusive\":7}",
                "{\"type\":\"minecraft:weighted_list\",\"distribution\":[{\"weight\":2,\"data\":1},{\"weight\":3,\"data\":4}]}"))for(boolean edges:List.of(false,true))for(boolean interior:List.of(false,true)) {
            var json=new JsonObject();json.addProperty("type","minecraft:cuboid");json.add("xz_size",JsonParser.parseString(size));json.add("y_size",JsonParser.parseString("{\"type\":\"minecraft:uniform\",\"min_inclusive\":1,\"max_inclusive\":5}"));
            json.addProperty("include_edges",edges);json.addProperty("include_interior",interior);
            var modifier=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,json).getOrThrow();raw.put(json.toString(),List.of(modifier));
            var map=net.minecraft.world.level.levelgen.placement.HeightmapPlacement.onHeightmap(Heightmap.Types.OCEAN_FLOOR);
            raw.put(json+"/height",List.of(modifier,map));
        }
        var maximum=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,JsonParser.parseString("{\"type\":\"minecraft:cuboid\",\"xz_size\":16,\"y_size\":16}")).getOrThrow();
        raw.put("cuboid/defaults/maximum",List.of(maximum));
        // Compare the real layer loop, including its repeatedly sampled bound.
        for(String count:List.of("0","1","256",
                "{\"type\":\"minecraft:uniform\",\"min_inclusive\":0,\"max_inclusive\":3}",
                "{\"type\":\"minecraft:biased_to_bottom\",\"min_inclusive\":2,\"max_inclusive\":5}",
                "{\"type\":\"minecraft:weighted_list\",\"distribution\":[{\"weight\":2,\"data\":1},{\"weight\":3,\"data\":4}]}")) {
            var json=new JsonObject();json.addProperty("type","minecraft:count_on_every_layer");json.add("count",JsonParser.parseString(count));
            var layer=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,json).getOrThrow();
            raw.put(json.toString(),List.of(layer));
            var shift=net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(ops,JsonParser.parseString("{\"type\":\"minecraft:offset\",\"x\":-3,\"y\":0,\"z\":2}")).getOrThrow();
            raw.put(json+"/shift",List.of(shift,layer));
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
                // Keep the full offset + 16-column footprint inside the supplied
                // three-chunk terrain halo, while crossing negative chunk edges.
                boolean layer=test.modifiers.stream().anyMatch(m->m instanceof net.minecraft.world.level.levelgen.placement.CountOnEveryLayerPlacement);
                var origin=new BlockPos(layer?-25:-17,fixture.originHeight+(seed%9)-4,layer?-25:-17);
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
        var floors=json.getAsJsonArray("plant_floor_masks");
        var flags=json.getAsJsonArray("material_flags");
        require(halves.size()==materials.length,"registered pair metadata covers palette");
        require(floors.size()==materials.length,"registered floor metadata covers palette");
        var world=new World(fixture);var pos=new BlockPos(-18,fixture.originHeight,-31);
        int checked=0,floorChecked=0;
        for(int i=0;i<materials.length;i++) {
            var state=materials[i];int half=halves.get(i).getAsInt();
            int mask=floors.get(i).getAsInt();
            boolean ordinaryFloor=state.getBlock().getClass()==TallGrassBlock.class || state.getBlock().getClass()==DoublePlantBlock.class && half>0;
            require((mask!=0)==ordinaryFloor,"floor metadata follows actual survival classes and lower halves");
            if(mask!=0)for(int j=0;j<materials.length;j++) {
                world.changed.clear();world.changed.put(pos,state);world.changed.put(pos.below(),materials[j]);
                require(state.canSurvive(world.level,pos)==((flags.get(j).getAsInt()&mask)!=0),"floor mask matches Minecraft survival: "+state+"/"+materials[j]);
                floorChecked++;
            }
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
        require(floorChecked>100,"grass/fern and terrestrial lower-half supports exercised");
        System.out.println("QA_EVT {\"event\":\"registered_plant_floor_minecraft_reference\",\"status\":\"pass\",\"context\":{\"state_pairs\":"+floorChecked+"}}");
        System.out.println("QA_EVT {\"event\":\"registered_plant_partner_minecraft_reference\",\"status\":\"pass\",\"context\":{\"state_pairs\":"+checked+"}}");
    }
    private static void checkRegion(JsonObject original,BlockState[] materials,String name)throws Exception {
        checkRegion(original,materials,name,false);
    }
    private static void checkRegion(JsonObject original,BlockState[] materials,String name,boolean expectProvider)throws Exception {
        var json=original.deepCopy();
        for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
        var biome=original.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(b->b.get("id").getAsString().equals("minecraft:"+name)).findFirst().orElseThrow().deepCopy();
        biome.add("terrain",JsonParser.parseString("[0,0,0]"));biome.add("ores",new JsonArray());biome.add("carvers",new JsonArray());
        var allowed=new JsonArray();
        boolean crowns=name.equals("dark_forest");
        for(var id:biome.getAsJsonArray("decorations")) {
            var recipe=original.getAsJsonArray("decorations").get(id.getAsInt()).getAsJsonObject();
            boolean selected=crowns ? recipe.get("kind").getAsString().equals("tree") && recipe.get("foliage_shape").getAsString().equals("dark_oak_foliage_placer")
                    : ADAPTERS.contains(recipe.get("kind").getAsString());
            if(selected)allowed.add(id);
        }
        biome.add("decorations",allowed);var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
        for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
        if(name.equals("lush_caves"))json.add("registry_program",cavernProgram(json,96));
        var nativeTerrain=NativeTerrain.instance();int profile=nativeTerrain.registerProfile(json.toString());
        int base=name.equals("ocean")?32:96;
        var request=new TerrainRequest(123456789L,-32,0,-64,384,base,0,.008f,profile);
        var directory=Files.createTempDirectory("retina-block-feature-regions-");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        int compared=0,vegetation=0,activeChunks=0;
        var canopyChunks=new HashMap<ChunkPos,short[]>();
        try {
            var report=nativeTerrain.generateRegion(request,directory.resolve("r.-1.0.mca"),net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:"+name);
            if(expectProvider && report.stages().gpuMeasured())require(report.stages().providerMeasured() && report.stages().nanos(NativeTimings.PROVIDER_NOISE)>0,"production ordered replay records GPU provider time in the region report");
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
                        if(crowns)canopyChunks.put(pos,bytes);
                        for(int section=0;section<24;section++) {
                            var blocks=codec.parse(NbtOps.INSTANCE,tag.getListOrEmpty("sections").getCompound(section).orElseThrow().getCompoundOrEmpty("block_states")).getOrThrow();
                            for(int y=0;y<16;y++)for(int bz=0;bz<16;bz++)for(int bx=0;bx<16;bx++) {
                                int layer=section*16+y,c=bz*16+bx;var state=blocks.get(bx,y,bz);
                                require(state.equals(materials[Short.toUnsignedInt(bytes[layer*256+c])]),"ordered feature chunk/MCA mismatch: "+name+"/"+pos+"/"+bx+","+(layer-64)+","+bz);
                                for(var type:Heightmap.Types.values())if(type.isOpaque().test(state))maps[type.ordinal()][c]=layer+1;
                                if(isFeature(state))vegetation++;
                                if(crowns && state.is(BlockTags.LOGS) && layer+1<384) {
                                    var above=materials[Short.toUnsignedInt(bytes[(layer+1)*256+c])];
                                    // Leaning trunks expose an elbow below their crown.
                                    // A true tip has no continuation above it within
                                    // the two-block lean footprint, even across chunks.
                                    if(above.isAir()) {
                                        boolean continuation=false;
                                        for(int dy=1;dy<=3 && !continuation;dy++)for(int dz=-2;dz<=2 && !continuation;dz++)for(int dx=-2;dx<=2 && !continuation;dx++) {
                                            int wx=x*16+bx+dx,wz=z*16+bz+dz;
                                            var neighbor=new ChunkPos(Math.floorDiv(wx,16),Math.floorDiv(wz,16));
                                            var raw=canopyChunks.computeIfAbsent(neighbor,p->{
                                                var q=new TerrainRequest(request.seed(),p.x(),p.z(),-64,384,base,0,.008f,profile);
                                                try(var generated=nativeTerrain.generate(q)){return generated.blocks().toArray(ValueLayout.JAVA_SHORT);}
                                            });
                                            if(layer+dy<384)continuation=materials[Short.toUnsignedInt(raw[(layer+dy)*256+Math.floorMod(wz,16)*16+Math.floorMod(wx,16)])].is(BlockTags.LOGS);
                                        }
                                        require(continuation,"registered dark crown leaves no exposed trunk tip: "+pos+"/"+bx+","+(layer-64)+","+bz);
                                    }
                                }
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
            this(original,materials,base,height,rugged,caveOrigin,Blocks.GRASS_BLOCK.defaultBlockState());
        }
        Fixture(JsonObject original,BlockState[] materials,int base,int height,boolean rugged,int caveOrigin,BlockState surface) {
            this(original,materials,base,height,rugged,caveOrigin,surface,false);
        }
        Fixture(JsonObject original,BlockState[] materials,int base,int height,boolean rugged,int caveOrigin,BlockState surface,boolean boundaryWall) {
            this.rugged=rugged;this.cavern=caveOrigin>=0;this.materials=materials;
            this.base=base;var json=original.deepCopy();
            for(String key:List.of("registry_program","climate_targets","structures","terrain_features"))json.remove(key);
            var biome=JsonParser.parseString("{\"id\":\"test:uniform\",\"climate\":[0,0,0,0],\"terrain\":[0,0,0],\"flags\":0}").getAsJsonObject();
            biome.addProperty("top",index(materials,surface));biome.addProperty("filler",index(materials,Blocks.DIRT.defaultBlockState()));biome.addProperty("underwater",index(materials,Blocks.DIRT.defaultBlockState()));
            if(rugged)biome.add("terrain",JsonParser.parseString("[0,1,1]"));
            var biomes=new JsonArray();biomes.add(biome);json.add("biomes",biomes);
            for(var e:json.getAsJsonArray("noises"))e.getAsJsonObject().addProperty("amplitude",1e-12);
            if(cavern)json.add("registry_program",cavernProgram(json,base));
            if(boundaryWall) {
                var gpu=cavernProgram(json,base);
                gpu.getAsJsonArray("programs").set(2,JsonParser.parseString("{\"nodes\":[{\"op\":3,\"a\":0,\"b\":0,\"c\":0,\"p\":[-18,-17,1,-1]}],\"roots\":[0]}"));
                gpu.add("terrain_cell",JsonParser.parseString("[1,1]"));json.add("registry_program",gpu);
            }
            int id=NativeTerrain.instance().registerProfile(json.toString());
            request=new TerrainRequest(123456789L,-2,-2,-64,height,base,rugged?14:0,rugged?.15f:.008f,id);
            originHeight=boundaryWall?319:cavern?caveOrigin:NativeTerrain.instance().sampleHeights(request)[255];
            var raw=NativeTerrain.instance().column(request,255);column=new BlockState[height];
            for(int y=0;y<height;y++)column[y]=materials[Short.toUnsignedInt(raw[y])];
            if(boundaryWall)require(column[height-1].isAir() && base(new BlockPos(-18,319,-17)).is(Blocks.STONE),"controlled GPU wall reaches build ceiling");
            else if(!cavern)require(column[originHeight-1+64].equals(base<63?Blocks.DIRT.defaultBlockState():surface),"controlled feature substrate has registered surface");
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
        int rejectedWrites;
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
                    if(y<0 || y>=fixture.column.length){rejectedWrites++;yield false;}
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
