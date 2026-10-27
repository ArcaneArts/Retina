package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.dimension.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.io.*;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.IntStream;

/** Bounded production MCA publication from a saved development End definition. */
public final class EndRegionPublicationTest {
    record Region(long seed,int x,int z,List<ChunkPos> extra) { }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var cases=List.of(new Region(123456789,-2,-3,List.of(new ChunkPos(-36,-75))),
                new Region(42,-1,-3,List.of(new ChunkPos(-1,-73))),
                new Region(42,0,-3,List.of(new ChunkPos(0,-73))),
                new Region(123456789,0,0,List.of(new ChunkPos(0,0),new ChunkPos(6,0))));
        var reports=new JsonArray();
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var type=f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END);
            var source=new GenerationSource(Level.END,f.generator,type.value());
            for(int i=0;i<cases.size();i++) {
                var c=cases.get(i);Path directory=Files.createTempDirectory(out,"disposable-region-"+i+"-");
                var data=EndTerrainProfile.export(f.registry,source,c.seed(),StructureProfile.Context.of(manager,true));
                var direct=BiomeTerrainProfile.register(data);
                Path definition=developmentDefinition(f,data,c.seed(),type,directory);
                var bindingOnly=new HolderLookup.Provider() {
                    @Override public java.util.stream.Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys(){throw new AssertionError("Generation registry enumeration during saved binding");}
                    @Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                        if(!key.equals(Registries.BIOME)&&!key.equals(Registries.STRUCTURE))throw new AssertionError("Generation registry during binding: "+key);
                        return f.registry.lookup(key).map(value->value);
                    }
                };
                Path path=directory.resolve("r."+c.x()+"."+c.z()+".mca");
                try(var world=new WorldDefinitionContext(NativeWorldDefinition.open(definition))) {
                    var dimension=world.dimension(Level.END);dimension.bindDimensionType(type.value());
                    var generator=RetinaChunkGenerator.fromDefinition(dimension,bindingOnly);
                    require(generator.hasFrozenStructurePieces(),"saved city decoder selected");
                    var request=generator.request(999,c.x()*32,c.z()*32);require(request.seed()==c.seed(),"saved seed owns request");
                    long started=System.nanoTime();var report=NativeTerrain.instance().generateRegion(request,path,0,"invalid:ignored_saved_biome");long nanos=System.nanoTime()-started;
                    require(report.generated()==1024&&report.preserved()==0,"all region slots generated");
                    var row=check(f,manager,c,direct,generator,directory);
                    row.addProperty("generation_ms_diagnostic",nanos/1e6);row.addProperty("bytes",report.bytes());
                    row.addProperty("file_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                    var before=Files.readAllBytes(path);var repeat=NativeTerrain.instance().generateRegion(request,path,0,"invalid:ignored_saved_biome");
                    require(repeat.generated()==0&&repeat.preserved()==1024&&Arrays.equals(before,Files.readAllBytes(path)),"complete publication preserves bytes");
                    if(i==0)checkPartial(f,c,request,directory,row);
                    row.addProperty("backend",NativeTerrain.instance().backend());
                    row.addProperty("callers",1);row.addProperty("region_worker_budget",Integer.parseInt(System.getenv("RETINA_REGION_THREADS")));
                    reports.add(row);Files.writeString(out.resolve("case-"+i+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(row)+"\n");
                }
                // Only this fixture's explicitly disposable generated region.
                Files.delete(path);
                require(!Files.exists(path),"completed disposable MCA removed");
            }
        }
        var report=new JsonObject();report.addProperty("schema","retina.end.region-publication.v1");report.add("cases",reports);report.addProperty("full_regions",4);report.addProperty("production_activated",false);report.addProperty("performance_acceptance",false);
        Files.writeString(out.resolve("validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("QA_EVT "+report);
    }
    private static JsonObject check(EndReferenceFixtures f,StructureTemplateManager manager,Region region,BiomeTerrainProfile direct,RetinaChunkGenerator generator,Path directory) throws Exception {
        var nativeTerrain=NativeTerrain.instance();var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
        var placement=(RandomSpreadStructurePlacement)f.registry.lookupOrThrow(Registries.STRUCTURE_SET).getValueOrThrow(BuiltinStructureSets.END_CITIES).placement();
        var sourceStarts=new TreeMap<ChunkPos,StructureStart>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        int ox=region.x()*32,oz=region.z()*32;
        for(int gx=Math.floorDiv(ox-8,placement.spacing());gx<=Math.floorDiv(ox+39,placement.spacing());gx++)for(int gz=Math.floorDiv(oz-8,placement.spacing());gz<=Math.floorDiv(oz+39,placement.spacing());gz++) {
            var at=placement.getPotentialStructureChunk(region.seed(),gx*placement.spacing(),gz*placement.spacing());
            var start=EndCityPlacementTest.start(f,manager,city,region.seed(),at);if(start.isValid())sourceStarts.put(at,start);
        }
        var serialization=new StructurePieceSerializationContext(f.resources,f.registry,manager);
        var probes=new HashSet<>(region.extra());probes.addAll(List.of(new ChunkPos(ox,oz),new ChunkPos(ox+31,oz),new ChunkPos(ox,oz+31),new ChunkPos(ox+31,oz+31),new ChunkPos(ox+16,oz+16)));
        var biome=f.resolver(f.state(region.seed()));var digest=MessageDigest.getInstance("SHA-256");int chunks=0,lit=0,starts=0,references=0,blocksCompared=0,blockEntities=0,entities=0;
        var containers=PalettedContainerFactory.create(f.registry);
        try(var storage=new RegionFileStorage(new RegionStorageInfo("end-publication-test",Level.END,"chunk"),directory,false)) {
            for(int z=oz;z<oz+32;z++)for(int x=ox;x<ox+32;x++) {
                var at=new ChunkPos(x,z);var tag=storage.read(at);require(tag!=null,"saved slot "+at);
                require(tag.getIntOr("DataVersion",-1)==SharedConstants.getCurrentVersion().dataVersion().version(),"saved data version overrides caller");
                require(tag.getIntOr("xPos",Integer.MIN_VALUE)==x&&tag.getIntOr("zPos",Integer.MIN_VALUE)==z&&tag.getIntOr("yPos",-1)==0,"saved coordinates requested="+at+" actual="+tag.get("xPos")+","+tag.get("yPos")+","+tag.get("zPos"));
                boolean interior=x>ox&&x<ox+31&&z>oz&&z<oz+31;
                require(tag.getStringOr("Status","").equals(interior?"minecraft:light":"minecraft:features"),"boundary lighting status "+at);
                require(tag.getBooleanOr("isLightOn",false)==interior,"boundary lighting flag");if(interior)lit++;
                try(var bytes=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){NbtIo.write(tag,bytes);}
                var parsed=SerializableChunkData.parse(f.height,containers,tag);
                require(parsed!=null&&parsed.chunkPos().equals(at)&&parsed.lightCorrect()==interior,"Minecraft chunk parser");
                var rewritten=parsed.write();
                for(String key:List.of("structures","block_entities","entities","Heightmaps","block_ticks","fluid_ticks"))
                    require(tag.get(key).equals(rewritten.get(key)),"Minecraft codec retains "+key+" at "+at);
                var sections=McaTestSections.terrain(tag,0,256);
                // parse() can recover a corrupt palette with fallback air. Use
                // the nonrecovering codec result for every stored container.
                var decodedBlocks=new ArrayList<PalettedContainer<BlockState>>();
                for(var section:sections)decodedBlocks.add(codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow());
                var sourceBiomes=new String[16];
                for(int dz=0;dz<4;dz++)for(int dx=0;dx<4;dx++)sourceBiomes[dz*4+dx]=EndReferenceFixtures.biome(biome.getNoiseBiome(x*4+dx,0,z*4+dz));
                for(var section:sections) {
                    var b=section.getCompoundOrEmpty("biomes");var palette=b.getListOrEmpty("palette");int bits=palette.size()<=1?0:32-Integer.numberOfLeadingZeros(palette.size()-1);
                    var packed=bits==0?null:new SimpleBitStorage(bits,64,b.getLongArray("data").orElseThrow());
                    for(int i=0;i<64;i++)require(palette.getString(packed==null?0:packed.get(i)).orElseThrow().equals(sourceBiomes[i%16]),"source biome "+at+" section="+section.getByteOr("Y",(byte)-1));
                }
                var structures=tag.getCompoundOrEmpty("structures");var saved=structures.getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:end_city");var wanted=sourceStarts.get(at);
                if(wanted!=null){require(saved.equals(wanted.createTag(serialization,at)),"source saved city start "+at);starts++;}
                else require(saved.isEmpty()||saved.getStringOr("id","").equals("INVALID"),"no invented source start "+at);
                var refs=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();for(var entry:sourceStarts.entrySet())if(Math.abs(entry.getKey().x()-x)<=8&&Math.abs(entry.getKey().z()-z)<=8&&entry.getValue().getBoundingBox().intersects(x*16,z*16,x*16+15,z*16+15))refs.add(entry.getKey().pack());
                var actualRefs=structures.getCompoundOrEmpty("References").getLongArray("minecraft:end_city").orElse(new long[0]);
                require(Arrays.equals(actualRefs,refs.toLongArray()),"source city references "+at);references+=refs.size();
                blockEntities+=tag.getListOrEmpty("block_entities").size();entities+=tag.getListOrEmpty("entities").size();
                if(probes.contains(at)) {
                    var request=new TerrainRequest(region.seed(),x,z,0,256,64,0,1,direct.nativeId());
                    try(var generated=nativeTerrain.generate(request)) {
                        var all=new BlockState[65536];
                        for(var section:sections){int sy=section.getByteOr("Y",(byte)-1);var blocks=decodedBlocks.get(sy);
                            for(int y=0;y<16;y++)for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                                int index=(sy*16+y)*256+dz*16+dx;var actual=blocks.get(dx,y,dz);all[index]=actual;
                                var expected=direct.materials()[Short.toUnsignedInt(generated.blocks().get(ValueLayout.JAVA_SHORT,index*2L))];require(actual.equals(expected),"region/chunk state "+at+" index="+index+" source="+expected+" saved="+actual);blocksCompared++;
                            }
                        }
                        for(var kind:Heightmap.Types.values())if(tag.getCompoundOrEmpty("Heightmaps").contains(kind.name())) {
                            var packed=new SimpleBitStorage(9,256,tag.getCompoundOrEmpty("Heightmaps").getLongArray(kind.name()).orElseThrow());
                            for(int c=0;c<256;c++){int h=0;for(int y=255;y>=0;y--)if(kind.isOpaque().test(all[y*256+c])){h=y+1;break;}require(packed.get(c)==h,"source block-state heightmap "+at+" "+kind+" column="+c);}
                        }
                    }
                    var metadata=nativeTerrain.structureData(request);for(String key:List.of("structures","block_entities","entities","block_ticks","fluid_ticks","PostProcessing"))require(metadata.get(key)==null?tag.getListOrEmpty(key).isEmpty():metadata.get(key).equals(tag.get(key)),"region/chunk metadata "+at+" "+key);
                    require(Arrays.equals(nativeTerrain.sampleBiomes(request),nativeTerrain.sampleBiomes(generator.request(999,x,z))),"saved/direct biome profile binding");
                }
                chunks++;
            }
        }
        var row=new JsonObject();row.addProperty("seed",region.seed());row.add("region",EndReferenceFixtures.ints(region.x(),region.z()));row.addProperty("chunks_read",chunks);row.addProperty("interior_lit",lit);row.addProperty("source_city_starts",starts);row.addProperty("source_city_references",references);row.addProperty("block_entities",blockEntities);row.addProperty("entities",entities);row.addProperty("probe_blocks_exact",blocksCompared);row.addProperty("decoded_nbt_sha256",HexFormat.of().formatHex(digest.digest()));return row;
    }
    private static void checkPartial(EndReferenceFixtures f,Region region,TerrainRequest request,Path directory,JsonObject row) throws Exception {
        var editedAt=new ChunkPos(region.x()*32+16,region.z()*32+16);
        var missingAt=new ChunkPos(region.x()*32+31,region.z()*32+31);
        var info=new RegionStorageInfo("end-publication-test",Level.END,"chunk");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        CompoundTag edited,missing;
        try(var storage=new RegionFileStorage(info,directory,false)) {
            edited=storage.read(editedAt);missing=storage.read(missingAt);
            edited.putString("retina_test_marker","saved player edit");
            var section=McaTestSections.terrain(edited,0,256).getFirst();
            var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
            blocks.set(0,0,0,Blocks.DIAMOND_BLOCK.defaultBlockState());
            section.put("block_states",codec.encodeStart(NbtOps.INSTANCE,blocks).getOrThrow());
            storage.write(editedAt,edited);storage.write(missingAt,null);
        }
        var path=directory.resolve("r."+region.x()+"."+region.z()+".mca");
        var repair=NativeTerrain.instance().generateRegion(request,path,0,"invalid:ignored_saved_biome");
        require(repair.generated()==1&&repair.preserved()==1023&&repair.stages().chunks()==1,"single missing End slot filled");
        try(var storage=new RegionFileStorage(info,directory,false)) {
            require(edited.equals(storage.read(editedAt)),"saved End player block and metadata edits retained exactly");
            require(missing.equals(storage.read(missingAt)),"repaired End slot equals original decoded NBT");
        }
        row.addProperty("partial_repaired_chunks",1);row.addProperty("edited_chunk_preserved",true);
    }
    private static Path developmentDefinition(EndReferenceFixtures f,TerrainProfileData data,long seed,Holder.Reference<DimensionType> type,Path directory) throws Exception {
        // Keep the production End adapter disabled. Replace only this fixture's
        // unsupported entry with its actual exported End profile and palettes.
        var root=WorldDefinitionCompiler.compile(f.registry,new WorldOptions(seed,true,false),Map.of(LevelStem.END,new LevelStem(type,f.generator)),StructureProfile.Context.NONE,List.of("vanilla"),"End development publication fixture");
        var entry=root.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:the_end");entry.remove("unsupported_reason");
        entry.putIntArray("material_palette",IntStream.range(0,data.materials().length).toArray());entry.putIntArray("biome_palette",IntStream.range(0,data.biomes().size()).toArray());
        var profile=JsonParser.parseString(data.json()).getAsJsonObject();profile.remove("materials");profile.remove("mca_compression");profile.addProperty("dimension","minecraft:the_end");entry.put("profile",ProfileNbt.encode(profile));
        var materials=new ListTag();for(var state:data.materials()) {
            var encoded=BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow();var tag=new CompoundTag();tag.putString("id",encoded.isJsonPrimitive()?encoded.getAsString():encoded.getAsJsonObject().get("id").getAsString());
            var properties=new CompoundTag();if(encoded.isJsonObject()&&encoded.getAsJsonObject().has("properties"))encoded.getAsJsonObject().getAsJsonObject("properties").entrySet().forEach(p->properties.putString(p.getKey(),p.getValue().getAsString()));tag.put("properties",properties);materials.add(tag);
        }
        var biomes=new ListTag();data.biomes().forEach(b->biomes.add(StringTag.valueOf(b.unwrapKey().orElseThrow().identifier().toString())));root.getCompoundOrEmpty("shared_types").put("materials",materials);root.getCompoundOrEmpty("shared_types").put("biomes",biomes);
        return WorldDefinitionFile.writeNew(directory,root);
    }
    private static void require(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
