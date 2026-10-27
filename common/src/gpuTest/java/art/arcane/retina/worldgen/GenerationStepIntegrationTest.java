package art.arcane.retina.worldgen;

import art.arcane.retina.core.TerrainRequest;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Observable feature -> structure -> feature -> structure dependencies, including neighbors and metadata. */
final class GenerationStepIntegrationTest {
    static void run(RegistryAccess registry,JsonObject source,BlockState[] sourceMaterials) throws Exception {
        var palette=new LinkedHashMap<BlockState,Integer>();for(var state:sourceMaterials)palette.put(state,palette.size());
        var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
        var world=source.deepCopy();world.remove("registry_program");world.remove("generation");world.remove("generation_bounds");world.remove("lighting");world.add("climate_targets",new JsonArray());
        var flat=new JsonObject();var layers=new JsonArray();int stone=palette.get(Blocks.NETHERRACK.defaultBlockState());for(int y=0;y<256;y++)layers.add(y<64?stone:0);
        flat.add("base_layers",layers);flat.add("deferred_layers",new JsonArray());flat.add("air_materials",StructureProfile.ints(0));world.add("flat",flat);
        var biomes=new JsonArray();var b=source.getAsJsonArray("biomes").get(0).deepCopy().getAsJsonObject();b.addProperty("id","minecraft:nether_wastes");b.add("cave_features",new JsonObject());b.add("ores",new JsonArray());biomes.add(b);world.add("biomes",biomes);
        world.add("terrain_features",new JsonObject());world.add("ores",new JsonArray());world.add("cave_noises",new JsonArray());world.addProperty("decoration_source_order",true);
        var settings=new BiomeGenerationSettings.PlainBuilder();
        settings.addFeature(2,feature(registry,"basalt","air",0,0,64,0,false));
        settings.addFeature(4,feature(registry,"gold_block","magma_block",0,0,64,0,false));
        settings.addFeature(7,feature(registry,"emerald_block","diamond_block",0,0,64,0,false));
        settings.addFeature(8,feature(registry,"ancient_debris","emerald_block",-1,0,64,1,false));
        for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})settings.addFeature(9,feature(registry,"netherrack","chest",offset[0],offset[1],64,0,false));
        settings.addFeature(9,feature(registry,"glowstone","chest",0,0,66,0,true));
        var generation=settings.build();
        var recipes=DecorationProfile.export(registry,List.of(biome),biomes,palette,world,Collections.newSetFromMap(new IdentityHashMap<>()),ignored->generation);
        world.add("decorations",recipes);
        var exporter=new StructureProfile(registry,List.of(biome),palette,StructureProfile.Context.NONE);
        var definitions=new JsonArray();var pools=new JsonObject();var sets=new JsonArray();
        for(int i=0;i<2;i++) {
            String id="retina:step_"+i;int template=exporter.template(id,template(i==1),0);
            var processors=JsonParser.parseString("""
                [{"processor_type":"minecraft:rule","rules":[{"input_predicate":{"predicate_type":"minecraft:block_match","block":"minecraft:obsidian"},
                 "location_predicate":{"predicate_type":"minecraft:block_match","block":"minecraft:basalt"},"output_state":{"id":"minecraft:magma_block"}}]}]
                """).getAsJsonArray();
            var rule=processors.get(0).getAsJsonObject().getAsJsonArray("rules").get(0).getAsJsonObject();
            if(i==1){rule.getAsJsonObject("location_predicate").addProperty("block","minecraft:gold_block");rule.getAsJsonObject("output_state").addProperty("id","minecraft:diamond_block");}
            exporter.registerProcessorStates(processors);
            var pool=JsonParser.parseString("{\"fallback\":\"minecraft:empty\",\"entries\":[{\"weight\":1,\"terrain_matching\":false,\"parts\":[{\"template\":0,\"ignore_air\":false}]}]}").getAsJsonObject();
            var part=pool.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject();part.addProperty("template",template);part.add("processors",processors);pools.add(id,pool);
            var definition=JsonParser.parseString("{\"kind\":\"jigsaw\",\"biomes\":[0],\"config\":{\"size\":0,\"start_height\":{\"absolute\":64},\"terrain_adaptation\":\"none\"}}").getAsJsonObject();
            definition.addProperty("id",id);definition.addProperty("pool",id);definition.getAsJsonObject("config").addProperty("step",i==0?"surface_structures":"underground_decoration");definitions.add(definition);
            var set=JsonParser.parseString("{\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":512,\"separation\":511,\"salt\":0},\"entries\":[{\"definition\":0,\"weight\":1}]}").getAsJsonObject();set.addProperty("id",id);set.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("definition",i);sets.add(set);
        }
        var structures=new JsonObject();structures.add("templates",exporter.templates);structures.add("definitions",definitions);structures.add("pools",pools);structures.add("sets",sets);world.add("structures",structures);
        StructureProfile.finishMaterials(structures,palette);exporter.finishProcessors(pools);
        DecorationProfile.finishPlacements(recipes,palette,GenerationBounds.uniform(0,256),32);DecorationProfile.materialFlags(world,palette);
        var materials=new JsonArray();var carveable=new JsonArray();palette.keySet().forEach(s->{materials.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,s).getOrThrow());carveable.add(false);});world.add("materials",materials);world.add("carveable",carveable);
        var states=palette.keySet().toArray(BlockState[]::new);var terrain=NativeTerrain.instance();
        try(var snapshot=DefinitionFixture.snapshot("minecraft:the_nether",world,
                new TerrainRequest(42,0,0,0,256,64,48,.008f,0),0,256,false,true);
            var profile=snapshot.open(null)) {
            Files.writeString(Path.of("build/nether/generation-step-fixture.json"),world.toString());
            var expected=new LinkedHashMap<ChunkPos,short[]>();
            for(var start:List.of(new ChunkPos(0,0),new ChunkPos(-512,512))) {
                for(int z=start.z()-1;z<=start.z()+1;z++)for(int x=start.x()-1;x<=start.x()+1;x++) {
                    var at=new ChunkPos(x,z);var request=profile.request(x,z);var blocks=PortalIntegrationTest.blocks(terrain,request);expected.put(at,blocks);
                    if(at.equals(start)) {
                        require(states[Short.toUnsignedInt(blocks[64*256])].is(Blocks.EMERALD_BLOCK),"both structures and features observe their predecessor at "+at+": "+states[Short.toUnsignedInt(blocks[64*256])]);
                        require(states[Short.toUnsignedInt(blocks[65*256])].is(Blocks.CHEST),"later structure chest survives");
                        require(states[Short.toUnsignedInt(blocks[66*256])].is(Blocks.GLOWSTONE),"later heightmap placement sees structure height");
                        require(terrain.structureData(request).getListOrEmpty("block_entities").size()==1,"replaced chest metadata removed and surviving chest retained");
                    } else require(terrain.structureData(request).getListOrEmpty("block_entities").isEmpty(),"cross-chunk replaced chest metadata removed");
                    if(x==start.x()-1&&z==start.z())require(states[Short.toUnsignedInt(blocks[64*256+15])].is(Blocks.ANCIENT_DEBRIS),"neighbor feature reads structure/feature chain at "+at+": "+states[Short.toUnsignedInt(blocks[64*256+15])]);
                }
            }
            try(var cold=snapshot.open(null)) {
                try(var workers=Executors.newFixedThreadPool(4)){var jobs=new ArrayList<Future<?>>();var entries=new ArrayList<>(expected.entrySet());Collections.reverse(entries);for(var entry:entries)jobs.add(workers.submit(()->require(Arrays.equals(entry.getValue(),PortalIntegrationTest.blocks(terrain,cold.request(entry.getKey().x(),entry.getKey().z()))),"cold concurrent ordered generation")));for(var job:jobs)job.get();}
            }
            var directory=Files.createTempDirectory(Path.of("build/nether"),"ordered-structure-regions-");var regions=new HashSet<ChunkPos>();expected.keySet().stream().filter(p->Math.abs(p.x())<32).forEach(p->regions.add(new ChunkPos(p.getRegionX()*32,p.getRegionZ()*32)));
            for(var at:regions)terrain.generateRegion(profile.request(at.x(),at.z()),directory.resolve("r."+at.getRegionX()+"."+at.getRegionZ()+".mca"),profile.dataVersion(),"minecraft:nether_wastes");
            var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
            try(var storage=new RegionFileStorage(new RegionStorageInfo("ordered-structures",Level.NETHER,"chunk"),directory,false)) {
                for(var entry:expected.entrySet())if(Math.abs(entry.getKey().x())<32) {
                    var chunk=Objects.requireNonNull(storage.read(entry.getKey()));var data=terrain.structureData(profile.request(entry.getKey().x(),entry.getKey().z()));
                    for(String key:List.of("structures","block_entities","fluid_ticks","block_ticks"))require(Objects.equals(chunk.get(key),data.get(key)),"ordered MCA metadata "+key);
                    for(var section:McaTestSections.terrain(chunk,0,256)){int y=section.getIntOr("Y",0)*16;var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();for(int dy=0;dy<16;dy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)require(blocks.get(x,dy,z).equals(states[Short.toUnsignedInt(entry.getValue()[(y+dy)*256+z*16+x])]),"ordered MCA voxel parity");}
                }
            }
            // Reversing only structure generation steps must visibly break the chain.
            var wrong=world.deepCopy();wrong.getAsJsonObject("structures").getAsJsonArray("definitions").get(0).getAsJsonObject().getAsJsonObject("config").addProperty("step","top_layer_modification");
            try(var reversed=NetherTerrainTest.fixture(wrong,42)) {
                require(!states[Short.toUnsignedInt(PortalIntegrationTest.blocks(terrain,reversed.request(0,0))[64*256])].is(Blocks.EMERALD_BLOCK),"fixture detects wrong structure step");
            }
            Files.copy(profile.file(),directory.resolve(WorldDefinitionFile.NAME));
            Files.writeString(Path.of("build/nether/generation-step-fixture.json"),world.toString());Files.writeString(Path.of("build/nether/generation-step-regions-path.txt"),directory.toAbsolutePath().toString());
            System.out.println("QA_EVT {\"event\":\"structure_feature_dependencies\",\"status\":\"pass\",\"context\":{\"chunks\":"+expected.size()+",\"regions\":"+regions.size()+"}}");
        }
    }
    static Holder<PlacedFeature> feature(RegistryAccess registry,String material,String previous,int x,int z,int y,int probe,boolean heightmap) {
        var root=JsonParser.parseString("""
            {"feature":{"type":"minecraft:single_block_pillar","direction":"up","chance_to_continue":1,
             "can_replace":{"type":"minecraft:height_range","min_inclusive":{"absolute":64},"max_inclusive":{"absolute":64}}},
             "placement":[{"type":"minecraft:height_range","height":{"absolute":64}},{"type":"minecraft:offset","x":0,"y":0,"z":0},
              {"type":"minecraft:block_predicate_filter","predicate":{"type":"minecraft:matching_blocks","blocks":["minecraft:air"]}}]}
            """).getAsJsonObject();var f=root.getAsJsonObject("feature");f.add("block",BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE,BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:"+material)).defaultBlockState()).getOrThrow());
        f.getAsJsonObject("can_replace").getAsJsonObject("min_inclusive").addProperty("absolute",y);f.getAsJsonObject("can_replace").getAsJsonObject("max_inclusive").addProperty("absolute",y);
        var p=root.getAsJsonArray("placement");p.get(0).getAsJsonObject().getAsJsonObject("height").addProperty("absolute",y);p.get(1).getAsJsonObject().addProperty("x",x);p.get(1).getAsJsonObject().addProperty("z",z);
        var predicate=p.get(2).getAsJsonObject().getAsJsonObject("predicate");var blocks=new JsonArray();blocks.add("minecraft:"+previous);predicate.add("blocks",blocks);predicate.add("offset",StructureProfile.ints(probe,heightmap?-1:0,0));
        if(heightmap){var first=p.get(1);p.set(1,JsonParser.parseString("{\"type\":\"minecraft:heightmap\",\"heightmap\":\"WORLD_SURFACE_WG\"}"));p.set(0,first);}
        return Holder.direct(PlacedFeature.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),root).getOrThrow());
    }
    static CompoundTag template(boolean high) {
        var tag=new CompoundTag();var size=new ListTag();for(int v:new int[]{2,2,2})size.add(IntTag.valueOf(v));tag.put("size",size);var palette=new ListTag();palette.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE,Blocks.OBSIDIAN.defaultBlockState()).getOrThrow());palette.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE,Blocks.CHEST.defaultBlockState()).getOrThrow());tag.put("palette",palette);
        var blocks=new ListTag();for(int i=0;i<2;i++){var block=new CompoundTag();var pos=new ListTag();for(int v:i==0?new int[]{0,0,0}:high?new int[]{0,1,0}:new int[]{1,0,0})pos.add(IntTag.valueOf(v));block.put("pos",pos);block.putInt("state",i);if(i==1){var nbt=new CompoundTag();nbt.putString("id","minecraft:chest");nbt.putString("LootTable","minecraft:chests/ruined_portal");block.put("nbt",nbt);}blocks.add(block);}tag.put("blocks",blocks);tag.put("entities",new ListTag());return tag;
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
