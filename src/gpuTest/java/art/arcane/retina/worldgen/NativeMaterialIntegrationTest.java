package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.*;
import net.minecraft.world.level.levelgen.material.rule.*;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual Minecraft material evaluators against complete GPU runs, not two sample depths. */
public final class NativeMaterialIntegrationTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            Holder<Biome> plains=registry.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,Identifier.withDefaultNamespace("plains")));
            var base=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(List.of(plains),128,.55F),-64,384);
            long checked=0;int varieties=0;
            for(String execution:List.of("interpreter","specialized"))for(boolean guarded:List.of(false,true))for(int[] scenario:new int[][]{{90,-64,384,1},{58,-64,384,1},{90,-63,383,1},{90,-64,384,0}}) {
                int surface=scenario[0],min=scenario[1],height=scenario[2];boolean caves=scenario[3]!=0;
                var rule=rule(guarded);
                var palette=new LinkedHashMap<BlockState,Integer>();for(var state:base.materials())palette.put(state,palette.size());
                var compiler=new RegistryGpuProgram(registry,-64,384,63);
                var data=JsonParser.parseString(base.json()).getAsJsonObject();data.remove("structures");data.addProperty("program_execution",execution);
                var graphs=new JsonArray();graphs.add(zero());graphs.add(constant(surface-1+.25F));
                // A flat, thirteen-block air span at Y=18..30, far enough below
                // either surface to avoid the intentional entrance taper.
                graphs.add(JsonParser.parseString("""
                    {"nodes":[{"op":0,"a":0,"b":0,"c":0,"p":[0,0,0,0]},
                    {"op":3,"a":1,"b":0,"c":0,"p":[-64,320,-88,296]},
                    {"op":11,"a":1,"b":0,"c":0,"p":[0,0,0,0]},
                    {"op":0,"a":0,"b":0,"c":0,"p":[6.25,0,0,0]},
                    {"op":5,"a":2,"b":3,"c":0,"p":[0,0,0,0]}],"roots":[4]}
                    """));
                for(var biome:base.biomes()) {
                    var program=compiler.new Program();program.roots.add(program.rule(rule,biome,palette));graphs.add(program.finish());
                }
                var materials=new JsonArray();
                for(var state:palette.keySet()) materials.add(net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,state).getOrThrow());
                // Native profile uses id/properties, whereas the block codec
                // uses Name/Properties. Preserve existing entries and convert additions.
                var nativePalette=data.getAsJsonArray("materials");
                for(var state:palette.keySet())if(palette.get(state)>=base.materials().length) {
                    var m=new JsonObject();m.addProperty("id",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    var encoded=materials.get(palette.get(state)).getAsJsonObject();
                    var props=encoded.has("properties")?encoded.getAsJsonObject("properties"):encoded.has("Properties")?encoded.getAsJsonObject("Properties"):new JsonObject();
                    if(!props.isEmpty())m.add("properties",props);nativePalette.add(m);
                    data.getAsJsonArray("material_flags").add(0);data.getAsJsonArray("heightmap_masks").add(63);data.getAsJsonArray("carveable").add(true);
                    data.getAsJsonArray("snow_support").add(DecorationProfile.supportsSnow(state));
                }
                for(var biome:data.getAsJsonArray("biomes")) {
                    var b=biome.getAsJsonObject();for(String key:List.of("decorations","ores","carvers"))b.add(key,new JsonArray());
                    b.addProperty("flags",0);b.addProperty("cave_kind",0);b.add("lakes",JsonParser.parseString("[0,0]"));
                    if(b.has("cave_features"))b.getAsJsonObject("cave_features").add("replaceable",new JsonArray());
                }
                // Zero registered surface noises make depth exactly 3 and
                // secondary depth exactly 0; Minecraft evaluates the same contexts.
                compiler.noise(new JsonPrimitive("minecraft:surface"));
                for(var n:compiler.noises)n.getAsJsonObject().addProperty("amplitude",0);
                var gpu=new JsonObject();gpu.add("programs",graphs);gpu.add("noises",compiler.noises);gpu.add("points",compiler.points);
                gpu.add("surface",JsonParser.parseString("[-64,8,1]"));gpu.add("terrain_cell",JsonParser.parseString("[4,8]"));
                gpu.add("surface_noises",JsonParser.parseString("[0,0,0]"));gpu.addProperty("material_layers",true);gpu.addProperty("material_halo",true);data.add("registry_program",gpu);
                // Remove unused recipes whose host-band masks belong to the old palette.
                data.add("ores",new JsonArray());data.add("decorations",new JsonArray());
                if(!caves)data.add("cave_noises",new JsonArray());
                int id=NativeTerrain.instance().registerProfile(data.toString());
                var expected=reference(rule,plains,surface,min,height,caves);
                varieties=Math.max(varieties,new HashSet<>(Arrays.asList(expected)).size());
                try(var workers=Executors.newFixedThreadPool(4)) {
                    var futures=new ArrayList<Future<Long>>();
                    for(int[] pos:new int[][]{{-33,-32},{-1,0},{0,-1},{31,32}})futures.add(workers.submit(()-> {
                        var request=new TerrainRequest(123456789L,pos[0],pos[1],min,height,64,48,.008F,id);
                        try(var chunk=NativeTerrain.instance().generate(request)) {
                            var actual=chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                            var states=palette.keySet().toArray(BlockState[]::new);
                            for(int y=0;y<height;y++)for(int c=0;c<256;c++) {
                                var state=states[Short.toUnsignedInt(actual[y*256+c])];
                                if(!state.equals(expected[y])) {
                                    var spans=new StringBuilder();BlockState previous=null;
                                    for(int k=height-1;k>=0;k--) {var s=states[Short.toUnsignedInt(actual[k*256+c])];if(!s.equals(previous)){spans.append(k+min).append(':').append(s).append(' ');previous=s;}}
                                    throw new AssertionError(execution+" surface="+surface+" guarded="+guarded+" caves="+caves+" at "+pos[0]+","+(y+min)+","+pos[1]+": "+state+" vs Minecraft "+expected[y]+"; GPU spans "+spans);
                                }
                            }
                            for(int c:new int[]{0,15,240,255}) {
                                var column=NativeTerrain.instance().column(request,c);
                                for(int y=0;y<height;y++)if(column[y]!=actual[y*256+c])throw new AssertionError("Independent base-column query disagrees with GPU runs");
                            }
                            return (long)height*256;
                        }
                    }));
                    for(var f:futures)checked+=f.get();
                }
                if(execution.equals("specialized") && !guarded && !caves)
                    checked+=regionWithoutFeatures(id,min,height,palette.keySet().toArray(BlockState[]::new),expected);
                Files.writeString(Path.of("build/registry-material-profile.json"),data.toString());
            }
            if(varieties<7)throw new AssertionError("Material reference must exercise multiple layers, air and water: "+varieties);
            System.out.println("QA_EVT {\"event\":\"gpu_material_runs_match_minecraft\",\"status\":\"pass\",\"context\":{\"voxels\":"+checked+",\"materials\":"+varieties+"}}");
        }
    }
    /** A material program alone must select the field path, even with no cave/feature recipes. */
    private static long regionWithoutFeatures(int profile,int min,int height,BlockState[] palette,BlockState[] expected) throws Exception {
        var directory=Files.createTempDirectory("retina-material-only-");
        var origin=new ChunkPos(-64,-32);
        var request=new TerrainRequest(123456789L,origin.x(),origin.z(),min,height,64,48,.008F,profile);
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        long checked=0;
        try {
            var data=NativeTerrain.instance().generateRegionColumns(request,directory.resolve("r.-2.-1.mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:plains");
            if(data.report().generated()!=1024)throw new AssertionError("Material-only region must contain 1024 chunks");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-material-test",Level.OVERWORLD,"chunk"),directory,false);
                var cache=new BaseMaterialCache(directory.resolve("r.-2.-1.materials"),request,origin)) {
                for(int z:new int[]{-32,-1})for(int x:new int[]{-64,-33}) {
                    var pos=new ChunkPos(x,z);var tag=storage.read(pos);
                    if(tag==null)throw new AssertionError("Missing material-only corner chunk "+pos);
                    var sections=tag.getListOrEmpty("sections");
                    for(int i=0;i<sections.size();i++) {
                        var section=sections.getCompound(i).orElseThrow();
                        int y0=section.getByte("Y").orElseThrow()*16;
                        var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                        for(int y=0;y<16;y++)if(y0+y>=min && y0+y<min+height)
                            for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++) {
                                if(!blocks.get(lx,y,lz).equals(expected[y0+y-min]))throw new AssertionError("Material-only MCA differs from Minecraft at "+pos+" Y="+(y0+y));
                                checked++;
                            }
                    }
                    for(int column:new int[]{0,15,240,255})
                        if(!Arrays.equals(cache.column(pos,column,palette),expected))throw new AssertionError("Material-only preview base column differs from Minecraft at "+pos);
                }
            }
        } finally {
            try(var paths=Files.walk(directory)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("QA_EVT {\"event\":\"material_only_region\",\"status\":\"pass\",\"context\":{\"voxels\":"+checked+"}}");
        return checked;
    }
    private static MaterialRule rule(boolean guarded) {
        var floor=new SequenceRule(List.of(
                when(new StoneDepthCondition(0,false,0,CaveSurface.FLOOR),new SequenceRule(List.of(when(new NotCondition(new WaterCondition(0,0,false)),block(Blocks.SAND.defaultBlockState())),block(Blocks.GRASS_BLOCK.defaultBlockState())))),
                when(new StoneDepthCondition(0,true,4,CaveSurface.FLOOR),block(Blocks.DIRT.defaultBlockState())),
                when(new StoneDepthCondition(9,false,0,CaveSurface.FLOOR),block(Blocks.SANDSTONE.defaultBlockState()))));
        return new SequenceRule(List.of(when(new StoneDepthCondition(1,false,0,CaveSurface.CEILING),block(Blocks.CALCITE.defaultBlockState())),
                guarded?when(AbovePreliminarySurfaceCondition.INSTANCE,floor):floor,
                when(new NotCondition(new YCondition(new VerticalAnchor.Absolute(0),0,false)),block(Blocks.DEEPSLATE.defaultBlockState())),block(Blocks.STONE.defaultBlockState())));
    }
    private static MaterialRule when(MaterialCondition condition,MaterialRule next) {return new ConditionRule(condition,next);}
    private static MaterialRule block(BlockState state) {return new BlockRule(state);}
    private static JsonElement zero() {return constant(0);}
    private static JsonElement constant(float value) {return JsonParser.parseString("{\"nodes\":[{\"op\":0,\"a\":0,\"b\":0,\"c\":0,\"p\":["+value+",0,0,0]}],\"roots\":[0]}");}
    private static BlockState[] reference(MaterialRule rule,Holder<Biome> biome,int surface,int min,int height,boolean caves) throws Exception {
        var constructor=MaterialRuleContext.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
        var context=(MaterialRuleContext)constructor.newInstance(null,null,new DensityVolume(16,height,16,0,min,0),null,(java.util.function.Function<net.minecraft.core.BlockPos,Holder<Biome>>)(p->biome),null,Set.of(biome));
        set(context,"lastUpdateXZ",-1L);
        set(context,"surfaceDepth",3);set(context,"surfaceSecondary",0.0);set(context,"lastSurfaceDepth2Update",-1L);
        set(context,"minSurfaceLevel",surface-1+3-8);set(context,"lastMinSurfaceLevelUpdate",-1L);
        var update=MaterialRuleContext.class.getDeclaredMethod("updateY",int.class,int.class,int.class,int.class);update.setAccessible(true);
        var evaluator=rule.compile(context);var result=new BlockState[height];int above=0,below=min+height,water=Integer.MIN_VALUE;
        for(int y=min+height-1;y>=min;y--) {
            boolean solid=y<surface && (!caves || y<18 || y>30);
            if(!solid) {
                var state=y>=surface && y<63?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
                result[y-min]=state;
                if(state.isAir()){above=0;water=Integer.MIN_VALUE;}else if(water==Integer.MIN_VALUE)water=y+1;
            } else {
                if(below>=y){below=min;for(int look=y-1;look>=min;look--)if(caves && look>=18 && look<=30){below=look+1;break;}}
                above++;update.invoke(context,above,y-below+1,water,y);
                var state=evaluator.tryApply(0,y,0);result[y-min]=state==null?Blocks.STONE.defaultBlockState():state;
            }
        }
        return result;
    }
    private static void set(Object context,String name,Object value) throws Exception {var f=MaterialRuleContext.class.getDeclaredField(name);f.setAccessible(true);f.set(context,value);}
}
