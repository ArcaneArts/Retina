package art.arcane.retina.worldgen;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.EndGatewayFeature;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.util.*;

/** Whole actual world-seeded gateway roots and source block-entity codecs. */
public final class EndGatewayTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var cases=new JsonArray();int roots=0,accepted=0,rejected=0,drawCandidates=0,generatedChunks=0;boolean crossing=false;
        try(var f=new EndReferenceFixtures()) {
            var type=f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var placed=f.registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,Identifier.withDefaultNamespace("end_gateway_return"))).value();
            var steps=FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()),b->b.value().getGenerationSettings().features(),true);
            int step=-1,index=-1;for(int s=0;s<steps.size();s++){int i=steps.get(s).features().indexOf(placed);if(i>=0){step=s;index=i;}}
            require(index>=0,"source gateway feature index");
            for(long seed:new long[]{123456789,42}) {
                var data=EndTerrainProfile.export(f.registry,new GenerationSource(Level.END,f.generator,type),seed,StructureProfile.Context.NONE);
                var json=JsonParser.parseString(data.json()).getAsJsonObject();int recipe=-1;var recipes=json.getAsJsonArray("decorations");
                for(int i=0;i<recipes.size();i++)if(recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_gateway"))recipe=i;
                require(recipe>=0,"gateway root exported");var profile=BiomeTerrainProfile.register(data);var materials=profile.materials();var state=f.state(seed);
                int successes=0,failures=0;boolean boundary=false;
                // Source-discovered accepted/rejected anchors, retained as
                // tiny fixtures rather than rediscovered by a repeated search.
                int[] anchors=seed==42?new int[]{65,66,67,68,69,70,71,72,6077,7338,8113,8660,11145,14722,20066,20731,23384}:new int[]{65,66,67,68,69,70,71,72,2354,3862,6104,8145,11618,17067,18721,19361};
                for(int cz:anchors) {
                    int cx=seed==42?-33:31;
                    var prefilter=new WorldgenRandom(new XoroshiroRandomSource(0));long d=prefilter.setDecorationSeed(seed,cx*16,cz*16);prefilter.setFeatureSeed(d,index,step);drawCandidates++;
                    // The complete source placed feature below remains the
                    // oracle; this records which source rarity branch it took.
                    boolean rarity=prefilter.nextFloat()<1.0f/700.0f;
                    if(!rarity&&failures>=8)continue;
                    var capture=capture(f,seed,state);
                    var random=new WorldgenRandom(new XoroshiroRandomSource(0));random.setFeatureSeed(d,index,step);
                    new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(cx*16,0,cz*16));
                    boolean success=!capture.blocks.isEmpty();
                    int ax=cx,az=cz;boolean crosses=capture.blocks.keySet().stream().anyMatch(p->Math.floorDiv(p.getX(),512)!=Math.floorDiv(ax*16,512)||Math.floorDiv(p.getZ(),512)!=Math.floorDiv(az*16,512));
                    if(success&&successes>=8&&(!crosses||boundary)||!success&&failures>=8)continue;
                    var request=new TerrainRequest(seed,cx,cz,0,256,64,0,1,profile.nativeId());
                    var writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0x12345678);
                    var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),materials[writes[i+3]]);
                    require(actual.equals(capture.blocks),"world-seeded gateway geometry seed="+seed+" anchor="+cx+","+cz+" source="+capture.blocks+" actual="+actual);
                    if(success) {
                        var metadata=NativeTerrain.instance().structureData(request);checkEntities(f,capture,metadata);
                        require(NativeTerrain.instance().structureData(request).equals(metadata),"gateway metadata reread is stable");
                    }
                    var row=capture.snapshot();row.addProperty("seed",seed);row.add("anchor",EndReferenceFixtures.ints(cx,cz));row.addProperty("step",step);row.addProperty("index",index);row.addProperty("cross_region",crosses);row.addProperty("rarity_accepted",rarity);cases.add(row);
                    if(crosses&&!boundary) {checkChunk(f,profile,steps,seed,cx,cz);generatedChunks++;}
                    boundary|=crosses;crossing|=crosses;roots++;if(success){successes++;accepted++;}else{failures++;rejected++;}
                }
                require(successes>=8&&failures==8&&boundary,"bounded source roots and natural boundary gateway found");
                checkVariants(f,data,json,recipe,step,index,seed,cases);
                Files.writeString(out.resolve("profile-"+seed+".json"),data.json());
            }
        }
        require(roots==33,"all targeted natural roots exercised");
        var report=new JsonObject();report.addProperty("schema","retina.end.gateway-placement-parity.v1");report.add("cases",cases);report.addProperty("roots_exact",roots);report.addProperty("accepted",accepted);report.addProperty("rejected",rejected);report.addProperty("cross_region",crossing);report.addProperty("source_rng_candidates",drawCandidates);report.addProperty("configured_variants_exact",6);report.addProperty("generated_chunks_exact",generatedChunks);report.addProperty("generated_blocks_exact",generatedChunks*65536);report.addProperty("full_regions_generated",0);
        Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_gateway_parity\",\"roots\":"+roots+",\"accepted\":"+accepted+",\"rejected\":"+rejected+",\"status\":\"pass\"}");
    }
    private static void checkChunk(EndReferenceFixtures f,BiomeTerrainProfile profile,List<FeatureSorter.StepFeatureData> steps,long seed,int cx,int cz) {
        var json=JsonParser.parseString(profile.json()).getAsJsonObject();int reach=0;
        for(var recipe:json.getAsJsonArray("decorations")) reach=Math.addExact(reach,recipe.getAsJsonObject().get("reach").getAsInt());
        // Bound the complete chain of preceding roots, not just this feature's
        // writes. A writer outside this sum cannot affect the requested chunk.
        int radius=(reach+15)/16;var state=f.state(seed);var terrain=capture(f,seed,state);
        var world=new HashMap<BlockPos,BlockState>();var entities=new HashMap<BlockPos,CompoundTag>();
        for(int step=0;step<steps.size();step++) for(int index=0;index<steps.get(step).features().size();index++) {
            var placed=steps.get(step).features().get(index);var previous=new HashMap<>(world);
            for(int z=cz-radius;z<=cz+radius;z++)for(int x=cx-radius;x<=cx+radius;x++) {
                var level=f.new Capture(seed,-1,p->{var prior=previous.get(p);return prior!=null?prior:terrain.state(p);});
                var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,x*16,z*16);random.setFeatureSeed(d,index,step);
                new FeaturePlacer(level.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(x*16,0,z*16));
                level.blocks.keySet().forEach(entities::remove);world.putAll(level.blocks);
                level.blockEntities.forEach((p,e)->entities.put(p,e.saveWithFullMetadata(f.registry)));
            }
        }
        var request=new TerrainRequest(seed,cx,cz,0,256,64,0,1,profile.nativeId());
        try(var chunk=NativeTerrain.instance().generate(request)) {
            var materials=profile.materials();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=0;y<256;y++) {
                var p=new BlockPos(cx*16+x,y,cz*16+z);var wanted=world.get(p);if(wanted==null)wanted=terrain.state(p);
                int id=Short.toUnsignedInt(chunk.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,(y*256L+z*16+x)*2));
                require(materials[id].equals(wanted),"complete End feature composition seed="+seed+" at="+p+" source="+wanted+" actual="+materials[id]);
            }
        }
        var expected=new HashMap<BlockPos,CompoundTag>();entities.forEach((p,e)->{if(Math.floorDiv(p.getX(),16)==cx&&Math.floorDiv(p.getZ(),16)==cz)expected.put(p,e);});
        var actual=new HashMap<BlockPos,CompoundTag>();for(var value:NativeTerrain.instance().structureData(request).getListOrEmpty("block_entities")) {
            var tag=(CompoundTag)value;actual.put(new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0)),tag);
        }
        require(actual.equals(expected),"complete source feature composition block entities");
    }
    private static EndReferenceFixtures.Capture capture(EndReferenceFixtures f,long seed,RandomState state) {
        var columns=new HashMap<Long,net.minecraft.world.level.NoiseColumn>();
        return f.new Capture(seed,-1,p->{
            if(p.getY()<0||p.getY()>=256)return Blocks.AIR.defaultBlockState();
            long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
            return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
        });
    }
    private static void checkEntities(EndReferenceFixtures f,EndReferenceFixtures.Capture source,CompoundTag metadata) {
        var actual=new HashMap<BlockPos,CompoundTag>();
        for(var value:metadata.getListOrEmpty("block_entities")) {
            var tag=(CompoundTag)value;var pos=new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));actual.put(pos,tag);
            var entity=BlockEntity.loadStatic(pos,Blocks.END_GATEWAY.defaultBlockState(),tag,f.registry);
            require(entity!=null&&entity.saveWithFullMetadata(f.registry).equals(tag),"actual gateway block-entity codec round trip");
        }
        var expected=new HashMap<BlockPos,CompoundTag>();source.blockEntities.forEach((p,e)->expected.put(p,e.saveWithFullMetadata(f.registry)));
        require(actual.equals(expected),"source gateway block entity exit/age/exact/components: expected="+expected+" actual="+actual);
    }
    private static void checkVariants(EndReferenceFixtures f,TerrainProfileData data,JsonObject original,int recipe,int step,int index,long seed,JsonArray cases) {
        // A corner-spanning gateway above the base-noise range. Repeat its
        // position to exercise last-write metadata and live overlay replay.
        var origin=new BlockPos(-1,134,511);int cx=-1,cz=31;
        for(int variant=0;variant<3;variant++) {
            var feature=variant==0?EndGatewayFeature.delayedExitSearch():EndGatewayFeature.knownExit(new BlockPos(-17,65,33),variant==2);
            var entity=new TheEndGatewayBlockEntity(BlockPos.ZERO,Blocks.END_GATEWAY.defaultBlockState());feature.exit().ifPresent(p->entity.setExitPosition(p,feature.exact()));
            var json=original.deepCopy();var gateway=json.getAsJsonArray("decorations").get(recipe).getAsJsonObject().getAsJsonObject("end_gateway");gateway.add("tag",StructureProfile.tag(entity.saveWithFullMetadata(f.registry)));
            var fixed=new JsonObject();fixed.addProperty("type","fixed_placement");var positions=new JsonArray();positions.add(EndReferenceFixtures.ints(-1,134,511));positions.add(EndReferenceFixtures.ints(variant==1?-2:-1,134,511));fixed.add("positions",positions);var program=new JsonArray();program.add(fixed);gateway.add("placement",program);
            // Make every End biome eligible for this fixture; the native root
            // remains a genuine exported root with only its loaded definition changed.
            for(var biome:json.getAsJsonArray("biomes")) {var ids=biome.getAsJsonObject().getAsJsonArray("decorations");if(!ids.asList().contains(new JsonPrimitive(recipe)))ids.add(recipe);}
            int profile=NativeTerrain.instance().registerProfile(json.toString());var request=new TerrainRequest(seed,cx,cz,0,256,64,0,1,profile);
            var capture=f.new Capture(seed,-1);feature.place(capture.level,f.generator,new WorldgenRandom(new XoroshiroRandomSource(0)),origin);feature.place(capture.level,f.generator,new WorldgenRandom(new XoroshiroRandomSource(0)),variant==1?origin.west():origin);
            var writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0);
            var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),data.materials()[writes[i+3]]);
            require(actual.equals(capture.blocks),"configured gateway geometry and duplicate placement");checkEntities(f,capture,NativeTerrain.instance().structureData(request));
            var row=capture.snapshot();row.addProperty("configured_variant",variant);row.addProperty("overlapping_metadata",variant==1);row.addProperty("seed",seed);cases.add(row);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
