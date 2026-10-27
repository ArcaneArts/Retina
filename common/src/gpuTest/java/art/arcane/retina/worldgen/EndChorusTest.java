package art.arcane.retina.worldgen;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.*;
import java.nio.file.*;
import java.util.*;

/** Whole source chorus roots; base columns and live heights come from vanilla. */
public final class EndChorusTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var cases=new JsonArray();int roots=0,accepted=0,rejected=0;boolean crossing=false,multiple=false;int duplicateHeightCases=0,generatedChunks=0;
        try(var f=new EndReferenceFixtures()) {
            var type=f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var placed=f.registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,Identifier.withDefaultNamespace("chorus_plant"))).value();
            var steps=FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()),b->b.value().getGenerationSettings().features(),true);
            int step=-1,index=-1;for(int s=0;s<steps.size();s++){int i=steps.get(s).features().indexOf(placed);if(i>=0){step=s;index=i;}}
            require(index>=0,"source chorus feature index");
            for(long seed:new long[]{123456789,42}) {
                var data=EndTerrainProfile.export(f.registry,new GenerationSource(Level.END,f.generator,type),seed,StructureProfile.Context.NONE);
                var json=JsonParser.parseString(data.json()).getAsJsonObject();int recipe=-1;var recipes=json.getAsJsonArray("decorations");
                for(int i=0;i<recipes.size();i++)if(recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_chorus"))recipe=i;
                require(recipe>=0,"chorus root exported");var chorus=recipes.get(recipe).getAsJsonObject().getAsJsonObject("end_chorus");
                require(chorus.get("step").getAsInt()==step&&chorus.get("index").getAsInt()==index,"source feature seed indices");
                require(recipes.get(recipe).getAsJsonObject().get("reach").getAsInt()==23,"derived spread and placement read reach");
                try(var profile=EndDefinitionFixture.create(f,data,seed)) {
                    var materials=profile.materials();var state=f.state(seed);var resolver=f.resolver(state);
                    int tested=0;
                    for(int cz=65;cz<400&&tested<16;cz++) {
                        int cx=seed==42?-33:31;
                        if(!resolver.getNoiseBiome(cx*4+2,0,cz*4+2).unwrapKey().orElseThrow().identifier().toString().equals("minecraft:end_highlands"))continue;
                        var columns=new HashMap<Long,net.minecraft.world.level.NoiseColumn>();
                        var capture=f.new Capture(seed,-1,pos->{
                            if(pos.getY()<0||pos.getY()>=256)return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                            long key=((long)pos.getX()<<32)^Integer.toUnsignedLong(pos.getZ());
                            return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(pos.getX(),pos.getZ(),f.height,state)).getBlock(pos.getY());
                        });
                        var random=new EndIslandTest.TraceRandom();long decoration=random.setDecorationSeed(seed,cx*16,cz*16);random.setFeatureSeed(decoration,index,step);
                        new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(cx*16,0,cz*16));
                        var request=profile.request(cx, cz);
                        int[] writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0x12345678);
                        var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),materials[writes[i+3]]);
                        if(!actual.equals(capture.blocks)) {
                            var mismatch=new TreeSet<String>();capture.blocks.forEach((p,b)->{if(!b.equals(actual.get(p)))mismatch.add(p+" expected="+b+" actual="+actual.get(p));});
                            actual.forEach((p,b)->{if(!capture.blocks.containsKey(p))mismatch.add(p+" unexpected="+b);});
                            throw new AssertionError("chorus seed="+seed+" anchor="+cx+","+cz+" expected="+capture.blocks.size()+" actual="+actual.size()+" differences="+mismatch.stream().limit(8).toList());
                        }
                        var row=capture.snapshot();row.addProperty("seed",seed);row.add("anchor",EndReferenceFixtures.ints(cx,cz));row.addProperty("step",step);row.addProperty("index",index);cases.add(row);
                        boolean success=!actual.isEmpty();if(success)accepted++;else rejected++;
                        int ax=cx,az=cz;crossing|=actual.keySet().stream().anyMatch(p->Math.floorDiv(p.getX(),512)!=Math.floorDiv(ax*16,512)||Math.floorDiv(p.getZ(),512)!=Math.floorDiv(az*16,512));
                        multiple|=random.draws.stream().filter(v->v[0]==4).count()>12;
                        if(success && duplicateHeightCases < (seed==42?2:1)) {
                            var stem=capture.blocks.keySet().stream().filter(p->capture.base.apply(p.below()).is(net.minecraft.world.level.block.Blocks.END_STONE)).findFirst().orElseThrow();
                            checkDuplicateHeight(f,data,json,recipe,placed,seed,step,index,stem);duplicateHeightCases++;
                            checkChunk(f,profile,steps,seed,cx,cz);generatedChunks++;
                        }
                        tested++;roots++;
                    }
                    require(tested==16,"bounded highlands roots exercised");Files.writeString(out.resolve("profile-"+seed+".json"),data.json());
                }
            }
        }
        require(accepted>16&&rejected>0,"successful and rejected roots");require(crossing,"region crossing plants");require(multiple,"recursive shared random draws");
        var report=new JsonObject();report.addProperty("schema","retina.end.chorus-placement-parity.v1");report.add("cases",cases);report.addProperty("roots_exact",roots);report.addProperty("accepted",accepted);report.addProperty("rejected",rejected);report.addProperty("cross_region",crossing);report.addProperty("full_regions_generated",0);report.addProperty("duplicate_live_height_cases",duplicateHeightCases);report.addProperty("generated_chunks_exact",generatedChunks);report.addProperty("generated_blocks_exact",generatedChunks*65536);
        Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_chorus_parity\",\"roots\":"+roots+",\"accepted\":"+accepted+",\"rejected\":"+rejected+",\"status\":\"pass\"}");
    }
    private static void checkChunk(EndReferenceFixtures f,EndDefinitionFixture profile,List<FeatureSorter.StepFeatureData> steps,long seed,int cx,int cz) {
        var state=f.state(seed);var columns=new HashMap<Long,net.minecraft.world.level.NoiseColumn>();
        java.util.function.Function<BlockPos,BlockState> base=p->{
            if(p.getY()<0||p.getY()>=256)return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
            return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
        };
        var expected=new HashMap<BlockPos,BlockState>();
        for(int step=0;step<steps.size();step++) for(int index=0;index<steps.get(step).features().size();index++) {
            var placed=steps.get(step).features().get(index);var feature=placed.feature().value();
            if(!(feature instanceof net.minecraft.world.level.levelgen.feature.EndIslandFeature || feature instanceof net.minecraft.world.level.levelgen.feature.ChorusPlantFeature))continue;
            for(int z=cz-2;z<=cz+2;z++)for(int x=cx-2;x<=cx+2;x++) {
                // Independent roots, composed in the ordered region pipeline's
                // stage/anchor order with live world reads after earlier roots.
                var previous=new HashMap<>(expected);
                var capture=f.new Capture(seed,-1,p->previous.getOrDefault(p,base.apply(p)));
                var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,x*16,z*16);random.setFeatureSeed(d,index,step);
                new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(x*16,0,z*16));
                expected.putAll(capture.blocks);
            }
        }
        var request=profile.request(cx, cz);
        try(var chunk=NativeTerrain.instance().generate(request)) {
            var materials=profile.materials();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++)for(int y=0;y<256;y++) {
                var p=new BlockPos(cx*16+x,y,cz*16+z);var wanted=expected.getOrDefault(p,base.apply(p));
                int id=Short.toUnsignedInt(chunk.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,(y*256L+z*16+x)*2));
                require(materials[id].equals(wanted),"generated chorus chunk seed="+seed+" at="+p+" expected="+wanted+" actual="+materials[id]);
            }
        }
    }
    private static void checkDuplicateHeight(EndReferenceFixtures f,TerrainProfileData data,JsonObject original,int recipe,PlacedFeature placed,long seed,int step,int index,BlockPos stem) {
        var point=new BlockPos(stem.getX(),0,stem.getZ());int cx=Math.floorDiv(point.getX(),16),cz=Math.floorDiv(point.getZ(),16);
        var state=f.state(seed);var columns=new HashMap<Long,net.minecraft.world.level.NoiseColumn>();
        var capture=f.new Capture(seed,-1,p->{
            if(p.getY()<0||p.getY()>=256)return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
            return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
        });
        var variant=new PlacedFeature(placed.feature(),List.of(FixedPlacement.of(point,point,point),HeightmapPlacement.onHeightmap(Heightmap.Types.MOTION_BLOCKING)));
        var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,cx*16,cz*16);random.setFeatureSeed(d,index,step);
        new FeaturePlacer(capture.level,f.generator).place(variant,random,new BlockPos(cx*16,0,cz*16));
        require(!capture.blocks.isEmpty(),"duplicate height fixture grows the first plant");
        var json=original.deepCopy();var program=new JsonArray();var fixed=new JsonObject();fixed.addProperty("type","fixed_placement");var positions=new JsonArray();
        for(int i=0;i<3;i++)positions.add(EndReferenceFixtures.ints(point.getX(),0,point.getZ()));fixed.add("positions",positions);program.add(fixed);
        var height=new JsonObject();height.addProperty("type","heightmap");height.addProperty("map",4);program.add(height);
        json.getAsJsonArray("decorations").get(recipe).getAsJsonObject().getAsJsonObject("end_chorus").add("placement",program);
        try(var profile=EndDefinitionFixture.create(f,json,seed)) {
            int[] writes=NativeTerrain.instance().decorationFeature(profile.request(cx, cz),recipe,new int[]{cx*16,0,cz*16},0);
            var actual=new HashMap<BlockPos,BlockState>();for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),profile.materials()[writes[i+3]]);
            require(actual.equals(capture.blocks),"duplicate columns use live height after the first plant, seed="+seed);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
