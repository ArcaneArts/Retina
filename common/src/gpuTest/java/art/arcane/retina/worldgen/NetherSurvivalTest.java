package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.nio.file.*;
import java.util.*;

/** Loaded fire and small mushroom survival, with actual pre-light Minecraft chunks. */
final class NetherSurvivalTest {
    static void check(RegistryAccess registry,TerrainProfileData exported,NoiseBasedChunkGenerator generator) throws Exception {
        var names=List.of("patch_fire","patch_soul_fire","brown_mushroom_nether","red_mushroom_nether","brown_mushroom_normal","red_mushroom_normal");
        var features=new ArrayList<Feature>();for(var name:names)features.add(NativeBlockFeatureIntegrationTest.resolve(registry,"minecraft:"+name));
        var floors=List.of(Blocks.NETHERRACK.defaultBlockState(),Blocks.SOUL_SAND.defaultBlockState(),Blocks.SOUL_SOIL.defaultBlockState(),
                Blocks.BASALT.defaultBlockState(),Blocks.PODZOL.defaultBlockState(),Blocks.MYCELIUM.defaultBlockState(),Blocks.GLASS.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP),
                Blocks.OAK_PLANKS.defaultBlockState(),Blocks.WARPED_STEM.defaultBlockState(),Blocks.AIR.defaultBlockState(),Blocks.LAVA.defaultBlockState(),Blocks.GLOWSTONE.defaultBlockState());
        int cases=0,accepted=0;var successes=new TreeMap<String,Integer>();
        for(boolean sky:List.of(false,true)) {
            var source=sky?TerrainProfileData.export(registry,RetinaBiomeSource.fromSelected(generator.getBiomeSource()),0,256,42,
                    generator.generatorSettings().value(),StructureProfile.Context.NONE,true,true,true):exported;
            var original=JsonParser.parseString(source.json()).getAsJsonObject();
            var palette=new LinkedHashMap<BlockState,Integer>();for(var state:source.materials())palette.put(state,palette.size());
            floors.forEach(s->palette.computeIfAbsent(s,ignored->palette.size()));
            for(var state:List.of(Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true),Blocks.STONE.defaultBlockState(),Blocks.WATER.defaultBlockState()))palette.computeIfAbsent(state,ignored->palette.size());
            var recipes=new JsonArray();
            for(var name:names) {
                var matching=original.getAsJsonArray("decorations").asList().stream().map(JsonElement::getAsJsonObject)
                        .filter(r->r.get("source").getAsString().equals("minecraft:"+name)).toList();
                require(matching.size()==1 && matching.getFirst().get("kind").getAsString().equals("simple_block"),"loaded survival feature exported once: "+name);
                recipes.add(matching.getFirst().deepCopy());
            }
            original.add("decorations",recipes);original.add("ores",new JsonArray());
            for(var b:original.getAsJsonArray("biomes")) {
                for(String key:List.of("decorations","ores","carvers"))b.getAsJsonObject().add(key,new JsonArray());b.getAsJsonObject().remove("cave_features");
            }
            // Re-export against the enlarged palette so support tables include
            // newly introduced glass/slabs/wet leaves and configured bases.
            var selected=registry.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.NETHER_WASTES);
            var isolatedProfiles=new JsonArray();isolatedProfiles.add(new JsonObject());
            var reexport=DecorationProfile.export(registry,List.of(selected),isolatedProfiles,palette,original,Collections.newSetFromMap(new IdentityHashMap<>()));
            recipes=new JsonArray();
            for(var name:names)for(var e:reexport)if(e.getAsJsonObject().get("source").getAsString().equals("minecraft:"+name))recipes.add(e);
            require(recipes.size()==names.size(),"all six Nether Wastes survival roots retained");
            DecorationProfile.finishPlacements(recipes,palette,GenerationBounds.noise(generator.generatorSettings().value().noiseSettings(),0,256),32);
            DecorationProfile.finishGenerationLight(recipes,sky);original.add("decorations",recipes);
            DecorationProfile.materialFlags(original,palette);
            var materials=palette.keySet().toArray(BlockState[]::new);var serialized=new JsonArray();var carveable=new JsonArray();
            for(var state:materials) {serialized.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow());carveable.add(!state.isAir() && state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.UNCARVABLE));}
            original.add("materials",serialized);original.add("carveable",carveable);original.add("lighting",LightingProfile.export(palette.keySet(),sky));
            var light=freshLight(registry,sky);
            for(var floor:floors) {
                var scene=new LinkedHashMap<BlockPos,BlockState>();var positions=new ArrayList<BlockPos>();
                for(int i=0;i<12;i++) {
                    var at=new BlockPos(-29+(i%4)*4,64,-29+(i/4)*4);positions.add(at);
                    if(i>=1 && i<=6)scene.put(at.relative(Direction.values()[i-1]),Blocks.OAK_PLANKS.defaultBlockState());
                    if(i==7)scene.put(at.north(),Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true));
                    if(i==8)scene.put(at.north(),Blocks.OAK_LEAVES.defaultBlockState());
                    if(i==9) {scene.put(at,Blocks.LAVA.defaultBlockState());scene.put(at.above(),Blocks.GLOWSTONE.defaultBlockState());}
                    if(i==10)scene.put(at,Blocks.WATER.defaultBlockState());
                    if(i==11)scene.put(at,Blocks.STONE.defaultBlockState());
                }
                scene.put(new BlockPos(-17,127,-21),Blocks.BEDROCK.defaultBlockState());
                var data=original.deepCopy();var compiler=new RegistryGpuProgram(registry,0,128,32);
                var density=compiler.new Program();density.roots.add(density.constant(1));density.roots.add(density.constant(128));
                var gpu=data.getAsJsonObject("registry_program");var programs=gpu.getAsJsonArray("programs");programs.set(1,density.finish());programs.set(2,density.finish());gpu.add("interpolations",new JsonArray());
                var material=compiler.new Program();int x=material.node(29,0,0,0),y=material.node(29,1,0,0),z=material.node(29,2,0,0);
                int root=material.node(23,y,material.constant(palette.get(Blocks.NETHERRACK.defaultBlockState())+1),material.constant(palette.get(Blocks.AIR.defaultBlockState())+1),-4096,63);
                root=material.node(23,y,material.constant(palette.get(floor)+1),root,63,64);
                for(var e:scene.entrySet()) {
                    var p=e.getKey();int value=material.constant(palette.get(e.getValue())+1);
                    value=material.node(23,x,value,root,p.getX(),p.getX()+1);value=material.node(23,y,value,root,p.getY(),p.getY()+1);
                    root=material.node(23,z,value,root,p.getZ(),p.getZ()+1);
                }
                material.roots.add(root);for(int i=0;i<source.biomes().size();i++)programs.set(3+i,material.finish());
                try(var saved=NetherTerrainTest.fixture(data,42,NativeWorldDefinition.ProgramExecution.INTERPRETER)) {
                    var request=saved.request(-2,-2);var terrain=new HashMap<ChunkPos,short[]>();
                    for(var site:positions)for(int height:new int[]{0,32,64,127,128,255})for(int id=0;id<features.size();id++) {
                        var at=new BlockPos(site.getX(),height,site.getZ());
                        var world=new NetherFeatureTest.World(request,materials,terrain,p->p.getY()>=128?Blocks.AIR.defaultBlockState():scene.getOrDefault(p,p.getY()<63?Blocks.NETHERRACK.defaultBlockState():p.getY()==63?floor:Blocks.AIR.defaultBlockState()));
                        world.lighting=light;
                        boolean placed=features.get(id).place(world.level,generator,new NativeBlockFeatureIntegrationTest.Stream(42,false),at);
                        var actual=new HashMap<BlockPos,BlockState>();var output=NativeTerrain.instance().decorationFeature(request,id,new int[]{at.getX(),at.getY(),at.getZ()},42);
                        for(int i=0;i<output.length;i+=4)actual.put(new BlockPos(output[i],output[i+1],output[i+2]),materials[output[i+3]]);
                        require(actual.equals(world.changed),"survival parity "+names.get(id)+" sky="+sky+" floor="+floor+" at="+at+": "+NativeBlockFeatureIntegrationTest.differences(world.changed,actual));
                        if(placed) {accepted++;successes.merge(names.get(id)+"/sky="+sky,1,Integer::sum);}cases++;
                    }
                }
            }
        }
        require(accepted>100 && accepted<cases && successes.size()==names.size()*2,"both dimensions exercise all survival classes and rejection");
        var stats=new JsonObject();stats.addProperty("cases",cases);stats.addProperty("accepted",accepted);stats.addProperty("rejected",cases-accepted);stats.add("successes",new Gson().toJsonTree(successes));
        Files.writeString(Path.of("build/nether/survival-reference-stats.json"),stats.toString());
        System.out.println("QA_EVT {\"event\":\"nether_survival_reference\",\"status\":\"pass\",\"context\":"+stats+"}");
    }

    private static LevelLightEngine freshLight(RegistryAccess registry,boolean sky) {
        var chunk=new ProtoChunk(new ChunkPos(-2,-2),UpgradeData.EMPTY,LevelHeightAccessor.create(0,256),PalettedContainerFactory.create(registry),null);
        chunk.setPersistedStatus(ChunkStatus.FEATURES);
        var light=new LevelLightEngine(new LightChunkGetter() {
            public LightChunk getChunkForLighting(int x,int z){return x==-2 && z==-2?chunk:null;}
            public BlockGetter getLevel(){return chunk;}
        },true,sky);
        chunk.setLightEngine(light);
        var at=new BlockPos(-25,64,-25);chunk.setBlockState(at,Blocks.GLOWSTONE.defaultBlockState(),2);chunk.setBlockState(at.below(),Blocks.LAVA.defaultBlockState(),2);
        require(ChunkStatus.FEATURES.isBefore(ChunkStatus.INITIALIZE_LIGHT),"feature stage precedes light initialization");
        for(int y:new int[]{0,32,64,127,128,255})require(light.getRawBrightness(new BlockPos(-25,y,-25),0)==(sky?15:0),"actual fresh feature-stage light storage defines mushroom context");
        return light;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
