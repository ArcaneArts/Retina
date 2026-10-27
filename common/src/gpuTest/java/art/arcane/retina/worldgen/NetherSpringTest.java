package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.ticks.*;

import java.nio.file.*;
import java.util.*;

/** Spring reference replay, including source fluid identities and saved tick semantics. */
final class NetherSpringTest {
    static void check(RegistryAccess registry,TerrainProfileData exported,NoiseBasedChunkGenerator generator) throws Exception {
        var original=JsonParser.parseString(exported.json()).getAsJsonObject();
        var palette=new LinkedHashMap<BlockState,Integer>();for(var state:exported.materials())palette.put(state,palette.size());
        var origins=List.of(Blocks.AIR.defaultBlockState(),Blocks.NETHERRACK.defaultBlockState(),Blocks.STONE.defaultBlockState(),
                Blocks.CAVE_AIR.defaultBlockState(),Blocks.LAVA.defaultBlockState());
        var air=List.of(Blocks.AIR.defaultBlockState(),Blocks.CAVE_AIR.defaultBlockState(),Blocks.VOID_AIR.defaultBlockState());
        origins.forEach(s->palette.computeIfAbsent(s,ignored->palette.size()));air.forEach(s->palette.computeIfAbsent(s,ignored->palette.size()));
        var features=new ArrayList<SpringFeature>();var names=new ArrayList<String>();
        for(String name:List.of("spring_lava","spring_delta","spring_closed","spring_closed_double","spring_open")) {
            features.add((SpringFeature)NativeBlockFeatureIntegrationTest.resolve(registry,"minecraft:"+name));names.add(name);
        }
        var valid=HolderSet.direct(Blocks.NETHERRACK.builtInRegistryHolder());
        for(boolean below:List.of(false,true))for(int holes=0;holes<=5;holes++) {
            features.add(new SpringFeature(Fluids.WATER.defaultFluidState(),below,5-holes,holes,valid));names.add("water/below="+below+"/holes="+holes);
        }
        for(var fluid:List.of(Fluids.FLOWING_WATER.getFlowing(3,true),Fluids.FLOWING_LAVA.getFlowing(2,false))) {
            features.add(new SpringFeature(fluid,true,4,1,valid));names.add(BuiltInRegistries.FLUID.getKey(fluid.getType())+"/level="+fluid.getAmount()+"/falling="+fluid.getValue(FlowingFluid.FALLING));
        }
        for(int count:new int[]{-1,6}) {features.add(new SpringFeature(Fluids.LAVA.defaultFluidState(),false,count,1,valid));names.add("impossible_rocks="+count);}
        var includesAir=HolderSet.direct(Blocks.NETHERRACK.builtInRegistryHolder(),Blocks.AIR.builtInRegistryHolder(),Blocks.CAVE_AIR.builtInRegistryHolder(),Blocks.VOID_AIR.builtInRegistryHolder());
        for(int holes=0;holes<=5;holes++) {features.add(new SpringFeature(Fluids.LAVA.defaultFluidState(),true,5,holes,includesAir));names.add("air_is_valid/holes="+holes);}
        var recipes=new JsonArray();var definitions=new JsonArray();var tickIds=new LinkedHashMap<String,Integer>();
        for(int i=0;i<features.size();i++) {
            var wrapper=NetherFeatureProfile.export(features.get(i),registry,s->palette.computeIfAbsent(s,ignored->palette.size()),
                    p->{throw new AssertionError("unexpected provider");},p->{throw new AssertionError("unexpected nested placement");},generator.getGenDepth(),
                    (kind,id)->tickIds.computeIfAbsent(id,ignored->{var d=new JsonObject();d.addProperty("kind",kind);d.addProperty("id",id);definitions.add(d);return definitions.size();}));
            var recipe=JsonParser.parseString("""
                    {"salt":0,"density":1,"low_density":1,"noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],"placement":[]}
                    """).getAsJsonObject();
            wrapper.entrySet().forEach(e->recipe.add(e.getKey(),e.getValue()));recipe.addProperty("source",names.get(i));recipes.add(recipe);
        }
        DecorationProfile.finishPlacements(recipes,palette,GenerationBounds.noise(generator.generatorSettings().value().noiseSettings(),0,256),generator.getSeaLevel());
        DecorationProfile.materialFlags(original,palette);
        var materials=palette.keySet().toArray(BlockState[]::new);var serialized=new JsonArray();var carveable=new JsonArray();
        for(var state:materials) {serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.UNCARVABLE));}
        original.add("materials",serialized);original.add("carveable",carveable);original.add("lighting",LightingProfile.export(palette.keySet(),false));
        original.add("decorations",recipes);original.add("decoration_ticks",definitions);original.add("ores",new JsonArray());
        for(var b:original.getAsJsonArray("biomes")) {
            for(String key:List.of("decorations","ores","carvers"))b.getAsJsonObject().add(key,new JsonArray());b.getAsJsonObject().remove("cave_features");
        }
        int cases=0,accepted=0;var successes=new TreeMap<String,Integer>();
        var offsets=List.of(new BlockPos(0,1,0),new BlockPos(0,-1,0),new BlockPos(-1,0,0),new BlockPos(1,0,0),new BlockPos(0,0,-1),new BlockPos(0,0,1));
        for(int mask=0;mask<64;mask++) {
            var scene=new LinkedHashMap<BlockPos,BlockState>();
            for(int origin=0;origin<origins.size();origin++) {
                var at=new BlockPos(-25+origin*4,64,-17);scene.put(at,origins.get(origin));
                for(int n=0;n<6;n++)scene.put(at.offset(offsets.get(n)),(mask&(1<<n))!=0?(origin==2?Blocks.STONE:Blocks.NETHERRACK).defaultBlockState():air.get(n%air.size()));
            }
            var data=original.deepCopy();var compiler=new RegistryGpuProgram(registry,0,128,32);
            var density=compiler.new Program();density.roots.add(density.constant(1));density.roots.add(density.constant(128));
            var gpu=data.getAsJsonObject("registry_program");var programs=gpu.getAsJsonArray("programs");programs.set(1,density.finish());programs.set(2,density.finish());gpu.add("interpolations",new JsonArray());
            var material=compiler.new Program();int x=material.node(29,0,0,0),y=material.node(29,1,0,0),z=material.node(29,2,0,0);
            int root=material.constant(palette.get(Blocks.NETHERRACK.defaultBlockState())+1);
            for(var e:scene.entrySet()) {
                var p=e.getKey();int value=material.constant(palette.get(e.getValue())+1);
                value=material.node(23,x,value,root,p.getX(),p.getX()+1);
                value=material.node(23,y,value,root,p.getY(),p.getY()+1);
                root=material.node(23,z,value,root,p.getZ(),p.getZ()+1);
            }
            material.roots.add(root);for(int i=0;i<exported.biomes().size();i++)programs.set(3+i,material.finish());
            try(var saved=NetherTerrainTest.fixture(data,42,NativeWorldDefinition.ProgramExecution.INTERPRETER)) {
                var request=saved.request(-2,-2);
                var terrain=new HashMap<ChunkPos,short[]>();
                for(int origin=0;origin<origins.size();origin++)for(int id=0;id<features.size();id++) {
                    var at=new BlockPos(-25+origin*4,64,-17);var world=new NetherFeatureTest.World(request,materials,terrain,
                            p->p.getY()>=128?Blocks.AIR.defaultBlockState():scene.getOrDefault(p,Blocks.NETHERRACK.defaultBlockState()));
                    boolean placed=features.get(id).place(world.level,generator,new NativeBlockFeatureIntegrationTest.Stream(42,false),at);
                    var actual=new HashMap<BlockPos,BlockState>();var output=NativeTerrain.instance().decorationFeature(request,id,new int[]{at.getX(),at.getY(),at.getZ()},42);
                    for(int i=0;i<output.length;i+=4)actual.put(new BlockPos(output[i],output[i+1],output[i+2]),materials[output[i+3]]);
                    String context=names.get(id)+" mask="+mask+" origin="+origins.get(origin);
                    require(actual.equals(world.changed),"spring blocks "+context+": "+NativeBlockFeatureIntegrationTest.differences(world.changed,actual));
                    var ticks=new ArrayList<NetherFeatureTest.Tick>();output=NativeTerrain.instance().decorationFeatureTicks(request,id,new int[]{at.getX(),at.getY(),at.getZ()},42);
                    for(int i=0;i<output.length;i+=4)ticks.add(new NetherFeatureTest.Tick(new BlockPos(output[i],output[i+1],output[i+2]),definitions.get(output[i+3]-1).getAsJsonObject().get("id").getAsString(),0));
                    require(ticks.equals(world.ticks),"spring scheduled updates "+context+" expected="+world.ticks+" actual="+ticks);
                    if(placed) {accepted++;successes.merge(names.get(id),1,Integer::sum);}cases++;
                }
            }
        }
        require(accepted>100 && successes.size()==features.size()-3,"every possible spring recipe accepted; negative/excess rock counts and five holes with required rock below rejected: "+successes);
        checkInstallation(registry,exported,generator);
        var stats=new JsonObject();stats.addProperty("cases",cases);stats.addProperty("accepted",accepted);stats.addProperty("rejected",cases-accepted);stats.addProperty("fluid_types",definitions.size());stats.add("successes",new Gson().toJsonTree(successes));
        Files.writeString(Path.of("build/nether/spring-reference-stats.json"),stats.toString());
        System.out.println("QA_EVT {\"event\":\"nether_springs_reference\",\"status\":\"pass\",\"context\":"+stats+"}");
    }

    private static void checkInstallation(RegistryAccess registry,TerrainProfileData exported,NoiseBasedChunkGenerator source) throws Exception {
        var at=new BlockPos(-17,64,-33);var pos=ChunkPos.containing(at);
        var lava=new SavedTick<Fluid>(Fluids.LAVA,at,0,TickPriority.NORMAL);
        var water=new SavedTick<Fluid>(Fluids.WATER,at,0,TickPriority.NORMAL);
        var fluidCodec=SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf();
        var blockCodec=SavedTick.codec(BuiltInRegistries.BLOCK.byNameCodec()).listOf();
        var data=new CompoundTag();
        data.put("fluid_ticks",fluidCodec.encodeStart(NbtOps.INSTANCE,List.of(lava,lava,water,new SavedTick<Fluid>(Fluids.LAVA,at.offset(16,0,0),0,TickPriority.NORMAL))).getOrThrow());
        data.put("block_ticks",blockCodec.encodeStart(NbtOps.INSTANCE,List.of(new SavedTick<>(Blocks.FIRE,at,0,TickPriority.NORMAL))).getOrThrow());
        var chunk=new ProtoChunk(pos,UpgradeData.EMPTY,LevelHeightAccessor.create(0,256),PalettedContainerFactory.create(registry),null);
        var generator=new RetinaChunkGenerator(new RetinaBiomeSource(List.of(registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.NETHER_WASTES)),256,.55f),0,256,64,48,.008f,"mca");
        generator.installDecorationData(chunk,data,null,registry);generator.installDecorationData(chunk,data,null,registry);
        require(chunk.getFluidTicks().count()==2 && chunk.getBlockTicks().count()==1,"proto-chunk installation deduplicates type/position, retains block ticks and filters foreign chunks");
        var saved=chunk.getTicksForSerialization(12345);
        var roundtrip=fluidCodec.parse(NbtOps.INSTANCE,fluidCodec.encodeStart(NbtOps.INSTANCE,saved.fluids()).getOrThrow()).getOrThrow();
        require(roundtrip.equals(List.of(lava,water)),"Minecraft saves installed fluid identities, order, delay and priority");
        var reloaded=ProtoChunkTicks.load(roundtrip);require(reloaded.count()==2,"Minecraft reload keeps saved updates");
        var active=new LevelChunkTicks<>(reloaded.scheduledTicks());active.unpack(12345);
        var first=active.poll();var second=active.poll();
        require(first.type()==Fluids.LAVA && second.type()==Fluids.WATER && first.triggerTick()==12345 && second.triggerTick()==12345,"saved updates become immediately runnable after chunk activation");
        try(var definition=NetherTerrainTest.fixture(registry,source,exported,42)) {
            var request=definition.request(-15,16);
            var metadata=NativeTerrain.instance().structureData(request);
            var expected=fluidCodec.parse(NbtOps.INSTANCE,metadata.getListOrEmpty("fluid_ticks")).getOrThrow();
            require(!expected.isEmpty(),"real native chunk supplies fluid updates");
            var generated=new ProtoChunk(new ChunkPos(-15,16),UpgradeData.EMPTY,LevelHeightAccessor.create(0,256),PalettedContainerFactory.create(registry),null);
            generator.installDecorationData(generated,metadata,null,registry);
            require(generated.getTicksForSerialization(12345).fluids().equals(expected),"actual native metadata reaches Minecraft's serialized proto-chunk ticks");
        }
    }

    static int checkRegionTicks(CompoundTag tag,ChunkPos chunk) {
        var ticks=SavedTick.codec(BuiltInRegistries.FLUID.byNameCodec()).listOf().parse(NbtOps.INSTANCE,tag.getListOrEmpty("fluid_ticks")).getOrThrow();
        var positions=new HashSet<BlockPos>();
        for(var tick:ticks) {
            require(tick.type()==Fluids.LAVA && tick.delay()==0 && tick.priority()==TickPriority.NORMAL,"loaded Nether springs save immediate lava updates");
            require(ChunkPos.containing(tick.pos()).equals(chunk) && tick.pos().getY()>=0 && tick.pos().getY()<256,"saved fluid update belongs to its chunk");
            require(positions.add(tick.pos()),"same-type scheduled updates are deduplicated");
        }
        return ticks.size();
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
