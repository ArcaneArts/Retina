package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.HugeFungusFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Actual Minecraft fungi on controlled nylium, obstructions and storage edges. */
final class NetherFungusTest {
    static void checkRegions(RegistryAccess registry,NoiseBasedChunkGenerator generator) throws Exception {
        for(var key:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(key);
            var data=TerrainProfileData.export(registry,RetinaBiomeSource.fromSelected(new FixedBiomeSource(biome)),0,256,42,
                    generator.generatorSettings().value(),StructureProfile.Context.NONE,true,false,true);
            var required=key.equals(Biomes.CRIMSON_FOREST)
                    ? Set.of(Blocks.GLOWSTONE,Blocks.CRIMSON_STEM,Blocks.CRIMSON_ROOTS,Blocks.SHROOMLIGHT,Blocks.WEEPING_VINES)
                    : Set.of(Blocks.GLOWSTONE,Blocks.WARPED_STEM,Blocks.WARPED_ROOTS,Blocks.NETHER_SPROUTS,Blocks.SHROOMLIGHT);
            try(var saved=NetherTerrainTest.fixture(registry,new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),generator.generatorSettings()),data,42)) {
                NetherRegionTest.check(data,saved,true,required);
            }
        }
    }

    static void check(RegistryAccess registry,TerrainProfileData exported,NoiseBasedChunkGenerator generator) throws Exception {
        var original=JsonParser.parseString(exported.json()).getAsJsonObject();
        var palette=new LinkedHashMap<BlockState,Integer>();for(var state:exported.materials())palette.put(state,palette.size());
        var recipes=new JsonArray();var features=new ArrayList<Feature>();
        var names=new ArrayList<String>();
        for(String name:List.of("crimson_fungus","warped_fungus")) {
            var loaded=(HugeFungusFeature)registry.lookupOrThrow(Registries.FEATURE)
                    .getOrThrow(ResourceKey.create(Registries.FEATURE,Identifier.parse("minecraft:"+name))).value();
            for(boolean planted:List.of(false,true)) {
                var feature=new HugeFungusFeature(loaded.validBaseState(),loaded.stemState(),loaded.hatState(),loaded.decorState(),loaded.replaceableBlocks(),planted);
                var wrapper=NetherFeatureProfile.export(feature,registry,s->palette.computeIfAbsent(s,ignored->palette.size()),
                        p->{throw new AssertionError("unexpected provider");},p->{throw new AssertionError("unexpected nested placement");},generator.getGenDepth(),(kind,id)->{throw new AssertionError("unexpected scheduled tick");});
                require(wrapper!=null,"registered fungus exports");
                var recipe=JsonParser.parseString("""
                        {"salt":0,"density":1,"low_density":1,"noise_count":false,"rarity":1,"tries":1,"spread":[0,0,0],"placement":[]}
                        """).getAsJsonObject();
                wrapper.entrySet().forEach(e->recipe.add(e.getKey(),e.getValue()));
                String id=name+"/planted="+planted;recipe.addProperty("source",id);recipes.add(recipe);features.add(feature);names.add(id);
            }
        }
        require(palette.size()==exported.materials().length,"loaded export reserves every fungus and vine material");
        var customized=new HugeFungusFeature(Blocks.NETHERRACK.defaultBlockState(),
                Blocks.BASALT.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,Direction.Axis.X),
                Blocks.MAGMA_BLOCK.defaultBlockState(),Blocks.GLOWSTONE.defaultBlockState(),BlockPredicate.matchesBlocks(Blocks.NETHERRACK),false);
        var wrapper=NetherFeatureProfile.export(customized,registry,s->palette.computeIfAbsent(s,ignored->palette.size()),
                p->{throw new AssertionError("unexpected provider");},p->{throw new AssertionError("unexpected nested placement");},generator.getGenDepth(),(kind,id)->{throw new AssertionError("unexpected scheduled tick");});
        var customRecipe=recipes.get(0).deepCopy().getAsJsonObject();wrapper.entrySet().forEach(e->customRecipe.add(e.getKey(),e.getValue()));
        customRecipe.addProperty("source","test:custom_fungus");recipes.add(customRecipe);features.add(customized);names.add("test:custom_fungus");
        DecorationProfile.finishPlacements(recipes,palette,GenerationBounds.noise(generator.generatorSettings().value().noiseSettings(),0,256),generator.getSeaLevel());
        DecorationProfile.materialFlags(original,palette);
        var materials=palette.keySet().toArray(BlockState[]::new);var serialized=new JsonArray();var carveable=new JsonArray();
        for(var state:materials) {serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.UNCARVABLE));}
        original.add("materials",serialized);original.add("carveable",carveable);original.add("lighting",LightingProfile.export(palette.keySet(),false));
        for(var entry:original.getAsJsonArray("decorations")) {
            var recipe=entry.getAsJsonObject();String name=recipe.get("source").getAsString();
            if(!Set.of("minecraft:crimson_forest_vegetation","minecraft:warped_forest_vegetation","minecraft:nether_sprouts").contains(name))continue;
            require(recipe.get("kind").getAsString().equals("simple_block"),"Nether plants retain exact support predicates: "+name);
            recipes.add(recipe.deepCopy());features.add(NativeBlockFeatureIntegrationTest.resolve(registry,name));names.add(name);
        }
        require(features.size()==8,"both fungus variants, customization and all three loaded small-vegetation recipes");
        original.add("decorations",recipes);original.add("ores",new JsonArray());
        for(var b:original.getAsJsonArray("biomes")) {
            for(String key:List.of("decorations","ores","carvers"))b.getAsJsonObject().add(key,new JsonArray());
            b.getAsJsonObject().remove("cave_features");
        }
        int cases=0,accepted=0,rejected=0,huge=0,aboveRoof=0,shroomlights=0;long writes=0;
        var ages=new TreeSet<Integer>();var heights=new TreeSet<Integer>();
        var successes=new TreeMap<String,Integer>();
        for(int floor:new int[]{0,31,95,111,126}) for(int obstruction:new int[]{0,1,2}) for(var base:List.of(Blocks.CRIMSON_NYLIUM,Blocks.WARPED_NYLIUM,Blocks.NETHERRACK)) {
            var data=original.deepCopy();var compiler=new RegistryGpuProgram(registry,0,128,32);
            var density=compiler.new Program();int y=density.node(29,1,0,0);
            int root=density.node(23,y,density.constant(1),density.constant(-1),-4096,floor+1);
            int barrier=floor+3;
            if(obstruction!=0)root=density.node(23,y,density.constant(1),root,barrier,barrier+1);
            density.roots.add(root);density.roots.add(density.constant(floor+1));
            var gpu=data.getAsJsonObject("registry_program");var programs=gpu.getAsJsonArray("programs");programs.set(1,density.finish());programs.set(2,density.finish());gpu.add("interpolations",new JsonArray());
            var barrierState=(obstruction==2?Blocks.NETHER_WART_BLOCK:Blocks.NETHERRACK).defaultBlockState();
            for(int i=0;i<exported.biomes().size();i++) {
                var material=compiler.new Program();int my=material.node(29,1,0,0);
                int result=material.node(23,my,material.constant(palette.get(base.defaultBlockState())+1),material.constant(palette.get(Blocks.NETHERRACK.defaultBlockState())+1),floor,floor+1);
                if(obstruction!=0)result=material.node(23,my,material.constant(palette.get(barrierState)+1),result,barrier,barrier+1);
                material.roots.add(result);programs.set(3+i,material.finish());
            }
            try(var saved=NetherTerrainTest.fixture(data,42)) {
                var request=saved.request(-2,-2);var terrain=new HashMap<ChunkPos,short[]>();
                final int wall=obstruction;
                Function<BlockPos,BlockState> expected=p->p.getY()>=128?Blocks.AIR.defaultBlockState():p.getY()==floor?base.defaultBlockState():wall!=0 && p.getY()==barrier?barrierState:p.getY()<floor?Blocks.NETHERRACK.defaultBlockState():p.getY()<32?Blocks.LAVA.defaultBlockState():Blocks.AIR.defaultBlockState();
                for(int id=0;id<features.size();id++)for(int seed=0;seed<192;seed++) {
                    var origin=new BlockPos(-17,floor+1,-17);var world=new NetherFeatureTest.World(request,materials,terrain,expected);
                    boolean placed=features.get(id).place(world.level,generator,new NativeBlockFeatureIntegrationTest.Stream(seed,false),origin);
                    var actual=new HashMap<BlockPos,BlockState>();var result=NativeTerrain.instance().decorationFeature(request,id,new int[]{-17,floor+1,-17},seed);
                    for(int i=0;i<result.length;i+=4)actual.put(new BlockPos(result[i],result[i+1],result[i+2]),materials[result[i+3]]);
                    require(actual.equals(world.changed),"fungus parity "+names.get(id)+" floor="+floor+" obstruction="+obstruction+" base="+base+" seed="+seed+": "+NativeBlockFeatureIntegrationTest.differences(world.changed,actual));
                    if(placed) {accepted++;successes.merge(names.get(id),1,Integer::sum);}else rejected++;cases++;writes+=actual.size();
                    int maxStem=0;boolean thick=false;
                    for(var e:actual.entrySet()) {
                        if(e.getKey().getY()>=128)aboveRoof++;
                        if(e.getValue().is(Blocks.SHROOMLIGHT))shroomlights++;
                        if(e.getValue().is(Blocks.WEEPING_VINES))ages.add(e.getValue().getValue(GrowingPlantHeadBlock.AGE));
                        if(features.get(id) instanceof HugeFungusFeature fungus && e.getValue().equals(fungus.stemState())) {maxStem=Math.max(maxStem,e.getKey().getY()-floor);thick|=e.getKey().getX()!=-17 || e.getKey().getZ()!=-17;}
                    }
                    if(maxStem>0)heights.add(maxStem);if(thick)huge++;
                }
            }
        }
        require(accepted>1000 && rejected>1000 && huge>0 && aboveRoof>0 && shroomlights>0 && heights.contains(26) && ages.equals(Set.of(23,24,25)) && successes.size()==8,"nonvacuous fungus/vegetation coverage: heights="+heights+" ages="+ages+" successes="+successes);
        var stats=new JsonObject();stats.addProperty("cases",cases);stats.addProperty("writes",writes);stats.addProperty("accepted",accepted);stats.addProperty("rejected",rejected);stats.addProperty("huge",huge);stats.addProperty("above_roof_writes",aboveRoof);stats.addProperty("shroomlights",shroomlights);stats.add("stem_heights",new Gson().toJsonTree(heights));stats.add("vine_ages",new Gson().toJsonTree(ages));stats.add("successes",new Gson().toJsonTree(successes));
        Files.writeString(Path.of("build/nether/fungus-reference-stats.json"),stats.toString());
        System.out.println("QA_EVT {\"event\":\"nether_fungi_reference\",\"status\":\"pass\",\"context\":"+stats+"}");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
