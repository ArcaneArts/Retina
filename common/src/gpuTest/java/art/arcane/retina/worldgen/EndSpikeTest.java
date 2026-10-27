package art.arcane.retina.worldgen;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.util.*;

/** Actual vanilla placed spikes, including source entity constructors and NBT codecs. */
public final class EndSpikeTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var cases=new JsonArray();int roots=0,guarded=0,cells=0,generated=0;var identities=new HashSet<String>();
        try(var f=new EndReferenceFixtures()) {
            var entityLevel=EndEntityFixture.create(f.registry);
            var type=f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var placed=f.registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,Identifier.withDefaultNamespace("end_spike"))).value();
            var steps=FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()),b->b.value().getGenerationSettings().features(),true);
            int step=-1,index=-1;for(int s=0;s<steps.size();s++){int i=steps.get(s).features().indexOf(placed);if(i>=0){step=s;index=i;}}
            require(index>=0,"source spike feature index");
            var layoutCases=new JsonArray();
            for(long seed:new long[]{0,1,-1,42,123456789,Long.MIN_VALUE,Long.MAX_VALUE}) {
                var capture=f.new Capture(seed,-1);var layout=new JsonArray();
                for(var spike:EndSpikeFeature.getSpikesForLevel(capture.level)) {
                    var s=new JsonObject();s.addProperty("x",spike.getCenterX());s.addProperty("z",spike.getCenterZ());s.addProperty("radius",spike.getRadius());s.addProperty("height",spike.getHeight());s.addProperty("guarded",spike.isGuarded());layout.add(s);
                }
                var c=new JsonObject();c.addProperty("seed",seed);c.add("spikes",layout);layoutCases.add(c);
            }
            Files.writeString(out.resolve("layout-reference.json"),layoutCases+"\n");
            for(long seed:new long[]{123456789,42}) {
                var data=EndTerrainProfile.export(f.registry,new GenerationSource(Level.END,f.generator,type),seed,StructureProfile.Context.NONE);
                var json=JsonParser.parseString(data.json()).getAsJsonObject();int recipe=-1;var recipes=json.getAsJsonArray("decorations");
                for(int i=0;i<recipes.size();i++)if(recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_spikes"))recipe=i;
                require(recipe>=0,"spike root exported");
                try(var profile=EndDefinitionFixture.create(f,data,seed)) {
                    var materials=profile.materials();var state=f.state(seed);
                    var sourceWrites=new LinkedHashMap<BlockPos,BlockState>();
                    var sourceLayout=EndSpikeFeature.getSpikesForLevel(f.new Capture(seed,-1).level);
                    for(var spike:sourceLayout) {
                        int cx=Math.floorDiv(spike.getCenterX(),16),cz=Math.floorDiv(spike.getCenterZ(),16);
                        var columns=new HashMap<Long,net.minecraft.world.level.NoiseColumn>();
                        var capture=f.new Capture(seed,-1,p->{
                            if(p.getY()<0||p.getY()>=256)return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                            long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
                            return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
                        },entityLevel);
                        var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,cx*16,cz*16);random.setFeatureSeed(d,index,step);
                        new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(cx*16,0,cz*16));
                        require(capture.entities.size()==1,"actual source crystal spawned for spike");
                        var request=profile.request(cx, cz);
                        var writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0x12345678);
                        var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),materials[writes[i+3]]);
                        require(actual.equals(capture.blocks),"source spike/cage/cap geometry seed="+seed+" center="+spike.getCenterX()+","+spike.getCenterZ()+" expected="+capture.blocks.size()+" actual="+actual.size());
                        var metadata=NativeTerrain.instance().structureData(request);var entities=metadata.getListOrEmpty("entities");require(entities.size()==1,"native crystal saved once in its owner chunk");
                        var nativeEntity=(CompoundTag)entities.get(0);var sourceEntity=entityLevel.save(capture.entities.get(0));
                        require(identities.add(seed+":"+Arrays.toString(nativeEntity.getIntArray("UUID").orElseThrow())),"native crystal identities unique");
                        require(entityLevel.roundTrip(nativeEntity).equals(nativeEntity),"crystal load/save persistence through actual source codecs");
                        var expected=sourceEntity.copy();var found=nativeEntity.copy();expected.remove("UUID");found.remove("UUID");
                        require(expected.equals(found),"source crystal NBT except runtime-generated UUID: expected="+expected+" actual="+found);
                        require(NativeTerrain.instance().structureData(request).equals(metadata),"metadata reread does not duplicate crystals");
                        if(generated<(seed==42?4:2)) {
                            try(var chunk=NativeTerrain.instance().generate(request)) {
                                for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                                    var base=f.generator.getBaseColumn(cx*16+x,cz*16+z,f.height,state);
                                    for(int y=0;y<256;y++) {
                                        var wanted=capture.blocks.getOrDefault(new BlockPos(cx*16+x,y,cz*16+z),base.getBlock(y));
                                        int id=Short.toUnsignedInt(chunk.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,(y*256L+z*16+x)*2));
                                        require(materials[id].equals(wanted),"complete generated spike chunk at "+(cx*16+x)+","+y+","+(cz*16+z));
                                    }
                                }
                            }generated++;
                        }
                        var row=new JsonObject();row.addProperty("seed",seed);row.add("anchor",EndReferenceFixtures.ints(cx,cz));row.add("center",EndReferenceFixtures.ints(spike.getCenterX(),spike.getCenterZ()));row.addProperty("radius",spike.getRadius());row.addProperty("height",spike.getHeight());row.addProperty("guarded",spike.isGuarded());row.addProperty("cells_exact",actual.size());row.add("source_crystal",StructureProfile.tag(sourceEntity));row.add("native_crystal",StructureProfile.tag(nativeEntity));cases.add(row);
                        sourceWrites.putAll(capture.blocks);
                        roots++;cells+=actual.size();if(spike.isGuarded())guarded++;
                    }
                    writeSourceCells(out.resolve("source-writes-"+seed+".json.gz"),sourceWrites);
                    Files.writeString(out.resolve("profile-"+seed+".json"),data.json());
                    if(seed==123456789)checkCustom(f,entityLevel,data,json,recipe,step,index,cases);
                }
            }
        }
        require(roots==20&&guarded==4,"both complete source arena layouts and cages");
        var report=new JsonObject();report.addProperty("schema","retina.end.spike-placement-parity.v1");report.add("cases",cases);report.addProperty("roots_exact",roots);report.addProperty("guarded",guarded);report.addProperty("cells_exact",cells);report.addProperty("crystals_exact_except_runtime_uuid",roots);report.addProperty("generated_chunks_exact",generated);report.addProperty("generated_blocks_exact",generated*65536);report.addProperty("full_regions_generated",0);report.addProperty("configured_tall_spikes_exact",1);
        Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_spike_parity\",\"roots\":"+roots+",\"cells\":"+cells+",\"status\":\"pass\"}");
    }
    private static void writeSourceCells(Path path,Map<BlockPos,BlockState> cells) throws Exception {
        var palette=new LinkedHashMap<BlockState,Integer>();var states=new JsonArray();var writes=new JsonArray();
        cells.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))).forEach(e->{
            int id=palette.computeIfAbsent(e.getValue(),b->{states.add(BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,b).getOrThrow());return states.size()-1;});
            writes.add(EndReferenceFixtures.ints(e.getKey().getX(),e.getKey().getY(),e.getKey().getZ(),id));
        });
        var root=new JsonObject();root.add("palette",states);root.add("writes",writes);
        try(var stream=new java.util.zip.GZIPOutputStream(Files.newOutputStream(path))) {stream.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    private static void checkCustom(EndReferenceFixtures f,EndEntityFixture entityLevel,TerrainProfileData data,JsonObject original,int recipe,int step,int index,JsonArray cases) {
        long seed=123456789;int cx=-1,cz=31;var json=original.deepCopy();var spike=json.getAsJsonArray("decorations").get(recipe).getAsJsonObject().getAsJsonObject("end_spikes");
        var descriptor=new JsonObject();descriptor.addProperty("x",-1);descriptor.addProperty("z",511);descriptor.addProperty("radius",3);descriptor.addProperty("height",134);descriptor.addProperty("guarded",true);
        var spikes=new JsonArray();spikes.add(descriptor);spike.add("spikes",spikes);spike.addProperty("invulnerable",true);spike.add("beam_target",EndReferenceFixtures.ints(0,128,0));spike.add("placement",new JsonArray());
        try(var profile=EndDefinitionFixture.create(f,json,seed)) {
            var request=profile.request(cx, cz);
            var source=new EndSpikeFeature(List.of(new EndSpikeFeature.EndSpike(-1,511,3,134,true)),true,Optional.of(new BlockPos(0,128,0)));
            var capture=f.new Capture(seed,-1,null,entityLevel);var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,cx*16,cz*16);random.setFeatureSeed(d,index,step);
            source.place(capture.level,f.generator,random,new BlockPos(cx*16,0,cz*16));
            var writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0);
            var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),profile.materials()[writes[i+3]]);
            require(actual.equals(capture.blocks),"configured tall spike/cage geometry across both region axes");
            var metadata=NativeTerrain.instance().structureData(request);var entities=metadata.getListOrEmpty("entities");require(entities.size()==1,"configured crystal metadata");
            var expected=entityLevel.save(capture.entities.getFirst());var found=((CompoundTag)entities.get(0)).copy();require(entityLevel.roundTrip(found).equals(found),"configured crystal load/save persistence");expected.remove("UUID");found.remove("UUID");
            require(expected.equals(found),"configured crystal preserves beam target and invulnerability");
            var row=new JsonObject();row.addProperty("configured_tall_spike",true);row.addProperty("seed",seed);row.add("anchor",EndReferenceFixtures.ints(cx,cz));row.addProperty("cells_exact",actual.size());row.add("crystal_without_runtime_uuid",StructureProfile.tag(expected));cases.add(row);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
