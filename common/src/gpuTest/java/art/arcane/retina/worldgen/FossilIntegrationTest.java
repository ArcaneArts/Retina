package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual loaded fossil pieces replayed against the same native base terrain. */
final class FossilIntegrationTest {
    record Expected(BlockPos pos, Rotation rotation, int template) {}
    static void run(RegistryAccess registry, ResourceManager resources, JsonObject source, BlockState[] materials) throws Exception {
        var fixture = fixture(source); var terrain = NativeTerrain.instance();
        var kit = fixture.getAsJsonObject("structures").getAsJsonObject("fossils");
        require(kit.getAsJsonArray("templates").size()==14,"all loaded fossil templates");
        for(int i=0;i<materials.length;i++) {
            var state=materials[i];
            require(kit.getAsJsonArray("floor").contains(new JsonPrimitive(i)) == (state.is(Blocks.SOUL_SAND)||state.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP)),"actual fossil floor predicate");
            require(kit.getAsJsonArray("air").contains(new JsonPrimitive(i))==state.isAir(),"actual dried-ghast air predicate");
        }
        var definition=fixture.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject();
        var loadedHeight=definition.getAsJsonObject("config").getAsJsonObject("height");
        require(loadedHeight.getAsJsonObject("max_inclusive").get("absolute").getAsInt()==125,"fossil below_top resolves against 128-block generation context");
        var generator=new NoiseBasedChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.SOUL_SAND_VALLEY)),registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER));
        var heights=new WorldGenerationContext(generator,LevelHeightAccessor.create(0,256));
        var baseline=fixture.deepCopy(); baseline.add("structures",JsonParser.parseString("{\"templates\":[],\"definitions\":[],\"pools\":{},\"sets\":[]}"));
        int base=terrain.registerProfile(baseline.toString());
        int checked=0, rejected=0, placed=0, ghasts=0, crossChunk=0, comparedChunks=0; var rotations=new HashSet<Rotation>(); var chosen=new EnumMap<Rotation,Long>(Rotation.class);
        var work=Files.createTempDirectory(Path.of("build/nether"),"fossil-reference-");
        try(var storage=LevelStorageSource.createDefault(work).createAccess("templates")) {
            var manager=new StructureTemplateManager(resources,storage,DataFixers.getDataFixer(),registry.lookupOrThrow(Registries.BLOCK));
            // Preserve source distributions and random consumption, including degenerate and nested providers.
            var providers=List.of(loadedHeight.toString(),"{\"absolute\":126}","{\"absolute\":31}",
                    "{\"type\":\"minecraft:uniform\",\"min_inclusive\":{\"absolute\":126},\"max_inclusive\":{\"absolute\":126}}",
                    "{\"type\":\"minecraft:biased_to_bottom\",\"min_inclusive\":{\"absolute\":40},\"max_inclusive\":{\"absolute\":126},\"inner\":2}",
                    "{\"type\":\"minecraft:very_biased_to_bottom\",\"min_inclusive\":{\"absolute\":40},\"max_inclusive\":{\"absolute\":126},\"inner\":3}",
                    "{\"type\":\"minecraft:trapezoid\",\"min_inclusive\":{\"absolute\":40},\"max_inclusive\":{\"absolute\":126},\"plateau\":12}",
                    "{\"type\":\"minecraft:trapezoid\",\"min_inclusive\":{\"absolute\":126},\"max_inclusive\":{\"absolute\":126},\"plateau\":0}",
                    "{\"type\":\"minecraft:weighted_list\",\"distribution\":[{\"data\":{\"absolute\":126},\"weight\":2},{\"data\":{\"absolute\":90},\"weight\":1}]}");
            for(var provider:providers) {
                var variant=fixture.deepCopy();allowAll(variant);variant.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").add("height",JsonParser.parseString(provider));
                int profile=terrain.registerProfile(variant.toString());
                var height=HeightProvider.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(provider)).getOrThrow();
                for(long seed=0;seed<64;seed++)for(int[] at:new int[][]{{0,0},{-512,512}}) {
                    var r=request(profile,seed,at[0],at[1]);var expected=reference(terrain,r,materials,height,heights,variant);
                    var tag=terrain.structureStarts(r).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:nether_fossil");
                    require((expected!=null)==!tag.isEmpty(),"fossil floor/biome acceptance seed="+seed+" provider="+provider);
                    require(terrain.queryStructureStarts(r,new int[]{at[0],at[1],0})[0]==(expected==null?-1:0),"fossil locate shares accepted start");
                    if(expected==null){rejected++;continue;}
                    var piece=new NetherFossilPieces.NetherFossilPiece(manager,Identifier.parse("minecraft:nether_fossils/fossil_"+(expected.template+1)),expected.pos,expected.rotation);
                    var child=tag.getListOrEmpty("Children").getCompound(0).orElseThrow();
                    require(child.getStringOr("template","").endsWith("fossil_"+(expected.template+1)),"source template selection after height draws");
                    require(child.read("BB",BoundingBox.CODEC).orElseThrow().equals(piece.getBoundingBox()),"source floor, rotation and bounds");
                    checked++;
                    if(provider.equals("{\"absolute\":126}")&&at[0]==0)chosen.putIfAbsent(expected.rotation,seed);
                }
            }
            require(rejected>0 && checked>0 && chosen.size()==4,"floor rejection and all rotations exercised: accepted="+checked+" rejected="+rejected+" chosen="+chosen);
            // Force each effective template in turn; draw rotation normally, then run Minecraft's real postProcess.
            for(int template=0;template<14;template++) {
                var variant=fixture.deepCopy();allowAll(variant); var v=variant.getAsJsonObject("structures");
                v.getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").add("height",JsonParser.parseString("{\"absolute\":126}"));
                var ids=new JsonArray();for(int i=0;i<14;i++)ids.add(template);v.getAsJsonObject("fossils").add("templates",ids);
                int profile=terrain.registerProfile(variant.toString());
                for(var e:chosen.entrySet()) {
                    long seed=e.getValue();var r=request(profile,seed,0,0);
                    var expected=reference(terrain,r,materials,HeightProvider.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("{\"absolute\":126}")).getOrThrow(),heights,variant);
                    require(expected!=null,"selected floor remains valid"); rotations.add(expected.rotation);
                    var piece=new NetherFossilPieces.NetherFossilPiece(manager,Identifier.parse("minecraft:nether_fossils/fossil_"+(template+1)),expected.pos,expected.rotation);
                    var box=piece.getBoundingBox();
                    int chunks=(Math.floorDiv(box.maxX(),16)-Math.floorDiv(box.minX(),16)+1)*(Math.floorDiv(box.maxZ(),16)-Math.floorDiv(box.minZ(),16)+1);
                    comparedChunks+=chunks;if(chunks>1)crossChunk++;
                    int count=placement(registry,resources,manager,terrain,profile,base,seed,new ChunkPos(0,0),piece,materials,false);
                    ghasts+=count;placed++;
                }
            }
            boundaryFixture(registry,resources,manager,terrain,fixture,base,heights,materials);
            // Actual source biome restriction, rather than accepting the whole chunk's surface biome.
            int restricted=terrain.registerProfile(fixture.toString());
            for(long seed=0;seed<24;seed++) {
                var r=request(restricted,seed,0,0);var expected=reference(terrain,r,materials,HeightProvider.CODEC.parse(JsonOps.INSTANCE,loadedHeight).getOrThrow(),heights,fixture);
                require(terrain.queryStructureStarts(r,new int[]{0,0,0})[0]==(expected==null?-1:0),"loaded fossil biome at exact floor");
            }
        }
        require(placed==56&&rotations.size()==4&&ghasts>0,"all 14 templates, four rotations and actual dried ghasts; crossChunk="+crossChunk+" chunks="+comparedChunks);
        var report=new JsonObject();report.addProperty("accepted_provider_checks",checked);report.addProperty("rejected_provider_checks",rejected);report.addProperty("template_rotations",placed);report.addProperty("dried_ghasts",ghasts);report.addProperty("cross_chunk_cases",crossChunk);report.addProperty("compared_chunks",comparedChunks);
        Files.writeString(Path.of("build/nether/fossil-validation.json"),report.toString());
        System.out.println("QA_EVT {\"event\":\"native_nether_fossils\",\"status\":\"pass\",\"context\":"+report+"}");
    }
    static void allowAll(JsonObject fixture) {
        var allowed=new JsonArray();for(int i=0;i<fixture.getAsJsonArray("biomes").size();i++)allowed.add(i);
        fixture.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().add("biomes",allowed);
    }
    static JsonObject fixture(JsonObject source) {
        var out=source.deepCopy();out.add("decorations",new JsonArray());out.add("terrain_features",new JsonObject());
        for(var value:out.getAsJsonArray("biomes")){var b=value.getAsJsonObject();b.add("decorations",new JsonArray());b.add("cave_features",new JsonObject());}
        var s=out.getAsJsonObject("structures");var definition=s.getAsJsonArray("definitions").asList().stream().map(JsonElement::getAsJsonObject).filter(d->d.get("kind").getAsString().equals("nether_fossil")).findFirst().orElseThrow().deepCopy();
        var definitions=new JsonArray();definitions.add(definition);s.add("definitions",definitions);
        var templates=new JsonArray();var ids=new JsonArray();for(var id:s.getAsJsonObject("fossils").getAsJsonArray("templates")){ids.add(templates.size());templates.add(s.getAsJsonArray("templates").get(id.getAsInt()));}
        s.add("templates",templates);s.getAsJsonObject("fossils").add("templates",ids);s.add("pools",new JsonObject());s.remove("fortress");
        s.add("sets",JsonParser.parseString("[{\"id\":\"retina:fossil_fixture\",\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":512,\"separation\":511,\"salt\":0},\"entries\":[{\"definition\":0,\"weight\":1}]}]"));
        return out;
    }
    static Expected reference(NativeTerrain terrain,TerrainRequest request,BlockState[] materials,HeightProvider height,WorldGenerationContext context,JsonObject fixture) {
        var random=RandomSource.create(mix(request.seed()^(long)request.chunkX()*341873128712L^(long)request.chunkZ()*132897987541L));random.nextInt(1);
        int x=random.nextInt(16),z=random.nextInt(16),y=height.sample(random,context);
        var column=terrain.column(request,z*16+x);int sea=fixture.get("sea_level").getAsInt();
        while(y>sea) {var current=at(column,materials,y);var below=at(column,materials,--y);if(current.isAir()&&(below.is(Blocks.SOUL_SAND)||below.isFaceSturdy(EmptyBlockGetter.INSTANCE,new BlockPos(x,y,z),Direction.UP)))break;}
        if(y<=sea)return null;
        var pos=new BlockPos(request.chunkX()*16+x,y,request.chunkZ()*16+z);
        int biome=terrain.queryPoints(request,new int[]{pos.getX(),y,pos.getZ()},false)[0];
        var allowed=fixture.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonArray("biomes");
        if(!allowed.contains(new JsonPrimitive(biome)))return null;
        return new Expected(pos,Rotation.getRandom(random),random.nextInt(14));
    }
    static BlockState at(short[] column,BlockState[] materials,int y){return y<0||y>=column.length?Blocks.AIR.defaultBlockState():materials[Short.toUnsignedInt(column[y])];}
    static void boundaryFixture(RegistryAccess registry,ResourceManager resources,StructureTemplateManager manager,NativeTerrain terrain,JsonObject source,int base,WorldGenerationContext heights,BlockState[] materials) throws Exception {
        var fixture=source.deepCopy();allowAll(fixture);var structures=fixture.getAsJsonObject("structures");
        structures.getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").add("height",JsonParser.parseString("{\"absolute\":126}"));
        var ids=new JsonArray();for(int i=0;i<14;i++)ids.add(13);structures.getAsJsonObject("fossils").add("templates",ids);
        // One candidate per 512-chunk grid, with starts allowed near either edge of an MCA region.
        structures.getAsJsonArray("sets").get(0).getAsJsonObject().getAsJsonObject("placement").addProperty("separation",480);
        int profile=terrain.registerProfile(fixture.toString());
        var height=HeightProvider.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("{\"absolute\":126}")).getOrThrow();
        for(int i=0;i<256;i++) {
            long seed=mix(i);var random=RandomSource.create(seed);var at=new ChunkPos(random.nextInt(32),random.nextInt(32));
            var expected=reference(terrain,request(profile,seed,at.x(),at.z()),materials,height,heights,fixture);
            if(expected==null)continue;
            var piece=new NetherFossilPieces.NetherFossilPiece(manager,Identifier.parse("minecraft:nether_fossils/fossil_14"),expected.pos,expected.rotation);
            var b=piece.getBoundingBox();
            if(Math.floorDiv(b.minX(),512)==Math.floorDiv(b.maxX(),512)&&Math.floorDiv(b.minZ(),512)==Math.floorDiv(b.maxZ(),512))continue;
            Files.writeString(Path.of("build/nether/fossil-fixture-profile.json"),fixture.toString());
            placement(registry,resources,manager,terrain,profile,base,seed,at,piece,materials,true);return;
        }
        throw new AssertionError("no cross-region fossil fixture found");
    }
    static int placement(RegistryAccess registry,ResourceManager resources,StructureTemplateManager manager,NativeTerrain terrain,int profile,int base,long seed,ChunkPos start,StructurePiece piece,BlockState[] materials,boolean mca) throws Exception {
        var baseline=new HashMap<ChunkPos,short[]>();
        class Reference extends FortressProfile.Capture {
            Reference(){super(registry);random=RandomSource.create(1945);}
            @Override BlockState state(BlockPos p){if(blocks.containsKey(p))return blocks.get(p);if(p.getY()<0||p.getY()>=256)return Blocks.AIR.defaultBlockState();var a=ChunkPos.containing(p);var data=baseline.computeIfAbsent(a,k->blocks(terrain,request(base,seed,k.x(),k.z())));return materials[Short.toUnsignedInt(data[p.getY()*256+(p.getZ()&15)*16+(p.getX()&15)])];}
        }
        var reference=new Reference();var level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->{
            if(method.getName().equals("getSeed"))return seed;
            try{return method.invoke(reference.level,args);}catch(InvocationTargetException error){throw error.getCause();}
        });
        piece.postProcess(level,null,null,reference.random,BoundingBox.infinite(),new ChunkPos(0,0),BlockPos.ZERO);
        var ids=new HashMap<BlockState,Integer>();for(int i=0;i<materials.length;i++)ids.put(materials[i],i);
        var box=piece.getBoundingBox();var generated=new LinkedHashMap<ChunkPos,short[]>();
        for(int z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZ(),16);z++)for(int x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxX(),16);x++) {
            var pos=new ChunkPos(x,z);var r=request(profile,seed,x,z);var expected=baseline.computeIfAbsent(pos,p->blocks(terrain,request(base,seed,p.x(),p.z()))).clone();
            reference.blocks.forEach((p,state)->{if(ChunkPos.containing(p).equals(pos))expected[p.getY()*256+(p.getZ()&15)*16+(p.getX()&15)]=ids.get(state).shortValue();});
            var actual=blocks(terrain,r);int mismatch=Arrays.mismatch(expected,actual);require(mismatch<0,"actual fossil postProcess voxel parity chunk="+pos+" index="+mismatch+(mismatch<0?"":" expected="+materials[Short.toUnsignedInt(expected[mismatch])]+" actual="+materials[Short.toUnsignedInt(actual[mismatch])])) ;generated.put(pos,actual);
        }
        var context=new StructurePieceSerializationContext(resources,registry,manager);
        var data=terrain.structureStarts(request(profile,seed,start.x(),start.z()));var tag=data.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:nether_fossil");
        var saved=StructureStart.loadStaticStart(context,tag,seed);require(saved!=null&&saved.isValid()&&saved.getBoundingBox().equals(box.inflatedBy(12)),"persisted source identity and adjusted fossil bounds");
        var adjusted=saved.getBoundingBox();
        for(int z=Math.floorDiv(adjusted.minZ(),16);z<=Math.floorDiv(adjusted.maxZ(),16);z++)for(int x=Math.floorDiv(adjusted.minX(),16);x<=Math.floorDiv(adjusted.maxX(),16);x++)
            require(terrain.structureStarts(request(profile,seed,x,z)).getCompoundOrEmpty("structures").getCompoundOrEmpty("References").contains("minecraft:nether_fossil"),"references cover source-adjusted bounds");
        if(mca) {
            try(var workers=Executors.newFixedThreadPool(4)){var jobs=new ArrayList<Future<?>>();for(var e:generated.entrySet())jobs.add(workers.submit(()->require(Arrays.equals(e.getValue(),blocks(terrain,request(profile,seed,e.getKey().x(),e.getKey().z()))),"concurrent fossil determinism")));for(var job:jobs)job.get();}
            var directory=Files.createTempDirectory(Path.of("build/nether"),"fossil-regions-");Files.writeString(Path.of("build/nether/fossil-regions-path.txt"),directory.toAbsolutePath().toString());
            var regions=new HashSet<ChunkPos>();generated.keySet().forEach(p->regions.add(new ChunkPos(p.getRegionX()*32,p.getRegionZ()*32)));
            for(var p:regions)terrain.generateRegion(request(profile,seed,p.x(),p.z()),directory.resolve("r."+p.getRegionX()+"."+p.getRegionZ()+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:nether_wastes");
            var report=new JsonObject();report.addProperty("seed",seed);report.addProperty("start_chunk_x",start.x());report.addProperty("start_chunk_z",start.z());report.addProperty("chunks",generated.size());report.addProperty("regions",regions.size());report.addProperty("compared_voxels",generated.size()*65536);
            Files.writeString(Path.of("build/nether/fossil-mca.json"),report.toString());
            var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-fossils",Level.NETHER,"chunk"),directory,false)) {
                for(var e:generated.entrySet()) {
                    var chunk=storage.read(e.getKey());require(chunk!=null,"saved fossil chunk");
                    require(chunk.getCompoundOrEmpty("structures").equals(terrain.structureStarts(request(profile,seed,e.getKey().x(),e.getKey().z())).getCompoundOrEmpty("structures")),"saved fossil starts/references");
                    if(e.getKey().equals(start)) {
                        var reopened=StructureStart.loadStaticStart(context,chunk.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:nether_fossil"),seed);
                        require(reopened!=null&&reopened.isValid()&&reopened.getBoundingBox().equals(saved.getBoundingBox()),"MCA fossil start reopens with adjusted bounds");
                    }
                    for(var section:McaTestSections.terrain(chunk,0,256)){int y=section.getIntOr("Y",0)*16;var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();for(int yy=0;yy<16;yy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)require(blocks.get(x,yy,z).equals(materials[Short.toUnsignedInt(e.getValue()[(y+yy)*256+z*16+x])]),"fossil MCA voxel parity");}
                }
            }
        }
        return (int)reference.blocks.values().stream().filter(s->s.is(Blocks.DRIED_GHAST)).count();
    }
    static short[] blocks(NativeTerrain terrain,TerrainRequest r){try(var chunk=terrain.generate(r)){return chunk.blocks().toArray(ValueLayout.JAVA_SHORT);}}
    static TerrainRequest request(int profile,long seed,int x,int z){return new TerrainRequest(seed,x,z,0,256,64,48,.008f,profile);}
    static long mix(long v){v=(v^v>>>30)*0xbf58476d1ce4e5b9L;v=(v^v>>>27)*0x94d049bb133111ebL;return v^v>>>31;}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
