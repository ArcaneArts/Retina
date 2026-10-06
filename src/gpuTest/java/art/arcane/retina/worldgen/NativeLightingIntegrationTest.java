package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.lighting.*;
import java.nio.file.*;
import java.util.*;

/** Actual Minecraft light propagation vs the production WGSL, including mixed saved/unlit seams. */
public final class NativeLightingIntegrationTest {
    private static final int SIDE=3, HEIGHT=64, COUNT=HEIGHT*256;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try(var pack=ServerPacksSource.createVanillaPackSource().fullResources()) {
            var resources=new FallbackResourceManager(PackType.SERVER_DATA,"minecraft");resources.push(pack);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
            var registry=RegistryIntegrationFixtures.load(resources);
            var plains=(Holder<Biome>)registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            var base=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(List.of(plains),512,.55F),-64,384);
            var data=JsonParser.parseString(base.json()).getAsJsonObject();
            for(String key:List.of("decorations","ores","cave_noises"))data.add(key,new JsonArray());
            data.remove("registry_program");
            for(var value:data.getAsJsonArray("biomes")) {
                var biome=value.getAsJsonObject();biome.remove("cave_features");
                for(String key:List.of("decorations","ores","carvers"))biome.add(key,new JsonArray());
            }
            var palette=new ArrayList<>(List.of(base.materials()));
            var examples=new ArrayList<BlockState>();
            for(var block:List.of(Blocks.TORCH,Blocks.GLOWSTONE,Blocks.LAVA,Blocks.WATER,Blocks.OAK_LEAVES,
                    Blocks.OAK_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_TRAPDOOR,Blocks.SNOW,Blocks.GLASS,Blocks.SEA_LANTERN))
                examples.addAll(block.getStateDefinition().getPossibleStates());
            for(var state:examples) if(!palette.contains(state)) {
                palette.add(state);data.getAsJsonArray("materials").add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());
                data.getAsJsonArray("material_flags").add(0);data.getAsJsonArray("heightmap_masks").add(0);data.getAsJsonArray("carveable").add(false);
            }
            for(String field:List.of("snow_support","plant_halves","plant_floor_masks","simple_current_states"))data.remove(field);
            int compared=0;
            var factory=PalettedContainerFactory.create(registry);
            for(boolean sky:List.of(true,false)) {
                data.add("lighting",LightingProfile.export(palette,sky));
                int profile=NativeTerrain.instance().registerProfile(data.toString());
                for(int variant=0;variant<3;variant++) {
                    var fixture=new Fixture(factory,palette,sky,variant,examples);
                    var request=new TerrainRequest(123456789L,0,0,0,HEIGHT,24,0,.0035F,profile);
                    byte[] light=NativeTerrain.instance().lightVolume(request,SIDE,fixture.materials);
                    var expected=fixture.engine(null);var imported=fixture.engine(light);
                    for(int z=0;z<48;z++)for(int x=0;x<48;x++)for(int y=0;y<HEIGHT;y++) {
                        int index=((z/16)*SIDE+x/16)*(HEIGHT+32)*256+(y+16)*256+(z%16)*16+x%16;
                        int value=Byte.toUnsignedInt(light[index]);var pos=new BlockPos(x,y,z);
                        for(var layer:LightLayer.values()) {
                            int reference=expected.getLayerListener(layer).getLightValue(pos);
                            int actual=layer==LightLayer.SKY?value>>4:value&15;
                            require(actual==reference,"GPU "+layer+" differs at "+pos+" sky="+sky+" variant="+variant+": "+actual+" != "+reference);
                            require(imported.getLayerListener(layer).getLightValue(pos)==reference,"mixed prelit/unlit seam differs at "+pos);
                            compared++;
                        }
                    }
                    // Dynamic block light remains handled by Minecraft after importing GPU arrays.
                    var torch=new BlockPos(24,45,24);fixture.put(torch,Blocks.TORCH.defaultBlockState());
                    imported.checkBlock(torch);imported.runLightUpdates();
                    require(imported.getLayerListener(LightLayer.BLOCK).getLightValue(torch)==14,"placed torch updates prelit chunk");
                    fixture.put(torch,Blocks.AIR.defaultBlockState());imported.checkBlock(torch);imported.runLightUpdates();
                    require(imported.getLayerListener(LightLayer.BLOCK).getLightValue(torch)==0,"removed torch clears prelit chunk");
                    if(sky) {
                        var roof=new BlockPos(24,HEIGHT-1,24);fixture.put(roof,Blocks.STONE.defaultBlockState());
                        imported.checkBlock(roof);imported.runLightUpdates();
                        require(imported.getLayerListener(LightLayer.SKY).getLightValue(roof.below())==14,"placed roof updates imported skylight");
                    }
                }
            }
            System.out.println("QA_EVT {\"event\":\"gpu_lighting_minecraft_reference\",\"status\":\"pass\",\"context\":{\"comparisons\":"+compared+"}}");
            region(base,registry,factory);
            GenerationMetricsTest.run();
            if(args.length==1) savedBenchmark(Path.of(args[0]),factory);
        }
    }
    private static final class Fixture implements LightChunkGetter {
        final ProtoChunk[] chunks=new ProtoChunk[9];final short[] materials=new short[9*COUNT];
        final LevelHeightAccessor bounds=LevelHeightAccessor.create(0,HEIGHT); final boolean sky;
        Fixture(PalettedContainerFactory factory,List<BlockState> palette,boolean sky,int variant,List<BlockState> examples) {
            this.sky=sky;
            for(int i=0;i<9;i++)chunks[i]=new ProtoChunk(new ChunkPos(i%3,i/3),UpgradeData.EMPTY,bounds,factory,null);
            var random=new Random(137+variant);
            var ids=new HashMap<BlockState,Integer>();for(int i=0;i<palette.size();i++)ids.put(palette.get(i),i);
            for(int z=0;z<48;z++)for(int x=0;x<48;x++)for(int y=0;y<HEIGHT;y++) {
                BlockState state=Blocks.AIR.defaultBlockState();
                if(y<4 || y==32 && x>3 && x<44 && z>3 && z<44)state=Blocks.STONE.defaultBlockState();
                else if(y<23 && ((x+z*3)%19<3 || z%17<2))state=Blocks.STONE.defaultBlockState();
                else if(y>=8 && y<30 && random.nextInt(35)==0)state=examples.get(random.nextInt(examples.size()));
                else if(variant==1 && y>=35 && y<=39 && x>10 && x<38 && z>10 && z<38)state=Blocks.OAK_LEAVES.defaultBlockState();
                else if(variant==2 && y==HEIGHT-1 && x>=15 && x<=17 && z==16)state=Blocks.GLOWSTONE.defaultBlockState();
                else if(variant==2 && y>=4 && y<18 && x>15 && x<32 && z>15 && z<32)state=Blocks.WATER.defaultBlockState();
                put(new BlockPos(x,y,z),state);
                materials[(z/16*3+x/16)*COUNT+y*256+(z%16)*16+x%16]=(short)(int)ids.get(state);
            }
            for(var chunk:chunks)chunk.initializeLightSources();
        }
        void put(BlockPos pos,BlockState state){chunks[(pos.getZ()/16)*3+pos.getX()/16].setBlockState(pos,state,0);}
        public LightChunk getChunkForLighting(int x,int z){return x>=0&&z>=0&&x<3&&z<3?chunks[z*3+x]:null;}
        public BlockGetter getLevel(){return chunks[0];}
        LevelLightEngine engine(byte[] saved) {
            var engine=new LevelLightEngine(this,true,sky);
            for(int i=0;i<9;i++) {
                var pos=chunks[i].getPos();
                boolean prelit=saved!=null && i==4;
                for(int s=0;s<bounds.getSectionsCount();s++)if(!chunks[i].getSection(s).hasOnlyAir())engine.updateSectionStatus(SectionPos.of(pos,bounds.getSectionYFromSectionIndex(s)),false);
                if(prelit) {
                    engine.retainData(pos,true);
                    for(int section=-1;section<=HEIGHT/16;section++)for(var layer:LightLayer.values()) {
                        if(layer==LightLayer.SKY&&!sky)continue;
                        var bytes=new byte[2048];
                        int offset=i*(HEIGHT+32)*256+(section+1)*4096;
                        int shift=layer==LightLayer.SKY?4:0;
                        for(int b=0;b<2048;b++)bytes[b]=(byte)(((saved[offset+b*2]>>shift)&15)|(((saved[offset+b*2+1]>>shift)&15)<<4));
                        engine.queueSectionData(layer,SectionPos.of(pos,section),new DataLayer(bytes));
                    }
                }
                chunks[i].setPersistedStatus(prelit?ChunkStatus.LIGHT:ChunkStatus.FEATURES);chunks[i].setLightEngine(engine);
                engine.setLightEnabled(pos,prelit);
            }
            engine.runLightUpdates();
            for(int i=0;i<9;i++)if(saved==null||i!=4){
                engine.propagateLightSources(chunks[i].getPos());seed(engine,this,chunks[i].getPos());
            }
            engine.runLightUpdates();
            return engine;
        }
    }
    private static void region(BiomeTerrainProfile base,HolderLookup.Provider registry,PalettedContainerFactory factory) throws Exception {
        var data=JsonParser.parseString(base.json()).getAsJsonObject();
        for(String key:List.of("registry_program","structures","terrain_features"))data.remove(key);
        for(String key:List.of("decorations","ores","cave_noises"))data.add(key,new JsonArray());
        var biome=data.getAsJsonArray("biomes").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(b->b.get("id").getAsString().equals("minecraft:plains")).findFirst().orElseThrow().deepCopy();
        biome.add("terrain",JsonParser.parseString("[0,0,0]"));
        for(String key:List.of("decorations","ores","carvers"))biome.add(key,new JsonArray());
        biome.add("lakes",JsonParser.parseString("[0,0]"));biome.addProperty("cave_kind",0);biome.addProperty("flags",0);biome.addProperty("snow_surface",false);
        var biomes=new JsonArray();biomes.add(biome);data.add("biomes",biomes);data.addProperty("sea_level",16);data.add("climate_targets",new JsonArray());data.remove("climate_lookup");
        int profile=NativeTerrain.instance().registerProfile(data.toString());
        var request=new TerrainRequest(123456789L,-32,-32,0,HEIGHT,24,0,.0035F,profile);
        var directory=Files.createTempDirectory("retina-gpu-lighting-");
        try {
            var path=directory.resolve("r.-1.-1.mca");
            var report=NativeTerrain.instance().generateRegion(request,path,SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:plains");
            int lit=0,unlit=0;
            var savedChunks=new ArrayList<SerializableChunkData>();
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-light",Level.OVERWORLD,"chunk"),directory,false)) {
                for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
                    var pos=new ChunkPos(x-32,z-32);var tag=storage.read(pos);
                    var parsed=SerializableChunkData.parse(LevelHeightAccessor.create(0,HEIGHT),factory,tag);
                    savedChunks.add(parsed);
                    boolean interior=x>0&&x<31&&z>0&&z<31;
                    require(parsed.lightCorrect()==interior,"saved lighting flag matches interior");
                    require(parsed.chunkStatus()==(interior?ChunkStatus.LIGHT:ChunkStatus.FEATURES),"saved status controls actual Minecraft lighting bypass");
                    require(parsed.sectionData().size()==(interior?6:4),"lit sections include light-only padding");
                    if(interior)lit++;else unlit++;
                }
                var pos=new ChunkPos(-31,-31);var tag=storage.read(pos);tag.putString("retina_edit","preserved");storage.write(pos,tag);
                storage.write(new ChunkPos(-30,-30),null);
            }
            require(lit==900&&unlit==124,"exactly 87.9% of region lit");
            var grid=new SavedGrid(factory,savedChunks);
            var unlitEngine=grid.engine(false);var prelitEngine=grid.engine(true);
            var random=new Random(5581);
            for(int sample=0;sample<30000;sample++) {
                var pos=new BlockPos(-512+random.nextInt(512),random.nextInt(HEIGHT),-512+random.nextInt(512));
                for(var layer:LightLayer.values())require(unlitEngine.getLayerListener(layer).getLightValue(pos)
                        ==prelitEngine.getLayerListener(layer).getLightValue(pos),"actual MCA imported lighting / outer ring matches recomputation at "+pos);
            }
            double[] vanilla=new double[4],gpu=new double[4];
            for(int i=0;i<4;i++) {
                if(i%2==0) {vanilla[i]=grid.measure(false);gpu[i]=grid.measure(true);}
                else {gpu[i]=grid.measure(true);vanilla[i]=grid.measure(false);}
            }
            System.out.println("QA_EVT {\"event\":\"gpu_lighting_load_benchmark\",\"status\":\"pass\",\"context\":{\"vanilla_light_ms\":"+Arrays.stream(vanilla).average().orElseThrow()+",\"prelit_load_ms\":"+Arrays.stream(gpu).average().orElseThrow()+",\"gpu_generation_light_ms\":"+report.stages().nanos(NativeTimings.LIGHT_HOST)/1e6+"}}");
            require(report.stages().nanos(NativeTimings.LIGHT_HOST)>0,"lighting host time reaches F3");
            if(report.stages().lightingMeasured())require(report.stages().nanos(NativeTimings.LIGHT_SPREAD)>0,"propagation has real device timestamps");
            var repaired=NativeTerrain.instance().generateRegion(request,path,SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:plains");
            require(repaired.generated()==1&&repaired.preserved()==1023,"repair preserves prior chunks");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-light",Level.OVERWORLD,"chunk"),directory,false)) {
                require(storage.read(new ChunkPos(-31,-31)).getStringOr("retina_edit","").equals("preserved"),"prelit player edits preserved");
                require(!storage.read(new ChunkPos(-30,-30)).getBooleanOr("isLightOn",false),"repair delegates lighting around actual saved neighbors");
            }
            System.out.println("QA_EVT {\"event\":\"gpu_lighting_single_mca_publish\",\"status\":\"pass\",\"context\":{\"lit\":900,\"unlit\":124,\"lighting_ms\":"+report.stages().nanos(NativeTimings.LIGHT_HOST)/1e6+"}}");
        } finally {try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static final class SavedGrid implements LightChunkGetter {
        final ProtoChunk[] chunks=new ProtoChunk[1024];final List<SerializableChunkData> saved;
        final LevelHeightAccessor bounds;
        SavedGrid(PalettedContainerFactory factory,List<SerializableChunkData> saved) {
            this(factory,saved,LevelHeightAccessor.create(0,HEIGHT));
        }
        SavedGrid(PalettedContainerFactory factory,List<SerializableChunkData> saved,LevelHeightAccessor bounds) {
            this.saved=saved;this.bounds=bounds;
            for(int i=0;i<1024;i++) {
                var data=saved.get(i);var sections=new LevelChunkSection[bounds.getSectionsCount()];
                for(var section:data.sectionData())if(section.chunkSection()!=null && section.y()>=bounds.getMinSectionY() && section.y()<=bounds.getMaxSectionY())
                    sections[bounds.getSectionIndexFromSectionY(section.y())]=section.chunkSection();
                chunks[i]=new ProtoChunk(data.chunkPos(),UpgradeData.EMPTY,sections,
                    new net.minecraft.world.ticks.ProtoChunkTicks<>(),new net.minecraft.world.ticks.ProtoChunkTicks<>(),
                    bounds,factory,null);
                chunks[i].initializeLightSources();
            }
        }
        public LightChunk getChunkForLighting(int x,int z){var origin=chunks[0].getPos();x-=origin.x();z-=origin.z();
            return x>=0&&z>=0&&x<32&&z<32?chunks[z*32+x]:null;}
        public BlockGetter getLevel(){return chunks[0];}
        boolean prelit(int i,boolean imported){return imported&&saved.get(i).lightCorrect()&&i%32>0&&i%32<31&&i/32>0&&i/32<31;}
        double measure(boolean imported){long start=System.nanoTime();engine(imported);return (System.nanoTime()-start)/1e6;}
        LevelLightEngine engine(boolean imported) {
            var engine=new LevelLightEngine(this,true,true);
            for(int i=0;i<1024;i++) {
                var pos=chunks[i].getPos();var data=saved.get(i);boolean prelit=prelit(i,imported);
                for(int s=0;s<bounds.getSectionsCount();s++)if(!chunks[i].getSection(s).hasOnlyAir())engine.updateSectionStatus(SectionPos.of(pos,bounds.getSectionYFromSectionIndex(s)),false);
                if(prelit) {
                    engine.retainData(pos,true);
                    for(var section:data.sectionData()) {
                        var p=SectionPos.of(pos,section.y());
                        if(section.blockLight()!=null)engine.queueSectionData(LightLayer.BLOCK,p,section.blockLight().copy());
                        if(section.skyLight()!=null)engine.queueSectionData(LightLayer.SKY,p,section.skyLight().copy());
                    }
                }
                chunks[i].setPersistedStatus(prelit?ChunkStatus.LIGHT:ChunkStatus.FEATURES);chunks[i].setLightEngine(engine);
                engine.setLightEnabled(pos,prelit);
            }
            engine.runLightUpdates();
            for(int i=0;i<1024;i++)if(!prelit(i,imported)){
                engine.propagateLightSources(chunks[i].getPos());seed(engine,this,chunks[i].getPos());
            }
            engine.runLightUpdates();return engine;
        }
    }
    private static void savedBenchmark(Path directory,PalettedContainerFactory factory) throws Exception {
        var bounds=LevelHeightAccessor.create(-64,384);var saved=new ArrayList<SerializableChunkData>();
        try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-light-benchmark",Level.OVERWORLD,"chunk"),directory,false)) {
            for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
                var tag=storage.read(new ChunkPos(x,z));require(tag!=null,"full saved region benchmark needs all chunks");
                saved.add(SerializableChunkData.parse(bounds,factory,tag));
            }
        }
        var grid=new SavedGrid(factory,saved,bounds);
        var vanilla=grid.engine(false);var imported=grid.engine(true);var random=new Random(31579);
        for(int i=0;i<100000;i++) {
            var pos=new BlockPos(random.nextInt(512),-64+random.nextInt(384),random.nextInt(512));
            for(var layer:LightLayer.values())require(vanilla.getLayerListener(layer).getLightValue(pos)
                    ==imported.getLayerListener(layer).getLightValue(pos),"live saved-region light differs at "+pos);
        }
        double[] a=new double[4],b=new double[4];
        for(int i=0;i<4;i++)if(i%2==0){a[i]=grid.measure(false);b[i]=grid.measure(true);}
            else{b[i]=grid.measure(true);a[i]=grid.measure(false);}
        System.out.println("QA_EVT {\"event\":\"live_saved_region_light_benchmark\",\"status\":\"pass\",\"context\":{\"comparisons\":200000,\"vanilla_light_ms\":"+Arrays.stream(a).average().orElseThrow()+",\"prelit_load_ms\":"+Arrays.stream(b).average().orElseThrow()+"}}");
    }
    /** Direct engines are private in LevelLightEngine; the production mixin calls this same helper. */
    private static void seed(LevelLightEngine engine,LightChunkGetter source,ChunkPos pos) {
        try {
            var block=LevelLightEngine.class.getDeclaredField("blockEngine");block.setAccessible(true);
            var sky=LevelLightEngine.class.getDeclaredField("skyEngine");sky.setAccessible(true);
            LightSeams.seed(source,pos,(LightEngine<?,?>)block.get(engine),(LightEngine<?,?>)sky.get(engine));
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void require(boolean test,String message){if(!test)throw new AssertionError(message);}
}
