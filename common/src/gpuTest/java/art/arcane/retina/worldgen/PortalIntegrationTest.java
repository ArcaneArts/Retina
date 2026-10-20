package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.attribute.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual RuinedPortalPiece.postProcess, including shared loot/spread RNG and neighbor shapes. */
final class PortalIntegrationTest {
    static int waterloggedPlacements, scheduledBlockTicks;
    record Plan(BlockPos pos,Rotation rotation,Mirror mirror,int template,boolean air,long randomSeed,int setup) {}
    static void run(RegistryAccess registry,ResourceManager resources,JsonObject source,BlockState[] materials) throws Exception {
        waterloggedPlacements=0;scheduledBlockTicks=0;
        var fixture=fixture(source);var terrain=NativeTerrain.instance();
        var base=fixture.deepCopy();base.add("structures",JsonParser.parseString("{\"templates\":[],\"definitions\":[],\"pools\":{},\"sets\":[]}"));int baseline=terrain.registerProfile(base.toString());
        var work=Files.createTempDirectory(Path.of("build/nether"),"portal-reference-");
        int cases=0,chunks=0;var chosen=new LinkedHashMap<String,Long>();
        for(long seed=0;chosen.size()<8;seed++) {
            var r=RandomSource.create(mix(seed));r.nextInt(1);boolean giant=r.nextFloat()<.05f;r.nextInt(giant?3:10);var rotation=Rotation.getRandom(r);var mirror=r.nextFloat()<.5f?Mirror.NONE:Mirror.FRONT_BACK;
            chosen.putIfAbsent(rotation+"/"+mirror,seed);
        }
        try(var storage=LevelStorageSource.createDefault(work).createAccess("templates")) {
            var manager=new StructureTemplateManager(resources,storage,DataFixers.getDataFixer(),registry.lookupOrThrow(Registries.BLOCK));
            for(int template=0;template<13;template++) {
                var variant=fixture.deepCopy();var kit=variant.getAsJsonObject("structures").getAsJsonObject("portals");var pair=kit.getAsJsonArray("templates").get(template).deepCopy();var ids=new JsonArray();for(int i=0;i<13;i++)ids.add(pair.deepCopy());kit.add("templates",ids);
                int profile=terrain.registerProfile(variant.toString());
                for(long seed:chosen.values()) {
                    var plan=plan(terrain,profile,seed,variant,materials,template,manager);
                    chunks+=placement(registry,resources,manager,terrain,profile,baseline,seed,plan,materials,variant,false);cases++;
                }
            }
            var custom=custom(fixture);int profile=terrain.registerProfile(custom.toString());var setups=new HashSet<Integer>();var air=new HashSet<Boolean>();
            for(long seed=0;seed<24;seed++) {
                var plan=plan(terrain,profile,seed,custom,materials,-1,manager,new ChunkPos(-512,512));setups.add(plan.setup);air.add(plan.air);
                chunks+=placement(registry,resources,manager,terrain,profile,baseline,seed,plan,materials,custom,seed==0);cases++;
            }
            require(setups.size()==3&&air.size()==2,"weighted setups and both air-pocket outcomes exercised");
            var flooded=fixture.deepCopy();var palette=new LinkedHashMap<BlockState,Integer>();for(var state:materials)palette.put(state,palette.size());
            int water=palette.computeIfAbsent(Blocks.WATER.defaultBlockState(),s->palette.size());
            StructureProfile.finishMaterials(flooded.getAsJsonObject("structures"),palette);DecorationProfile.materialFlags(flooded,palette);
            var materialJson=new JsonArray();palette.keySet().forEach(s->materialJson.add(BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,s).getOrThrow()));flooded.add("materials",materialJson);flooded.remove("lighting");
            var wetMaterials=palette.keySet().toArray(BlockState[]::new);
            flooded.remove("registry_program");flooded.remove("generation");flooded.remove("generation_bounds");flooded.add("climate_targets",new JsonArray());
            var biomes=new JsonArray();biomes.add(flooded.getAsJsonArray("biomes").get(0));flooded.add("biomes",biomes);
            flooded.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().add("biomes",StructureProfile.ints(0));
            var flat=new JsonObject();var layers=new JsonArray();for(int i=0;i<256;i++)layers.add(water);flat.add("base_layers",layers);flat.add("deferred_layers",new JsonArray());flat.add("air_materials",StructureProfile.ints(0));flooded.add("flat",flat);
            var floodedBase=flooded.deepCopy();floodedBase.add("structures",base.get("structures").deepCopy());int floodedBaseline=terrain.registerProfile(floodedBase.toString());
            int floodedProfile=terrain.registerProfile(flooded.toString());
            for(long seed=0;seed<8;seed++) {
                var plan=plan(terrain,floodedProfile,seed,flooded,wetMaterials,-1,manager);
                chunks+=placement(registry,resources,manager,terrain,floodedProfile,floodedBaseline,seed,plan,wetMaterials,flooded,false);cases++;
            }
        }
        require(waterloggedPlacements>0&&scheduledBlockTicks>0,"wet placements and bubble-column block ticks exercised");
        var report=new JsonObject();report.addProperty("source_piece_replays",cases);report.addProperty("compared_chunks",chunks);report.addProperty("compared_voxels",chunks*65536L);report.addProperty("waterlogged_placements",waterloggedPlacements);report.addProperty("scheduled_block_ticks",scheduledBlockTicks);
        Files.writeString(Path.of("build/nether/portal-placement.json"),report.toString());System.out.println("QA_EVT {\"event\":\"nether_portal_placement\",\"status\":\"pass\",\"context\":"+report+"}");
    }
    static String templateName(int index){return "minecraft:ruined_portal/"+(index<10?"portal_"+(index+1):"giant_portal_"+(index-9));}
    static JsonObject custom(JsonObject source) {
        var fixture=source.deepCopy();var structures=fixture.getAsJsonObject("structures");var config=structures.getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config");
        var original=config.getAsJsonArray("setups").get(0).getAsJsonObject();var pools=structures.getAsJsonObject("pools");var pool=pools.getAsJsonObject(original.get("retina_pool").getAsString());var setups=new JsonArray();
        for(int i=0;i<3;i++) {
            var setup=original.deepCopy();setup.addProperty("weight",i+1);setup.addProperty("air_pocket_probability",i*.5f);setup.addProperty("mossiness",i*.5f);
            setup.addProperty("overgrown",true);setup.addProperty("vines",true);setup.addProperty("replace_with_blackstone",i==1);
            String id="retina:portal_fixture/"+i;setup.addProperty("retina_pool",id);setups.add(setup);
            var variant=pool.deepCopy();var part=variant.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject();var processors=new JsonArray();
            for(var value:part.getAsJsonArray("processors")) {
                var processor=value.getAsJsonObject();String type=processor.get("processor_type").getAsString();
                if(type.equals("minecraft:block_age"))processor.addProperty("mossiness",i*.5f);
                if(!type.equals("minecraft:blackstone_replace")||i==1)processors.add(processor);
            }
            part.add("processors",processors);pools.add(id,variant);
        }
        config.add("setups",setups);return fixture;
    }
    static JsonObject fixture(JsonObject source) {
        var fixture=source.deepCopy();fixture.add("decorations",new JsonArray());fixture.add("terrain_features",new JsonObject());
        for(var value:fixture.getAsJsonArray("biomes")){var b=value.getAsJsonObject();b.add("decorations",new JsonArray());b.add("cave_features",new JsonObject());}
        var structures=fixture.getAsJsonObject("structures");var definitions=new JsonArray();
        var definition=structures.getAsJsonArray("definitions").asList().stream().map(JsonElement::getAsJsonObject).filter(d->d.get("id").getAsString().equals("minecraft:ruined_portal_nether")).findFirst().orElseThrow();definitions.add(definition);structures.add("definitions",definitions);
        structures.add("sets",JsonParser.parseString("[{\"id\":\"retina:portal_fixture\",\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":512,\"separation\":511,\"salt\":0},\"entries\":[{\"definition\":0,\"weight\":1}]}]"));
        return fixture;
    }
    static Plan plan(NativeTerrain terrain,int profile,long seed,JsonObject fixture,BlockState[] materials,int forcedTemplate,StructureTemplateManager manager) {
        return plan(terrain,profile,seed,fixture,materials,forcedTemplate,manager,new ChunkPos(0,0));
    }
    static Plan plan(NativeTerrain terrain,int profile,long seed,JsonObject fixture,BlockState[] materials,int forcedTemplate,StructureTemplateManager manager,ChunkPos at) {
        long randomSeed=mix(seed^(long)at.x()*341873128712L^(long)at.z()*132897987541L);var random=RandomSource.create(randomSeed);random.nextInt(1);
        var setups=fixture.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").getAsJsonArray("setups");
        int selected=0;
        if(setups.size()>1) {
            float total=0;for(var value:setups)total+=value.getAsJsonObject().get("weight").getAsFloat();float pick=random.nextFloat();
            for(;selected<setups.size();selected++){pick-=setups.get(selected).getAsJsonObject().get("weight").getAsFloat()/total;if(pick<0)break;}
        }
        var setup=setups.get(selected).getAsJsonObject();
        float probability=setup.get("air_pocket_probability").getAsFloat();boolean air=probability==1||probability!=0&&random.nextFloat()<probability;
        boolean giant=random.nextFloat()<.05f;int selectedTemplate=(giant?10:0)+random.nextInt(giant?3:10);if(forcedTemplate<0)forcedTemplate=selectedTemplate;var rotation=Rotation.getRandom(random);var mirror=random.nextFloat()<.5f?Mirror.NONE:Mirror.FRONT_BACK;
        var template=manager.getOrCreate(Identifier.parse(templateName(forcedTemplate)));var size=template.getSize();var pivot=new BlockPos(size.getX()/2,0,size.getZ()/2);
        var bounds=template.getBoundingBox(at.getWorldPosition(),rotation,pivot,mirror);
        int y=air?32+random.nextInt(69):random.nextFloat()<.5f?27+random.nextInt(3):29+random.nextInt(72);
        var columns=new ArrayList<short[]>();for(int[] corner:new int[][]{{bounds.minX(),bounds.minZ()},{bounds.maxX(),bounds.minZ()},{bounds.minX(),bounds.maxZ()},{bounds.maxX(),bounds.maxZ()}}) {
            var request=request(profile,seed,Math.floorDiv(corner[0],16),Math.floorDiv(corner[1],16));columns.add(terrain.column(request,(corner[1]&15)*16+(corner[0]&15)));
        }
        for(;y>15;y--){int corners=0;for(var column:columns)if(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(materials[Short.toUnsignedInt(column[y])]))corners++;if(corners>=3)break;}
        return new Plan(new BlockPos(at.getMinBlockX(),y,at.getMinBlockZ()),rotation,mirror,forcedTemplate,air,randomSeed,selected);
    }
    static int placement(RegistryAccess registry,ResourceManager resources,StructureTemplateManager manager,NativeTerrain terrain,int profile,int base,long seed,Plan plan,BlockState[] materials,JsonObject fixture,boolean mca) throws Exception {
        var setup=fixture.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").getAsJsonArray("setups").get(plan.setup).getAsJsonObject();
        var name=Identifier.parse(templateName(plan.template));var template=manager.getOrCreate(name);var size=template.getSize();
        var piece=new RuinedPortalPiece(registry,manager,plan.pos,RuinedPortalPiece.VerticalPlacement.IN_NETHER,
                new RuinedPortalPiece.Properties(false,setup.get("mossiness").getAsFloat(),plan.air,setup.get("overgrown").getAsBoolean(),setup.get("vines").getAsBoolean(),setup.get("replace_with_blackstone").getAsBoolean()),name,template,plan.rotation,plan.mirror,new BlockPos(size.getX()/2,0,size.getZ()/2));
        var baseline=new HashMap<ChunkPos,short[]>();
        class Reference extends FortressProfile.Capture {
            Reference(){super(registry);random=RandomSource.create(plan.randomSeed);}
            @Override BlockState state(BlockPos p) {
                if(blocks.containsKey(p))return blocks.get(p);if(p.getY()<0||p.getY()>=256)return Blocks.AIR.defaultBlockState();
                var at=ChunkPos.containing(p);var values=baseline.computeIfAbsent(at,k->PortalIntegrationTest.blocks(terrain,request(base,seed,k.x(),k.z())));
                return materials[Short.toUnsignedInt(values[p.getY()*256+(p.getZ()&15)*16+(p.getX()&15)])];
            }
            int surface(int x,int z){for(int y=255;y>=0;y--)if(!state(new BlockPos(x,y,z)).isAir())return y+1;return 0;}
        }
        var dimension=registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER).value();
        var attributes=new EnvironmentAttributeReader() {
            public <V> V getDimensionValue(EnvironmentAttribute<V> attribute){return dimension.attributes().applyModifier(attribute,attribute.defaultValue());}
            public <V> V getValue(EnvironmentAttribute<V> attribute,Vec3 position,SpatialAttributeInterpolator interpolator){return getDimensionValue(attribute);}
        };
        record BlockTick(BlockPos pos,Block block,int priority) {}
        var blockTicks=new ArrayList<BlockTick>();
        var reference=new Reference();WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)-> {
            switch(method.getName()) {
                case "getMinY":return 0;case "getMaxY":return 255;case "getSeed":return seed;
                case "dimensionType":return dimension;
                case "environmentAttributes":return attributes;
                case "enabledFeatures":return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
                case "holderLookup","setBlockAndUpdate":return InvocationHandler.invokeDefault(proxy,method,args);
                case "setBlock": {
                    var pos=((BlockPos)args[0]).immutable();var state=(BlockState)args[1];var previous=reference.entities.get(pos);
                    boolean retain=previous!=null&&reference.state(pos).is(state.getBlock());
                    var result=method.invoke(reference.level,args);
                    if(retain){previous.setBlockState(state);reference.entities.put(pos,previous);}
                    return result;
                }
                case "scheduleTick":if(args[1] instanceof Block block) {blockTicks.add(new BlockTick(((BlockPos)args[0]).immutable(),block,args.length>3?((net.minecraft.world.ticks.TickPriority)args[3]).getValue():0));return null;}else return method.invoke(reference.level,args);
                case "getHeight":return reference.surface((int)args[1],(int)args[2]);
                case "getHeightmapPos":{var p=(BlockPos)args[1];return new BlockPos(p.getX(),reference.surface(p.getX(),p.getZ()),p.getZ());}
                case "updateNeighborsAt","blockUpdated":return null;
                default:try{return method.invoke(reference.level,args);}catch(InvocationTargetException e){throw e.getCause();}
            }
        });
        piece.postProcess(level,null,null,reference.random,BoundingBox.infinite(),new ChunkPos(0,0),BlockPos.ZERO);
        waterloggedPlacements+=(int)reference.blocks.values().stream().filter(s->s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)&&s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)).count();
        scheduledBlockTicks+=blockTicks.size();
        var ids=new HashMap<BlockState,Integer>();for(int i=0;i<materials.length;i++)ids.put(materials[i],i);
        var box=piece.getBoundingBox();var center=box.getCenter();int minX=Math.min(box.minX(),center.getX()-14)-1,maxX=Math.max(box.maxX(),center.getX()+14)+1,minZ=Math.min(box.minZ(),center.getZ()-14)-1,maxZ=Math.max(box.maxZ(),center.getZ()+14)+1;
        var start=ChunkPos.containing(plan.pos);var metadata=terrain.structureStarts(request(profile,seed,start.x(),start.z()));var tag=metadata.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:ruined_portal_nether");
        var saved=StructureStart.loadStaticStart(new StructurePieceSerializationContext(resources,registry,manager),tag,seed);
        require(saved!=null&&saved.isValid()&&saved.getBoundingBox().equals(box),"actual portal floor/template/mirror/pivot bounds "+plan);
        require(terrain.queryStructureStarts(request(profile,seed,start.x(),start.z()),new int[]{start.x(),start.z(),0})[0]==0,"portal locate shares accepted start");
        int count=0;var generated=new LinkedHashMap<ChunkPos,short[]>();
        for(int z=Math.floorDiv(minZ,16);z<=Math.floorDiv(maxZ,16);z++)for(int x=Math.floorDiv(minX,16);x<=Math.floorDiv(maxX,16);x++) {
            var at=new ChunkPos(x,z);var expected=baseline.computeIfAbsent(at,k->blocks(terrain,request(base,seed,k.x(),k.z()))).clone();
            reference.blocks.forEach((p,state)->{if(ChunkPos.containing(p).equals(at))expected[p.getY()*256+(p.getZ()&15)*16+(p.getX()&15)]=Objects.requireNonNull(ids.get(state),"unexported portal state "+state).shortValue();});
            var r=request(profile,seed,x,z);var actual=blocks(terrain,r);generated.put(at,actual);int mismatch=Arrays.mismatch(expected,actual);
            require(mismatch<0,"portal full postProcess "+plan+" chunk="+at+" index="+mismatch+(mismatch<0?"":" expected="+materials[Short.toUnsignedInt(expected[mismatch])]+" actual="+materials[Short.toUnsignedInt(actual[mismatch])]));
            var data=terrain.structureData(r);var expectedEntities=new HashMap<BlockPos,CompoundTag>();
            reference.entities.forEach((p,entity)->{if(ChunkPos.containing(p).equals(at))expectedEntities.put(p,entity.saveWithFullMetadata(registry));});
            require(data.getListOrEmpty("block_entities").size()==expectedEntities.size(),"portal block entity count");
            for(var value:data.getListOrEmpty("block_entities")) {
                var entity=((CompoundTag)value).copy();var p=new BlockPos(entity.getIntOr("x",0),entity.getIntOr("y",0),entity.getIntOr("z",0));entity.remove("keepPacked");
                entity=Objects.requireNonNull(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p,reference.state(p),entity,registry),"load native portal block entity").saveWithFullMetadata(registry);
                require(entity.equals(expectedEntities.get(p)),"portal block entity and exact shared loot RNG at "+p+" expected="+expectedEntities.get(p)+" actual="+entity);
            }
            var expectedTicks=new ListTag();var seen=new HashSet<String>();
            for(var tick:reference.ticks)if(ChunkPos.containing(tick.pos()).equals(at)&&seen.add(tick.pos()+"/"+BuiltInRegistries.FLUID.getKey(tick.fluid()))) {
                var t=new CompoundTag();t.putString("i",BuiltInRegistries.FLUID.getKey(tick.fluid()).toString());t.putInt("x",tick.pos().getX());t.putInt("y",tick.pos().getY());t.putInt("z",tick.pos().getZ());t.putInt("t",0);t.putInt("p",tick.priority());expectedTicks.add(t);
            }
            require(data.getListOrEmpty("fluid_ticks").equals(expectedTicks),"portal source fluid updates "+plan+" chunk="+at+" expected="+expectedTicks+" actual="+data.getListOrEmpty("fluid_ticks"));count++;
            var expectedBlockTicks=new ListTag();seen.clear();
            for(var tick:blockTicks)if(ChunkPos.containing(tick.pos()).equals(at)&&seen.add(tick.pos()+"/"+BuiltInRegistries.BLOCK.getKey(tick.block()))) {
                var t=new CompoundTag();t.putString("i",BuiltInRegistries.BLOCK.getKey(tick.block()).toString());t.putInt("x",tick.pos().getX());t.putInt("y",tick.pos().getY());t.putInt("z",tick.pos().getZ());t.putInt("t",0);t.putInt("p",tick.priority());expectedBlockTicks.add(t);
            }
            require(data.getListOrEmpty("block_ticks").equals(expectedBlockTicks),"portal source block updates "+plan+" chunk="+at+" expected="+expectedBlockTicks+" actual="+data.getListOrEmpty("block_ticks"));
        }
        if(mca)persist(terrain,profile,seed,generated,materials,registry,resources,manager,start,box,fixture);
        return count;
    }
    static TerrainRequest request(int profile,long seed,int x,int z){return new TerrainRequest(seed,x,z,0,256,64,48,.008f,profile);}
    static void persist(NativeTerrain terrain,int profile,long seed,Map<ChunkPos,short[]> generated,BlockState[] materials,RegistryAccess registry,ResourceManager resources,StructureTemplateManager manager,ChunkPos start,BoundingBox box,JsonObject fixture) throws Exception {
        int cold=terrain.registerProfile(fixture.toString());Files.writeString(Path.of("build/nether/portal-fixture-profile.json"),fixture.toString());
        try(var workers=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();var reverse=new ArrayList<>(generated.entrySet());Collections.reverse(reverse);
            for(var entry:reverse)jobs.add(workers.submit(()->require(Arrays.equals(entry.getValue(),blocks(terrain,request(cold,seed,entry.getKey().x(),entry.getKey().z()))),"cold concurrent portal replay")));
            for(var job:jobs)job.get();
        }
        var directory=Files.createTempDirectory(Path.of("build/nether"),"portal-regions-");Files.writeString(Path.of("build/nether/portal-regions-path.txt"),directory.toAbsolutePath().toString());
        var regions=new HashSet<ChunkPos>();generated.keySet().forEach(p->regions.add(new ChunkPos(p.getRegionX()*32,p.getRegionZ()*32)));
        require(regions.size()>1,"portal footprint crosses MCA region boundary");
        for(var p:regions)terrain.generateRegion(request(profile,seed,p.x(),p.z()),directory.resolve("r."+p.getRegionX()+"."+p.getRegionZ()+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:nether_wastes");
        var context=new StructurePieceSerializationContext(resources,registry,manager);var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-portals",Level.NETHER,"chunk"),directory,false)) {
            for(var entry:generated.entrySet()) {
                var chunk=Objects.requireNonNull(storage.read(entry.getKey()),"saved portal chunk");var data=terrain.structureData(request(profile,seed,entry.getKey().x(),entry.getKey().z()));
                for(String key:List.of("structures","block_entities","fluid_ticks","block_ticks"))require(Objects.equals(chunk.get(key),data.get(key))||chunk.getListOrEmpty(key).isEmpty()&&data.getListOrEmpty(key).isEmpty()&&!key.equals("structures"),"saved portal "+key);
                if(entry.getKey().equals(start)) {
                    var reopened=StructureStart.loadStaticStart(context,chunk.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:ruined_portal_nether"),seed);
                    require(reopened!=null&&reopened.isValid()&&reopened.getBoundingBox().equals(box),"saved portal source identity and bounds");
                }
                for(var section:McaTestSections.terrain(chunk,0,256)) {
                    int y=section.getIntOr("Y",0)*16;var states=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for(int yy=0;yy<16;yy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)require(states.get(x,yy,z).equals(materials[Short.toUnsignedInt(entry.getValue()[(y+yy)*256+z*16+x])]),"portal MCA blocks");
                }
            }
        }
        var report=new JsonObject();report.addProperty("seed",seed);report.addProperty("start_chunk_x",start.x());report.addProperty("start_chunk_z",start.z());report.addProperty("chunks",generated.size());report.addProperty("regions",regions.size());
        Files.writeString(Path.of("build/nether/portal-mca.json"),report.toString());
    }
    static short[] blocks(NativeTerrain terrain,TerrainRequest request){try(var chunk=terrain.generate(request)){return chunk.blocks().toArray(ValueLayout.JAVA_SHORT);}}
    static long mix(long v){v=(v^v>>>30)*0xbf58476d1ce4e5b9L;v=(v^v>>>27)*0x94d049bb133111ebL;return v^v>>>31;}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
