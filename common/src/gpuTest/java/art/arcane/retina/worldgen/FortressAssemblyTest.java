package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class FortressAssemblyTest {
    static void run(RegistryAccess registry, ResourceManager resources, JsonObject source, BlockState[] materials) throws Exception {
        var context = new StructurePieceSerializationContext(resources, registry, null);
        var fixture = source.deepCopy(); fixture.add("decorations",new JsonArray()); fixture.add("terrain_features",new JsonObject());
        for (var b : fixture.getAsJsonArray("biomes")) {
            b.getAsJsonObject().add("decorations",new JsonArray()); b.getAsJsonObject().add("cave_features",new JsonObject());
        }
        var structures = fixture.getAsJsonObject("structures"); var definitions = structures.getAsJsonArray("definitions");
        int index = -1;
        for (int i=0;i<definitions.size();i++) if(definitions.get(i).getAsJsonObject().get("id").getAsString().equals("minecraft:fortress"))index=i;
        require(index>=0 && !definitions.get(index).getAsJsonObject().has("unimplemented"),"fortress executable");
        var allowed = new JsonArray(); for(int i=0;i<fixture.getAsJsonArray("biomes").size();i++)allowed.add(i);
        definitions.get(index).getAsJsonObject().add("biomes",allowed);
        structures.add("sets",JsonParser.parseString("[{\"id\":\"retina:fortress_fixture\",\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":512,\"separation\":511,\"salt\":0},\"entries\":[{\"definition\":"+index+",\"weight\":1}]}]"));
        Files.writeString(Path.of("build/nether/fortress-fixture-profile.json"),fixture.toString());
        var nativeTerrain = NativeTerrain.instance(); int profile = nativeTerrain.registerProfile(fixture.toString());
        var baseline = fixture.deepCopy(); baseline.add("structures",JsonParser.parseString("{\"templates\":[],\"definitions\":[],\"pools\":{},\"sets\":[]}"));
        int baseProfile = nativeTerrain.registerProfile(baseline.toString());
        var kinds = new HashSet<String>(); int layouts=0,pieces=0; List<StructurePiece> selected=null; long selectedSeed=0; long mostChests=0;
        for(long seed:new long[]{0,1,42,1945,123456789,-1}) for(int[] at:new int[][]{{0,0},{-512,512}}) {
            var request = request(profile,seed,at[0],at[1]);
            var tag = nativeTerrain.structureStarts(request).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:fortress");
            var expected = reference(seed,at[0],at[1]); var children = tag.getListOrEmpty("Children");
            require(children.size()==expected.size(),"source connected-piece count seed="+seed+" at="+Arrays.toString(at)+" native="+children.size()+" source="+expected.size());
            for(int i=0;i<children.size();i++) {
                var actual=(CompoundTag)children.get(i); var piece=expected.get(i); String kind=kind(piece); kinds.add(kind);
                int variant=piece.createTag(null).getBooleanOr("Chest",false)?1:0;
                String id="retina:captured/fortress/"+kind+"/"+piece.getOrientation().getName()+(kind.equals("end_filler")?"":"/"+variant);
                require(actual.getStringOr("template","").equals(id),"source weighted piece/orientation/variant seed="+seed+" piece="+i+" expected="+id+" native="+actual.getStringOr("template",""));
                require(actual.read("BB",BoundingBox.CODEC).orElseThrow().equals(piece.getBoundingBox()),"source attachment/collision/height bounds "+i);
                require(actual.getIntOr("GD",-1)==piece.getGenDepth(),"source graph depth");
            }
            var decoded=StructureStart.loadStaticStart(context,tag,seed); require(decoded!=null && decoded.isValid(),"saved fortress loads");
            require(decoded.getStructure()==registry.lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse("minecraft:fortress")),"actual fortress identity for spawning/locate");
            require(decoded.getStructure().spawnOverrides().containsKey(MobCategory.MONSTER),"loaded fortress monster override");
            require(nativeTerrain.queryStructureStarts(request,new int[]{at[0],at[1],0})[0]==index,"locate candidate shares native start");
            long chests=expected.stream().filter(p->p.createTag(null).getBooleanOr("Chest",false)).count();
            if(chests>mostChests && at[0]==0 && expected.stream().anyMatch(p->p instanceof NetherFortressPieces.MonsterThrone)) {selected=expected;selectedSeed=seed;mostChests=chests;}
            layouts++;pieces+=expected.size();
        }
        require(kinds.size()==14,"all fortress families reached: "+kinds); require(selected!=null,"progression layout selected");
        int chunks=placement(registry,context,nativeTerrain,profile,baseProfile,selectedSeed,selected,materials);
        var report=new JsonObject();report.addProperty("layouts",layouts);report.addProperty("pieces",pieces);report.addProperty("families",kinds.size());report.addProperty("placement_chunks",chunks);report.addProperty("selected_seed",selectedSeed);
        Files.writeString(Path.of("build/nether/fortress-assembly.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("QA_EVT {\"event\":\"native_fortress_assembly\",\"status\":\"pass\",\"context\":"+report+"}");
    }
    static List<StructurePiece> reference(long seed,int x,int z) {
        var random=RandomSource.create(mix(seed^(long)x*341873128712L^(long)z*132897987541L));random.nextInt(1);
        var builder=new StructurePiecesBuilder();var start=new NetherFortressPieces.StartPiece(random,x*16+2,z*16+2);
        builder.addPiece(start);start.addChildren(start,builder,random);
        while(!start.pendingChildren.isEmpty())start.pendingChildren.remove(random.nextInt(start.pendingChildren.size())).addChildren(start,builder,random);
        builder.moveInsideHeights(random,48,70);return builder.build().pieces();
    }
    private static int placement(RegistryAccess registry,StructurePieceSerializationContext context,NativeTerrain terrain,int profile,int base,long seed,List<StructurePiece> source,BlockState[] materials) throws Exception {
        var baseline=new HashMap<ChunkPos,short[]>();
        class Reference extends FortressProfile.Capture {
            Reference(){super(registry);random=RandomSource.create(1945);}
            @Override BlockState state(BlockPos pos) {
                var current=blocks.get(pos);if(current!=null)return current;
                if(pos.getY()<0 || pos.getY()>=256)return Blocks.VOID_AIR.defaultBlockState();
                var at=ChunkPos.containing(pos);var values=baseline.computeIfAbsent(at,p->{try(var chunk=terrain.generate(request(base,seed,p.x(),p.z()))){return chunk.blocks().toArray(ValueLayout.JAVA_SHORT);}});
                return materials[Short.toUnsignedInt(values[pos.getY()*256+(pos.getZ()&15)*16+(pos.getX()&15)])];
            }
        }
        var reference=new Reference();
        // Match the requested Nether storage minimum for actual source support termination.
        var level=(WorldGenLevel)java.lang.reflect.Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->{
            if(method.getName().equals("getMinY"))return 0;
            if(method.getName().equals("getMaxY"))return 255;
            try{return method.invoke(reference.level,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        for(var piece:source)piece.postProcess(level,null,null,reference.random,BoundingBox.infinite(),new ChunkPos(0,0),BlockPos.ZERO);
        var bounds=StructurePiece.createBoundingBox(source.stream());
        var generated=new LinkedHashMap<ChunkPos,short[]>();var metadata=new LinkedHashMap<ChunkPos,CompoundTag>();int spawners=0,chests=0,ticks=0,supports=0;
        var lootSeeds=new HashSet<Long>();
        var materialIds=new HashMap<BlockState,Integer>();for(int i=0;i<materials.length;i++)materialIds.put(materials[i],i);
        for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++)for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++) {
            var at=new ChunkPos(x,z);var r=request(profile,seed,x,z);
            var expected=baseline.computeIfAbsent(at,p->{try(var chunk=terrain.generate(request(base,seed,p.x(),p.z()))){return chunk.blocks().toArray(ValueLayout.JAVA_SHORT);}}).clone();
            for(var e:reference.blocks.entrySet())if(ChunkPos.containing(e.getKey()).equals(at)) {
                var p=e.getKey();expected[p.getY()*256+(p.getZ()&15)*16+(p.getX()&15)]=materialIds.get(e.getValue()).shortValue();
                if(p.getY()<bounds.minY())supports++;
            }
            try(var chunk=terrain.generate(r)) {
                var actual=chunk.blocks().toArray(ValueLayout.JAVA_SHORT);
                int mismatch=Arrays.mismatch(expected,actual);
                require(mismatch<0,"source fortress voxel parity at "+at+" index="+mismatch+(mismatch<0?"":" expected="+materials[Short.toUnsignedInt(expected[mismatch])]+" actual="+materials[Short.toUnsignedInt(actual[mismatch])]));
                generated.put(at,actual);
            }
            var data=terrain.structureData(r);metadata.put(at,data);
            require(data.getCompoundOrEmpty("structures").getCompoundOrEmpty("References").contains("minecraft:fortress"),"whole fortress box has references including spaces between pieces");
            var expectedEntities=new HashMap<BlockPos,CompoundTag>();
            for(var e:reference.entities.entrySet())if(ChunkPos.containing(e.getKey()).equals(at))expectedEntities.put(e.getKey(),e.getValue().saveWithFullMetadata(registry));
            require(data.getListOrEmpty("block_entities").size()==expectedEntities.size(),"source block entity count");
            for(var value:data.getListOrEmpty("block_entities")) {
                var entity=((CompoundTag)value).copy();var pos=new BlockPos(entity.getIntOr("x",0),entity.getIntOr("y",0),entity.getIntOr("z",0));
                var expectedEntity=expectedEntities.get(pos);require(expectedEntity!=null,"source entity position");
                if(entity.contains("LootTable")){chests++;lootSeeds.add(entity.getLongOr("LootTableSeed",0));}
                if(entity.getStringOr("id","").equals("minecraft:mob_spawner")){require(entity.toString().contains("minecraft:blaze"),"blaze spawner");spawners++;}
                entity.remove("keepPacked");entity.remove("LootTableSeed");expectedEntity.remove("LootTableSeed");require(entity.equals(expectedEntity),"source block entity data: "+pos);
            }
            var expectedTicks=new ListTag();
            for(var tick:reference.ticks)if(ChunkPos.containing(tick.pos()).equals(at)) {
                var tag=new CompoundTag();tag.putString("i",BuiltInRegistries.FLUID.getKey(tick.fluid()).toString());tag.putInt("x",tick.pos().getX());tag.putInt("y",tick.pos().getY());tag.putInt("z",tick.pos().getZ());tag.putInt("t",0);tag.putInt("p",tick.priority());expectedTicks.add(tag);
            }
            require(data.getListOrEmpty("fluid_ticks").equals(expectedTicks),"source lava well updates");ticks+=expectedTicks.size();
            var expectedProcessing=new ListTag();boolean any=false;
            for(int section=0;section<16;section++) {
                var values=new ListTag();
                for(var p:reference.postProcessing)if(ChunkPos.containing(p).equals(at)&&p.getY()/16==section)values.add(ShortTag.valueOf((short)((p.getX()&15)|((p.getY()&15)<<4)|((p.getZ()&15)<<8))));
                any|=!values.isEmpty();expectedProcessing.add(values);
            }
            require(data.getListOrEmpty("PostProcessing").equals(any?expectedProcessing:new ListTag()),"source shape postprocessing");
            var proto=proto(registry,at);GenerationTicks.install(proto,data);
            require(proto.getFluidTicks().count()==expectedTicks.size(),"prototype chunk installs lava ticks");
            int marked=Arrays.stream(proto.getPostProcessing()).filter(Objects::nonNull).mapToInt(List::size).sum();
            require(marked==reference.postProcessing.stream().filter(p->ChunkPos.containing(p).equals(at)).count(),"prototype chunk installs shape updates");
        }
        require(chests>1&&lootSeeds.size()==chests,"distinct deterministic loot seeds for fortress chests");
        require(spawners>0&&chests>0&&ticks>0&&supports>0,"progression and terrain-aware supports: "+spawners+"/"+chests+"/"+ticks+"/"+supports);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();for(var e:generated.entrySet())jobs.add(pool.submit(()->{
                var p=e.getKey();try(var chunk=terrain.generate(request(profile,seed,p.x(),p.z()))){require(Arrays.equals(e.getValue(),chunk.blocks().toArray(ValueLayout.JAVA_SHORT)),"parallel fortress determinism");}
                require(metadata.get(p).equals(terrain.structureData(request(profile,seed,p.x(),p.z()))),"parallel fortress metadata");
            }));for(var job:jobs)job.get();
        }
        var directory=Files.createTempDirectory(Path.of("build/nether"),"fortress-regions-");
        Files.writeString(Path.of("build/nether/fortress-regions-path.txt"),directory.toAbsolutePath().toString());
        var regions=new HashSet<ChunkPos>();
        for(var p:generated.keySet())regions.add(new ChunkPos(p.getRegionX()*32,p.getRegionZ()*32));
        for(var p:regions)terrain.generateRegion(request(profile,seed,p.x(),p.z()),directory.resolve("r."+p.getRegionX()+"."+p.getRegionZ()+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:nether_wastes");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-fortress",Level.NETHER,"chunk"),directory,false)) {
            for(var e:generated.entrySet()) {
                var tag=storage.read(e.getKey());require(tag!=null,"fortress MCA chunk");
                for(String key:List.of("structures","block_entities","entities","fluid_ticks","block_ticks"))require(Objects.equals(tag.get(key),metadata.get(e.getKey()).get(key)),"MCA metadata "+key);
                require(tag.getListOrEmpty("PostProcessing").equals(metadata.get(e.getKey()).getListOrEmpty("PostProcessing")),"MCA shape updates");
                for(var section:McaTestSections.terrain(tag,0,256)) {
                    int y=section.getIntOr("Y",0)*16;var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for(int yy=0;yy<16;yy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)require(blocks.get(x,yy,z).equals(materials[Short.toUnsignedInt(e.getValue()[(y+yy)*256+z*16+x])]),"MCA fortress voxel parity at "+e.getKey()+" local="+x+","+(y+yy)+","+z);
                }
            }
            var saved=storage.read(new ChunkPos(0,0)).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:fortress");
            var reopened=StructureStart.loadStaticStart(context,saved,seed);require(reopened!=null&&reopened.isValid()&&reopened.getPieces().size()==source.size(),"fortress persisted start reopens");
        }
        System.out.println("QA_EVT {\"event\":\"fortress_terrain_progression_mca\",\"status\":\"pass\",\"context\":{\"chunks\":"+generated.size()+",\"regions\":"+regions.size()+",\"spawners\":"+spawners+",\"chests\":"+chests+",\"lava_ticks\":"+ticks+",\"support_blocks\":"+supports+"}}");
        return generated.size();
    }
    private static ProtoChunk proto(RegistryAccess registry,ChunkPos at) {
        var biomeIds=new IdMapper<Holder<Biome>>();registry.lookupOrThrow(Registries.BIOME).listElements().forEach(biomeIds::add);
        var bs=Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);var bio=Strategy.createForBiomes(biomeIds);var air=Blocks.AIR.defaultBlockState();var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
        var factory=new PalettedContainerFactory(bs,air,PalettedContainer.codecRW(BlockState.CODEC,bs,air),bio,biome,PalettedContainer.codecRO(Biome.CODEC,bio,biome));
        return new ProtoChunk(at,UpgradeData.EMPTY,LevelHeightAccessor.create(0,256),factory,null);
    }
    private static String kind(StructurePiece piece) {
        if(piece instanceof NetherFortressPieces.StartPiece)return "bridge_crossing";
        if(piece instanceof NetherFortressPieces.BridgeEndFiller)return "end_filler";
        for(String kind:FortressProfile.KINDS)if(FortressProfile.piece(kind,Direction.SOUTH,false,0).getClass()==piece.getClass())return kind;
        throw new AssertionError(piece.getClass());
    }
    static void registerPiece() throws Exception {
        var frozen=MappedRegistry.class.getDeclaredField("frozen");frozen.setAccessible(true);frozen.setBoolean(BuiltInRegistries.STRUCTURE_PIECE,false);
        RetinaStructurePiece.register();var bind=Holder.Reference.class.getDeclaredMethod("bindValue",Object.class);bind.setAccessible(true);bind.invoke(BuiltInRegistries.STRUCTURE_PIECE.get(Identifier.parse("retina:template")).orElseThrow(),RetinaStructurePiece.TYPE);frozen.setBoolean(BuiltInRegistries.STRUCTURE_PIECE,true);
    }
    private static TerrainRequest request(int profile,long seed,int x,int z){return new TerrainRequest(seed,x,z,0,256,64,48,.008f,profile);}
    private static long mix(long v){v=(v^v>>>30)*0xbf58476d1ce4e5b9L;v=(v^v>>>27)*0x94d049bb133111ebL;return v^v>>>31;}
    private static void require(boolean v,String message){if(!v)throw new AssertionError(message);}
}
