package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.HeightRangePredicate;

import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Real loaded feature implementations run against an independently checked native cavern substrate. */
public final class NetherFeatureTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); NativeRegistryLoadingTest.registerCodecs();
        try (var resources=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
            var source=MultiNoiseBiomeSource.createFromPreset(registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
            var generator=new NoiseBasedChunkGenerator(source,settings);
            var exported=TerrainProfileData.export(registry,RetinaBiomeSource.fromSelected(source),0,256,42,settings.value(),StructureProfile.Context.NONE,true,false,true);
            var data=JsonParser.parseString(exported.json()).getAsJsonObject();
            Files.createDirectories(Path.of("build/nether"));Files.writeString(Path.of("build/nether/feature-profile.json"),data.toString());
            NetherFeatureCoverageTest.check(registry,exported);
            NetherFeatureOrderTest.check(registry,exported);
            NetherOreTest.check(registry,exported,generator);
            var kinds=new TreeSet<String>();gather(data.getAsJsonArray("decorations"),kinds);
            require(kinds.containsAll(Set.of("neighbor_spread","pillar","projected_square","column_cluster","replace_blobs","delta","huge_fungus","spring")),"all loaded Nether primitive families exported: "+kinds);
            for (var biome:data.getAsJsonArray("biomes")) {
                var b=biome.getAsJsonObject();b.add("decorations",new JsonArray());b.add("ores",new JsonArray());b.add("carvers",new JsonArray());
            }
            data.add("ores",new JsonArray());
            var compiler=new RegistryGpuProgram(registry,0,128,32);
            var density=compiler.new Program();int y=density.node(29,1,0,0);
            int root=density.node(23,y,density.constant(-1),density.constant(1),32,96);
            density.roots.add(root);density.roots.add(density.constant(128));
            var gpu=data.getAsJsonObject("registry_program");var programs=gpu.getAsJsonArray("programs");
            programs.set(1,density.finish());programs.set(2,density.finish());gpu.add("interpolations",new JsonArray());
            for(int i=0;i<exported.biomes().size();i++) {
                var material=compiler.new Program();material.roots.add(material.constant(data.get("stone").getAsInt()+1));programs.set(3+i,material.finish());
            }
            var heightCases=heightPredicates(data,exported.materials(),settings.value());
            int profile=NativeTerrain.instance().registerProfile(data.toString());
            var request=new TerrainRequest(42,-2,-2,0,256,64,48,.008f,profile);
            checkHeightPredicates(heightCases,request,generator);
            var palette=exported.materials();var terrain=new HashMap<ChunkPos,short[]>();
            var recipes=data.getAsJsonArray("decorations");var successes=new TreeMap<String,Integer>();var failures=new TreeMap<String,Integer>();
            int cases=0;long writes=0;
            for(int id=0;id<recipes.size();id++) {
                var recipe=recipes.get(id).getAsJsonObject();var nested=new TreeSet<String>();gather(recipe,nested);
                if(nested.isEmpty() || nested.contains("ore"))continue; // Bulk-section ore writes have a separate reference fixture.
                String name=recipe.get("source").getAsString();
                var feature=NativeBlockFeatureIntegrationTest.resolve(registry,name);require(feature!=null,"source feature resolves: "+name);
                for(int y0:new int[]{0,31,32,64,95,96,127,128,255}) for(int seed=0;seed<16;seed++) {
                    var origin=new BlockPos(-17,y0,-17);var world=new World(request,palette,terrain);
                    feature.place(world.level,generator,new NativeBlockFeatureIntegrationTest.Stream(seed,false),origin);
                    int[] result=NativeTerrain.instance().decorationFeature(request,id,new int[]{origin.getX(),y0,origin.getZ()},seed);
                    var actual=new HashMap<BlockPos,BlockState>();
                    for(int i=0;i<result.length;i+=4) {
                        actual.put(new BlockPos(result[i],result[i+1],result[i+2]),palette[result[i+3]]);
                        require(Math.abs(result[i]-origin.getX())<=recipe.get("reach").getAsInt()
                                && Math.abs(result[i+2]-origin.getZ())<=recipe.get("reach").getAsInt(),
                                "exported dependency footprint contains actual feature writes: "+name);
                    }
                    require(actual.equals(world.changed),"Nether feature parity "+name+" y="+y0+" seed="+seed+": "+NativeBlockFeatureIntegrationTest.differences(world.changed,actual));
                    (actual.isEmpty()?failures:successes).merge(name,1,Integer::sum);cases++;writes+=actual.size();
                }
            }
            require(successes.size()>=8 && cases>1000 && writes>10000,"loaded primitive geometry exercised: "+successes);
            var stats=new JsonObject();stats.addProperty("cases",cases);stats.addProperty("writes",writes);stats.add("successes",new Gson().toJsonTree(successes));stats.add("empty",new Gson().toJsonTree(failures));
            Files.writeString(Path.of("build/nether/feature-reference-stats.json"),stats.toString());
            System.out.println("QA_EVT {\"event\":\"nether_block_features_reference\",\"status\":\"pass\",\"context\":"+stats+"}");
            NetherSpringTest.check(registry,exported,generator);
            NetherSurvivalTest.check(registry,exported,generator);
            NetherFungusTest.check(registry,exported,generator);
            if(Arrays.asList(args).contains("--region")) {
                NetherRegionTest.check(exported,BiomeTerrainProfile.register(exported),true);
                NetherFungusTest.checkRegions(registry,generator);
            }
        }
    }

    private record HeightCase(int recipe,HeightRangePredicate predicate) {}

    private static List<HeightCase> heightPredicates(JsonObject profile,BlockState[] states,NoiseGeneratorSettings settings) {
        var palette=new LinkedHashMap<BlockState,Integer>();for(var state:states)palette.put(state,palette.size());
        var recipes=new JsonArray();var cases=new ArrayList<HeightCase>();
        for(String range:List.of(
                "{\"min_inclusive\":{\"above_bottom\":3},\"max_inclusive\":{\"below_top\":7}}",
                "{\"min_inclusive\":{\"relative_to_sea_level\":-2},\"max_inclusive\":{\"relative_to_sea_level\":2}}",
                "{\"min_inclusive\":{\"absolute\":90},\"max_inclusive\":{\"absolute\":90}}",
                "{\"min_inclusive\":{\"absolute\":140},\"max_inclusive\":{\"absolute\":90}}")) {
            var predicate=JsonParser.parseString(range).getAsJsonObject();predicate.addProperty("type","minecraft:height_range");
            var reference=(HeightRangePredicate)BlockPredicate.CODEC.parse(JsonOps.INSTANCE,predicate).getOrThrow();
            var filter=new JsonObject();filter.addProperty("type","minecraft:block_predicate_filter");filter.add("predicate",predicate);
            var recipe=JsonParser.parseString("""
                    {"source":"test:nether_height_range","salt":0,"density":1,"low_density":1,"noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],"kind":"plant","states":[{"lower":0,"upper":0,"weight":1,"band":0,"dry":false}]}
                    """).getAsJsonObject();
            var placement=new JsonArray();placement.add(filter);recipe.add("placement",placement);
            cases.add(new HeightCase(profile.getAsJsonArray("decorations").size()+recipes.size(),reference));recipes.add(recipe);
        }
        DecorationProfile.finishPlacements(recipes,palette,GenerationBounds.noise(settings.noiseSettings(),0,256),settings.seaLevel());
        require(palette.size()==states.length,"height-only predicates do not extend material palette");
        profile.getAsJsonArray("decorations").addAll(recipes);return cases;
    }

    private static void checkHeightPredicates(List<HeightCase> cases,TerrainRequest request,NoiseBasedChunkGenerator generator) {
        var context=new WorldGenerationContext(generator,LevelHeightAccessor.create(0,256));int checked=0,accepted=0;
        for(var test:cases) {
            int min=test.predicate.minInclusive().resolveY(context),max=test.predicate.maxInclusive().resolveY(context);
            for(int y:new int[]{min-1,min,max,max+1,0,127,128,255}) {
                boolean expected=y>=min && y<=max && y>=0 && y<256;
                int[] positions=NativeTerrain.instance().decorationPlacement(request,test.recipe,new int[]{-17,y,-17},17);
                require(positions.length==(expected?4:0),"height-range predicate follows Minecraft context and inclusive edges: "+test.predicate+" y="+y);
                if(expected) {require(positions[0]==-17 && positions[1]==y && positions[2]==-17,"height predicate preserves position");accepted++;}
                checked++;
            }
        }
        require(accepted>0 && accepted<checked,"height predicates accept and reject boundary candidates");
        System.out.println("QA_EVT {\"event\":\"nether_height_predicates_reference\",\"status\":\"pass\",\"context\":{\"cases\":"+checked+",\"accepted\":"+accepted+"}}");
    }

    private static void gather(JsonElement node,Set<String> kinds) {
        if(node.isJsonArray())node.getAsJsonArray().forEach(v->gather(v,kinds));
        else if(node.isJsonObject()) {
            var object=node.getAsJsonObject();if(object.has("nether"))kinds.add(object.getAsJsonObject("nether").get("kind").getAsString());
            object.entrySet().forEach(e->gather(e.getValue(),kinds));
        }
    }

    record Tick(BlockPos position,String id,int delay) {}

    static final class World implements InvocationHandler {
        final TerrainRequest request;final BlockState[] palette;final Map<ChunkPos,short[]> terrain;
        final java.util.function.Function<BlockPos,BlockState> expected;
        final Map<BlockPos,BlockState> changed=new HashMap<>();
        final List<Tick> ticks=new ArrayList<>();
        net.minecraft.world.level.lighting.LevelLightEngine lighting=net.minecraft.world.level.lighting.LevelLightEngine.EMPTY;
        final WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
        World(TerrainRequest request,BlockState[] palette,Map<ChunkPos,short[]> terrain) {
            this(request,palette,terrain,p->(p.getY()<32 || p.getY()>=96 && p.getY()<128)?Blocks.NETHERRACK.defaultBlockState():Blocks.AIR.defaultBlockState());
        }
        World(TerrainRequest request,BlockState[] palette,Map<ChunkPos,short[]> terrain,java.util.function.Function<BlockPos,BlockState> expected) {
            this.request=request;this.palette=palette;this.terrain=terrain;this.expected=expected;
        }
        BlockState get(BlockPos at) {
            var changed=this.changed.get(at);if(changed!=null)return changed;
            if(at.getY()<request.minY() || at.getY()>=request.minY()+request.height())return Blocks.AIR.defaultBlockState();
            var cp=ChunkPos.containing(at);
            var blocks=terrain.computeIfAbsent(cp,p->{
                var r=new TerrainRequest(request.seed(),p.x(),p.z(),request.minY(),request.height(),64,48,.008f,request.profile());
                try(var chunk=NativeTerrain.instance().generate(r)) {
                    short[] result=chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                    for(int y=0;y<request.height();y++)for(int c=0;c<256;c++) {
                        var expected=this.expected.apply(new BlockPos(p.x()*16+c%16,y+request.minY(),p.z()*16+c/16));
                        var actual=palette[Short.toUnsignedInt(result[y*256+c])];
                        require(actual.equals(expected),"controlled cavern substrate at "+p+":"+(y+request.minY())+" expected="+expected+" actual="+actual);
                    }
                    return result;
                }
            });
            return palette[Short.toUnsignedInt(blocks[(at.getY()-request.minY())*256+Math.floorMod(at.getZ(),16)*16+Math.floorMod(at.getX(),16)])];
        }
        @Override public Object invoke(Object proxy,Method method,Object[] arguments)throws Throwable {
            var args=arguments==null?new Object[0]:arguments;
            return switch(method.getName()) {
                case "getBlockState" -> get((BlockPos)args[0]);
                case "isStateAtPosition" -> ((java.util.function.Predicate<BlockState>)args[1]).test(get((BlockPos)args[0]));
                case "getFluidState" -> get((BlockPos)args[0]).getFluidState();
                case "getLightEngine" -> lighting;
                case "setBlock", "setBlockAndUpdate" -> {var p=((BlockPos)args[0]).immutable();if(p.getY()<request.minY() || p.getY()>=request.minY()+request.height())yield false;changed.put(p,(BlockState)args[1]);yield true;}
                case "destroyBlock" -> {var p=((BlockPos)args[0]).immutable();if(get(p).isAir() || p.getY()<request.minY() || p.getY()>=request.minY()+request.height())yield false;changed.put(p,Blocks.AIR.defaultBlockState());yield true;}
                case "isEmptyBlock" -> get((BlockPos)args[0]).isAir();
                case "getHeight" -> {if(args.length==0)yield request.height();var type=(Heightmap.Types)args[0];int y=request.minY()+request.height();while(y>request.minY()&&!type.isOpaque().test(get(new BlockPos((int)args[1],y-1,(int)args[2]))))y--;yield y;}
                case "getMinY" -> request.minY();
                case "getSeed" -> request.seed();
                case "getRandom" -> new NativeBlockFeatureIntegrationTest.Stream(123,false);
                case "scheduleTick" -> {
                    ticks.add(new Tick(((BlockPos)args[0]).immutable(),net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey((net.minecraft.world.level.material.Fluid)args[1]).toString(),(int)args[2]));yield null;
                }
                case "toString" -> "NetherFeatureReferenceWorld";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> {if(method.isDefault())yield InvocationHandler.invokeDefault(proxy,method,args);throw new UnsupportedOperationException("Actual feature called "+method);}
            };
        }
    }
    private static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
