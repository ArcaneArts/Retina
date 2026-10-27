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

/** Whole loaded island roots from actual world seeds, including rejected anchors. */
public final class EndIslandTest {
    static final class TraceRandom extends WorldgenRandom {
        final List<int[]> draws=new ArrayList<>();
        TraceRandom(){super(new XoroshiroRandomSource(0));}
        @Override public int nextInt(int bound){int n=super.nextInt(bound);draws.add(new int[]{bound,n});return n;}
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var cases=new JsonArray();int comparisons=0,accepted=0,rejected=0;
        boolean twoIslands=false,crossRegion=false,neighborBiomeCase=false;int generatedChunks=0;
        try(var f=new EndReferenceFixtures()) {
            var type=f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value();
            var placed=f.registry.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,Identifier.withDefaultNamespace("end_island_decorated"))).value();
            var steps=FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()),b->b.value().getGenerationSettings().features(),true);
            int step=-1,index=-1;for(int s=0;s<steps.size();s++){int i=steps.get(s).features().indexOf(placed);if(i>=0){step=s;index=i;}}
            require(index>=0,"actual vanilla island feature index");
            for(long seed:new long[]{123456789,42}) {
                var data=EndTerrainProfile.export(f.registry,new GenerationSource(Level.END,f.generator,type),seed,StructureProfile.Context.NONE);
                var json=JsonParser.parseString(data.json()).getAsJsonObject();int recipe=-1;
                var recipes=json.getAsJsonArray("decorations");for(int i=0;i<recipes.size();i++)if(recipes.get(i).getAsJsonObject().get("kind").getAsString().equals("end_island"))recipe=i;
                require(recipe>=0,"loaded island root exported");var island=recipes.get(recipe).getAsJsonObject().getAsJsonObject("end_island");
                require(island.get("step").getAsInt()==step&&island.get("index").getAsInt()==index,"actual per-step source feature index");
                try(var profile=EndDefinitionFixture.create(f,data,seed)) {
                    var capture=f.new Capture(seed,-1);var resolver=f.resolver(f.state(seed));int successes=0,failures=0;
                    // Targeted coordinates discovered once from the source; no broad
                    // anchor search is needed on subsequent reference checks.
                    int[] anchors=seed==42?new int[]{-80,-81,-82,-83,-105,-110,-152,-188,-199,-204,-240,-264}
                        :new int[]{80,81,82,83,108,128,159,190,193,210,213,289,3841};
                    for(int cz:anchors) {
                        int cx=seed==42?-33:31;
                        capture.blocks.clear();capture.blockEntities.clear();var random=new TraceRandom();
                        long decoration=random.setDecorationSeed(seed,cx*16,cz*16);random.setFeatureSeed(decoration,index,step);
                        new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(cx*16,0,cz*16));
                        boolean success=!capture.blocks.isEmpty();
                        var noiseBiome=resolver.getNoiseBiome(cx*4+2,0,cz*4+2).unwrapKey().orElseThrow().identifier().toString();
                        boolean neighbor=success&&!noiseBiome.equals("minecraft:small_end_islands");
                        boolean extraNeighbor=neighbor&&!neighborBiomeCase;
                        if((success&&successes>=8||!success&&failures>=4)&&!extraNeighbor)continue;
                        var request=profile.request(cx, cz);
                        int[] writes=NativeTerrain.instance().decorationFeature(request,recipe,new int[]{cx*16,0,cz*16},0x12345678);
                        var actual=new HashMap<BlockPos,BlockState>();var materials=profile.materials();
                        for(int i=0;i<writes.length;i+=4)actual.put(new BlockPos(writes[i],writes[i+1],writes[i+2]),materials[writes[i+3]]);
                        require(actual.equals(capture.blocks),"world-seeded island writes seed="+seed+" anchor="+cx+","+cz+" expected="+capture.blocks.size()+" actual="+actual.size());
                        boolean two=random.draws.stream().filter(v->v[0]==3).count()>=2;
                        boolean crossing=actual.keySet().stream().anyMatch(p->Math.floorDiv(p.getX(),512)!=Math.floorDiv(cx*16,512)||Math.floorDiv(p.getZ(),512)!=Math.floorDiv(cz*16,512));
                        twoIslands|=success&&two;crossRegion|=crossing;
                        var row=capture.snapshot();row.addProperty("seed",seed);row.add("anchor",EndReferenceFixtures.ints(cx,cz));row.addProperty("decoration_seed",decoration);
                        row.addProperty("step",step);row.addProperty("index",index);row.addProperty("two_islands",two);row.addProperty("crosses_region",crossing);row.addProperty("anchor_noise_biome",noiseBiome);row.addProperty("neighbor_biome_root",neighbor);cases.add(row);
                        if(success && (successes < 2 || extraNeighbor)) {
                            checkChunk(f,profile,placed,seed,cx,cz,step,index,recipes.get(recipe).getAsJsonObject().get("reach").getAsInt());
                            generatedChunks++;
                        }
                        neighborBiomeCase|=neighbor;
                        comparisons++;if(success){if(successes<8)successes++;accepted++;}else{failures++;rejected++;}
                    }
                    require(successes==8&&failures==4,"targeted accepted/rejected roots exercised");
                    Files.writeString(out.resolve("profile-"+seed+".json"),data.json());
                }
            }
        }
        require(twoIslands,"two-island shared RNG branch exercised");require(crossRegion,"region-crossing island writes exercised");require(neighborBiomeCase,"neighbor-biome root discovery exercised");
        var report=new JsonObject();report.addProperty("schema","retina.end.island-placement-parity.v1");report.add("cases",cases);
        report.addProperty("roots_exact",comparisons);report.addProperty("accepted",accepted);report.addProperty("rejected",rejected);
        report.addProperty("generated_chunks_exact",generatedChunks);report.addProperty("generated_blocks_exact",generatedChunks*65536);
        report.addProperty("two_island_case",twoIslands);report.addProperty("cross_region_case",crossRegion);report.addProperty("neighbor_biome_case",neighborBiomeCase);report.addProperty("full_regions_generated",0);
        Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_island_parity\",\"roots\":"+comparisons+",\"accepted\":"+accepted+",\"rejected\":"+rejected+",\"status\":\"pass\"}");
    }
    private static void checkChunk(EndReferenceFixtures f,EndDefinitionFixture profile,PlacedFeature placed,long seed,int cx,int cz,int step,int index,int reach) {
        var capture=f.new Capture(seed,-1);int radius=(reach+15)/16;
        for(int z=cz-radius;z<=cz+radius;z++)for(int x=cx-radius;x<=cx+radius;x++) {
            var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,x*16,z*16);random.setFeatureSeed(d,index,step);
            new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(x*16,0,z*16));
        }
        var state=f.state(seed);var request=profile.request(cx, cz);
        try(var chunk=NativeTerrain.instance().generate(request)) {
            var materials=profile.materials();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int wx=cx*16+x,wz=cz*16+z;var base=f.generator.getBaseColumn(wx,wz,f.height,state);
                for(int y=0;y<256;y++) {
                    var expected=capture.blocks.getOrDefault(new BlockPos(wx,y,wz),base.getBlock(y));
                    int id=Short.toUnsignedInt(chunk.blocks().get(java.lang.foreign.ValueLayout.JAVA_SHORT,(y*256L+z*16+x)*2));
                    require(materials[id].equals(expected),"published island chunk seed="+seed+" at "+wx+","+y+","+wz);
                }
            }
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
