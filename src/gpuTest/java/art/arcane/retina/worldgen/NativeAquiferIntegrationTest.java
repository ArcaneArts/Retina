package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.*;
import java.lang.foreign.ValueLayout;
import java.util.*;

/** Real Minecraft aquifer equations, with its centers fixed to Retina's GPU hash. */
public final class NativeAquiferIntegrationTest {
    private static final long SEED=123456789L;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            Holder<Biome> plains=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace("plains")));
            var settings=registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
            var profile=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(List.of(plains),128,.55F),-64,384,SEED,settings);
            var registered=JsonParser.parseString(profile.json()).getAsJsonObject();
            if(!registered.getAsJsonObject("registry_program").getAsJsonObject("aquifer").get("enabled").getAsBoolean())throw new AssertionError("Loaded Overworld aquifer config must export");
            for(var biome:registered.getAsJsonArray("biomes")) {
                var rates=biome.getAsJsonObject().getAsJsonArray("lakes");
                if(Math.abs(rates.get(0).getAsDouble()-rates.get(1).getAsDouble())>1e-9)throw new AssertionError("Vanilla has no rainfall-driven surface-water lake recipe");
            }
            checkRegisteredLakes(registry,plains,settings,profile,registered);
            var random=RandomState.create(registry.lookupOrThrow(Registries.NOISE),SEED,settings);
            var samplers=random.samplersWithContext(SamplerContext.EMPTY_UNCACHED);
            var positional=random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"));
            var surfaceSearch=DensityFunctions.DIRECT_CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("{\"type\":\"minecraft:find_top_surface\",\"density\":{\"type\":\"minecraft:gradient\",\"axis\":\"y\",\"from_coordinate\":-64,\"to_coordinate\":160,\"from_value\":1,\"to_value\":-1},\"upper_bound\":141,\"lower_bound\":-64,\"cell_height\":8}")).getOrThrow();
            long compared=0,air=0,water=0,lava=0,barriers=0;
            for(String execution:List.of("interpreter","specialized"))
                for(float[] values:new float[][]{{-1,0,0,0,0,0},{.65f,0,0,0,0,0},{.65f,.55f,0,0,0,0},{.65f,-.55f,0,0,0,0},{1,0,0,0,0,0},{1,0,.55f,0,0,0},{1,0,0,1,0,0},{.65f,0,0,0,1,0},{-1,0,0,0,0,1},{1,0,0,0,0,2},{.65f,0,.55f,0,1,3},{.65f,0,.55f,0,1,4}}) {
                    float flood=values[0],spread=values[1],hot=values[2],exclude=values[3],barrier=values[4];
                    var data=fixture(registered,execution,flood,spread,hot,exclude,barrier);
                    int variant=(int)values[5];
                    var gpu=data.getAsJsonObject("registry_program");var graphs=gpu.getAsJsonArray("programs");
                    if(variant==1) {
                        var search=constant(141);var nodes=search.getAsJsonArray("nodes");
                        var gradient=JsonParser.parseString("{\"op\":3,\"a\":1,\"b\":0,\"c\":0,\"p\":[-64,160,1,-1]}");nodes.add(gradient);
                        search.add("roots",JsonParser.parseString("[1,0]"));graphs.set(graphs.size()-1,search);
                        gpu.getAsJsonObject("aquifer").add("surface",JsonParser.parseString("[-64,8,0]"));
                    } else if(variant==2) {
                        for(int i=0;i<5;i++)graphs.remove(graphs.size()-1);
                        gpu.add("aquifer",JsonParser.parseString("{\"enabled\":false}"));
                    } else if(variant>=3) {
                        int fluid=0;
                        if(variant==4)for(int i=0;i<profile.materials().length;i++)if(profile.materials()[i].is(Blocks.LAVA))fluid=i;
                        data.addProperty("water",fluid);
                    }
                    var defaultFluid=profile.materials()[data.get("water").getAsInt()];
                    int id=NativeTerrain.instance().registerProfile(data.toString());
                    var config=new Aquifer.Config(DensityFunctions.constant(barrier),DensityFunctions.constant(flood),DensityFunctions.constant(spread),DensityFunctions.constant(hot),DensityFunctions.constant(exclude),variant==1?surfaceSearch:DensityFunctions.constant(100));
                    Aquifer.FluidPicker picker=(x,y,z)->y<-54?new Aquifer.FluidStatus(-54,Blocks.LAVA.defaultBlockState()):new Aquifer.FluidStatus(63,defaultFluid);
                    for(int[] position:new int[][]{{-33,-32},{-1,0},{0,-1},{31,32}}) {
                        var request=new TerrainRequest(SEED,position[0],position[1],-64,384,64,48,.008F,id);
                        var reference=variant==2?Aquifer.createDisabled(picker):config.create(samplers,positional,new DensityVolume(16,384,16,position[0]*16,-64,position[1]*16),picker);
                        if(variant!=2)installCenters(reference);
                        try(var chunk=NativeTerrain.instance().generate(request)) {
                            var actual=chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                            for(int y=-59;y<=65;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                                var state=reference.computeSubstance(position[0]*16+x,y,position[1]*16+z,-.25390625);
                                if(state==null)state=settings.defaultBlock();
                                var generated=profile.materials()[Short.toUnsignedInt(actual[(y+64)*256+z*16+x])];
                                if(!state.equals(generated))throw new AssertionError("Aquifer "+execution+" "+Arrays.toString(values)+" at "+(position[0]*16+x)+","+y+","+(position[1]*16+z)+": "+generated+" vs Minecraft "+state);
                                if(state.isAir())air++;else if(state.is(Blocks.WATER))water++;else if(state.is(Blocks.LAVA))lava++;else barriers++;
                                compared++;
                            }
                        }
                    }
                }
            if(air==0 || water==0 || lava==0 || barriers==0)throw new AssertionError("Aquifer reference must exercise all four substances");
            System.out.println("QA_EVT {\"event\":\"gpu_aquifers_match_minecraft\",\"status\":\"pass\",\"context\":{\"voxels\":"+compared+",\"air\":"+air+",\"water\":"+water+",\"lava\":"+lava+",\"barriers\":"+barriers+"}}");
        }
    }
    private static void checkRegisteredLakes(HolderLookup.Provider registry,Holder<Biome> plains,NoiseGeneratorSettings settings,BiomeTerrainProfile profile,JsonObject source) {
        var ops=registry.createSerializationContext(JsonOps.INSTANCE);
        var data=Biome.DIRECT_CODEC.encodeStart(ops,plains.value()).getOrThrow().getAsJsonObject();
        var recipes=JsonParser.parseString("[[{\"feature\":{\"type\":\"minecraft:lake\",\"barrier\":{\"id\":\"minecraft:deepslate\"},\"fluid\":{\"id\":\"minecraft:water\"},\"can_place_feature\":{\"type\":\"minecraft:true\"},\"can_replace_with_air_or_fluid\":{\"type\":\"minecraft:true\"},\"can_replace_with_barrier\":{\"type\":\"minecraft:true\"}},\"placement\":[{\"type\":\"minecraft:rarity_filter\",\"chance\":200},{\"type\":\"minecraft:heightmap\",\"heightmap\":\"WORLD_SURFACE_WG\"}]}]]").getAsJsonArray();
        var lava=recipes.get(0).getAsJsonArray().get(0).getAsJsonObject().deepCopy();
        lava.getAsJsonObject("feature").getAsJsonObject("fluid").addProperty("id","minecraft:lava");
        lava.getAsJsonObject("feature").getAsJsonObject("barrier").addProperty("id","minecraft:stone");
        lava.getAsJsonArray("placement").get(0).getAsJsonObject().addProperty("chance",500);recipes.get(0).getAsJsonArray().add(lava);
        data.add("features",recipes);data.add("carvers",new JsonArray());
        var biome=Holder.direct(Biome.DIRECT_CODEC.parse(ops,data).getOrThrow());
        var entries=new JsonArray();entries.add(source.getAsJsonArray("biomes").get(0).deepCopy());
        var palette=new LinkedHashMap<net.minecraft.world.level.block.state.BlockState,Integer>();
        for(int i=0;i<profile.materials().length;i++)palette.put(profile.materials()[i],i);
        TerrainFeatureProfile.export(registry,List.of(biome),entries,palette,source.deepCopy(),settings.materialRule().value(),SEED,List.of());
        var entry=entries.get(0).getAsJsonObject();var lakes=entry.getAsJsonArray("lakes");
        double water=1-Math.pow(1-1.0/200,64),hot=1-Math.pow(1-1.0/500,64);
        if(Math.abs(lakes.get(0).getAsDouble()-water-hot)>1e-9 || Math.abs(lakes.get(1).getAsDouble()-hot)>1e-9)throw new AssertionError("Explicit registered water/lava lake rarity must be retained");
        if(entry.get("lake_water_barrier").getAsInt()!=palette.get(Blocks.DEEPSLATE.defaultBlockState()) || entry.get("lake_barrier").getAsInt()!=palette.get(Blocks.STONE.defaultBlockState()))throw new AssertionError("Water and lava lake barriers remain distinct registered materials");
        System.out.println("QA_EVT {\"event\":\"registered_surface_lake_rates_and_barriers\",\"status\":\"pass\"}");
    }
    private static JsonObject fixture(JsonObject source,String execution,float flood,float spread,float lava,float exclude,float barrier) {
        var result=source.deepCopy();result.remove("structures");result.add("ores",new JsonArray());result.add("decorations",new JsonArray());result.add("terrain_features",new JsonObject());result.addProperty("program_execution",execution);
        for(var biome:result.getAsJsonArray("biomes")) {
            var b=biome.getAsJsonObject();for(String key:List.of("ores","decorations","carvers"))b.add(key,new JsonArray());
            b.addProperty("flags",0);b.addProperty("cave_kind",0);b.addProperty("snow_surface",false);b.add("lakes",JsonParser.parseString("[0,0]"));
            b.getAsJsonObject("cave_features").add("replaceable",new JsonArray());
        }
        var gpu=result.getAsJsonObject("registry_program");var graphs=new JsonArray();graphs.add(constant(0));graphs.add(constant(89.25f));graphs.add(constant(-.25390625f));
        for(var b:result.getAsJsonArray("biomes"))graphs.add(constant(result.get("stone").getAsInt()+1));
        int start=graphs.size();graphs.add(constants(flood,exclude));graphs.add(constant(spread));graphs.add(constant(lava));graphs.add(constant(barrier));graphs.add(constant(100));
        gpu.add("programs",graphs);gpu.add("surface",JsonParser.parseString("[-64,1,1]"));
        gpu.add("aquifer",JsonParser.parseString("{\"enabled\":true,\"program\":"+start+",\"surface\":[-64,1,1]}"));
        return result;
    }
    private static JsonObject constant(float value) {return constants(value);}
    private static JsonObject constants(float... values) {
        var p=new JsonObject();var nodes=new JsonArray();var roots=new JsonArray();
        for(float value:values) {var n=new JsonObject();n.addProperty("op",0);n.addProperty("a",0);n.addProperty("b",0);n.addProperty("c",0);n.add("p",JsonParser.parseString("["+value+",0,0,0]"));roots.add(nodes.size());nodes.add(n);}
        p.add("nodes",nodes);p.add("roots",roots);return p;
    }
    private static int hash(int value) {int h=value;h=(h^(h>>>16))*0x7feb352d;h=(h^(h>>>15))*0x846ca68b;return h^(h>>>16);}
    private static Object field(Object value,String name) throws Exception {var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static void installCenters(Aquifer aquifer) throws Exception {
        int minX=(int)field(aquifer,"minGridX"),minY=(int)field(aquifer,"minGridY"),minZ=(int)field(aquifer,"minGridZ");
        int sizeX=(int)field(aquifer,"gridSizeX"),sizeZ=(int)field(aquifer,"gridSizeZ");
        long[] locations=(long[])field(aquifer,"aquiferLocationCache");
        for(int i=0;i<locations.length;i++) {
            int x=minX+i%sizeX,y=minY+i/(sizeX*sizeZ),z=minZ+i/sizeX%sizeZ;
            int h=hash(x*0x9e3779b9^y*0x85ebca6b^z*0xc2b2ae35^(int)SEED^(hash((int)(SEED>>>32))+91871));
            locations[i]=BlockPos.asLong(x*16+Integer.remainderUnsigned(h,10),y*12+Integer.remainderUnsigned(hash(h+1),9),z*16+Integer.remainderUnsigned(hash(h+2),10));
        }
    }
}
