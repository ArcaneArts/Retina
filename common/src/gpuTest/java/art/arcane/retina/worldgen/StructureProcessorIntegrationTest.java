package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Compare native template processors to Minecraft for complete property-state families. */
final class StructureProcessorIntegrationTest {
    static void run(RegistryAccess registry, JsonObject source) throws Exception {
        latePaletteInputs();
        var cases=new ArrayList<BlockState>();
        for(var block:List.of(Blocks.STONE,Blocks.STONE_BRICKS,Blocks.CHISELED_STONE_BRICKS,Blocks.OBSIDIAN))
            for(int i=0;i<128;i++)cases.add(block.defaultBlockState());
        for(var block:List.of(Blocks.STONE_STAIRS,Blocks.STONE_BRICK_STAIRS,Blocks.MOSSY_STONE_BRICK_STAIRS,
                Blocks.COBBLESTONE_STAIRS,Blocks.PRISMARINE_STAIRS,Blocks.NETHER_BRICK_STAIRS,
                Blocks.STONE_SLAB,Blocks.STONE_BRICK_SLAB,Blocks.MOSSY_STONE_BRICK_SLAB,Blocks.COBBLESTONE_SLAB,
                Blocks.COBBLESTONE_WALL,Blocks.STONE_BRICK_WALL,Blocks.IRON_BARS))
            cases.addAll(block.getStateDefinition().getPossibleStates());
        cases.add(Blocks.CHEST.defaultBlockState());cases.add(Blocks.LAVA.defaultBlockState());cases.add(Blocks.GOLD_BLOCK.defaultBlockState());
        long[] seeds=new long[4];Arrays.fill(seeds,-1);
        for(long seed=0;Arrays.stream(seeds).anyMatch(v->v<0);seed++) {
            var random=new Random(mix(seed));random.nextInt(1);random.nextInt(1);int rotation=random.nextInt(4);if(seeds[rotation]<0)seeds[rotation]=seed;
        }
        int checks=0,profiles=0,changed=0,blockEntities=0;
        for(int mode=0;mode<8;mode++)for(var world:List.of(Blocks.AIR.defaultBlockState(),Blocks.LAVA.defaultBlockState(),Blocks.BEDROCK.defaultBlockState())) {
            float mossiness=mode==1||mode==6||mode==7?.35f:mode==2?1:0;
            var raw=new JsonArray();var processors=new ArrayList<StructureProcessor>();
            if(mode==7)add(raw,processors,"blackstone_replace",BlackstoneReplaceProcessor.INSTANCE);
            if(mode<=2||mode>=5) {
                var age=new JsonObject();age.addProperty("processor_type","minecraft:block_age");age.addProperty("mossiness",mossiness);raw.add(age);processors.add(new BlockAgeProcessor(mossiness));
            }
            if(mode>=5)add(raw,processors,"protected_blocks",new ProtectedBlockProcessor(registry.lookupOrThrow(Registries.BLOCK).getOrThrow(BlockTags.FEATURES_CANNOT_REPLACE)));
            if(mode==4||mode>=5)add(raw,processors,"lava_submerged_block",LavaSubmergedBlockProcessor.INSTANCE);
            if(mode==3||mode==5||mode==6)add(raw,processors,"blackstone_replace",BlackstoneReplaceProcessor.INSTANCE);
            var palette=new LinkedHashMap<BlockState,Integer>();
            for(var value:source.getAsJsonArray("materials")){var state=BlockState.CODEC.parse(JsonOps.INSTANCE,value).getOrThrow();palette.put(state,palette.size());}
            var biome=registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.NETHER_WASTES);
            var exporter=new StructureProfile(registry,List.of(biome),palette,StructureProfile.Context.NONE);
            exporter.template("retina:processor_fixture",template(cases),0);
            var structures=JsonParser.parseString("{\"definitions\":[{\"id\":\"retina:processor_fixture\",\"kind\":\"jigsaw\",\"biomes\":[0],\"pool\":\"retina:processor_fixture\",\"config\":{\"size\":0,\"start_height\":{\"absolute\":64},\"terrain_adaptation\":\"none\"}}],\"pools\":{\"retina:processor_fixture\":{\"fallback\":\"minecraft:empty\",\"entries\":[{\"parts\":[{\"template\":0,\"ignore_air\":false}],\"terrain_matching\":false,\"weight\":1}]}},\"sets\":[{\"id\":\"retina:processor_fixture\",\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":4096,\"separation\":4095,\"salt\":0},\"entries\":[{\"definition\":0,\"weight\":1}]}]}").getAsJsonObject();
            structures.add("templates",exporter.templates);
            var part=structures.getAsJsonObject("pools").getAsJsonObject("retina:processor_fixture").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject();part.add("processors",raw);
            StructureProfile.finishMaterials(structures,palette);exporter.finishProcessors(structures.get("pools"));
            var fixture=source.deepCopy();fixture.remove("registry_program");fixture.remove("lighting");fixture.remove("generation");fixture.remove("generation_bounds");
            fixture.add("structures",structures);fixture.add("decorations",new JsonArray());fixture.add("terrain_features",new JsonObject());fixture.add("ores",new JsonArray());fixture.add("cave_noises",new JsonArray());
            var biomes=new JsonArray();var b=JsonParser.parseString("{\"id\":\"minecraft:nether_wastes\",\"climate\":[0,0,0,0],\"terrain\":[0,0,0],\"top\":0,\"filler\":0,\"underwater\":0,\"flags\":0,\"decorations\":[]}");biomes.add(b);fixture.add("biomes",biomes);fixture.add("climate_targets",new JsonArray());
            var flat=new JsonObject();var layers=new JsonArray();for(int i=0;i<256;i++)layers.add(palette.get(world));flat.add("base_layers",layers);flat.add("deferred_layers",new JsonArray());flat.add("air_materials",StructureProfile.ints(0));fixture.add("flat",flat);
            DecorationProfile.materialFlags(fixture,palette);var materials=new JsonArray();palette.keySet().forEach(state->materials.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE,state).getOrThrow()));fixture.add("materials",materials);
            var states=palette.keySet().toArray(BlockState[]::new);var terrain=NativeTerrain.instance();int profile=terrain.registerProfile(fixture.toString());profiles++;
            WorldGenLevel level=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->switch(method.getName()) {
                case "getBlockState" -> world;
                case "getFluidState" -> world.getFluidState();
                case "getBlockEntity" -> null;
                case "getMinY" -> 0;
                case "getMaxY" -> 255;
                default -> {if(method.isDefault())yield InvocationHandler.invokeDefault(proxy,method,args);throw new UnsupportedOperationException(method.toString());}
            });
            for(var rotation:Rotation.values()) {
                long seed=seeds[rotation.ordinal()];var expected=new HashMap<ChunkPos,short[]>();var expectedEntities=new HashMap<ChunkPos,Integer>();
                var settings=new StructurePlaceSettings().setRotation(rotation);var origin=new BlockPos(0,64,0);
                for(int i=0;i<cases.size();i++) {
                    var local=new BlockPos(i%16,i/256,i/16%16);var pos=StructureTemplate.transform(local,Mirror.NONE,rotation,BlockPos.ZERO).offset(origin);
                    var input=cases.get(i);var info=new StructureTemplate.StructureBlockInfo(pos,input,null);
                    for(var processor:processors) {info=processor.processBlock(level,origin,origin,local,info,settings);if(info==null)break;}
                    var result=info==null?world:info.state().rotate(rotation);var chunk=ChunkPos.containing(pos);
                    var blocks=expected.computeIfAbsent(chunk,key->{var array=new short[65536];Arrays.fill(array,palette.get(world).shortValue());return array;});
                    blocks[pos.getY()*256+(pos.getZ()&15)*16+(pos.getX()&15)]=palette.get(result).shortValue();
                    if(info!=null&&input.is(Blocks.CHEST)&&result.getBlock() instanceof EntityBlock)expectedEntities.merge(chunk,1,Integer::sum);
                    if(!result.equals(input.rotate(rotation)))changed++;checks++;
                }
                for(var entry:expected.entrySet()) {
                    var at=entry.getKey();var request=new TerrainRequest(seed,at.x(),at.z(),0,256,64,48,.008f,profile);
                    try(var generated=terrain.generate(request)) {
                        var actual=generated.blocks().toArray(ValueLayout.JAVA_SHORT);int mismatch=Arrays.mismatch(entry.getValue(),actual);
                        require(mismatch<0,"processor mode="+mode+" world="+world+" rotation="+rotation+" chunk="+at+" index="+mismatch+(mismatch<0?"":" expected="+states[Short.toUnsignedInt(entry.getValue()[mismatch])]+" actual="+states[Short.toUnsignedInt(actual[mismatch])]));
                    }
                    int entities=terrain.structureData(request).getListOrEmpty("block_entities").size();require(entities==expectedEntities.getOrDefault(at,0),"lava and protected blocks preserve valid block entities only");blockEntities+=entities;
                }
            }
            if(mode==6&&world.is(Blocks.LAVA))Files.writeString(Path.of("build/nether/portal-processor-fixture.json"),fixture.toString());
        }
        require(changed>0&&blockEntities>0,"observable processor transitions and retained metadata");
        var report=new JsonObject();report.addProperty("profiles",profiles);report.addProperty("states_per_template",cases.size());report.addProperty("source_comparisons",checks);report.addProperty("changed_states",changed);report.addProperty("retained_block_entities",blockEntities);
        Files.writeString(Path.of("build/nether/portal-processors.json"),report.toString());System.out.println("QA_EVT {\"event\":\"nether_portal_processors\",\"status\":\"pass\",\"context\":"+report+"}");
    }
    static void latePaletteInputs() {
        var processors=JsonParser.parseString("[{\"processor_type\":\"minecraft:block_age\",\"mossiness\":0.35},{\"processor_type\":\"minecraft:lava_submerged_block\"},{\"processor_type\":\"minecraft:blackstone_replace\"}]");
        var palette=new LinkedHashMap<BlockState,Integer>();palette.put(Blocks.AIR.defaultBlockState(),0);
        StructureProcessorProfile.finish(processors,palette);
        for(var block:List.of(Blocks.STONE_STAIRS,Blocks.DARK_OAK_STAIRS,Blocks.PRISMARINE_WALL))
            for(var state:block.getStateDefinition().getPossibleStates())palette.computeIfAbsent(state,s->palette.size());
        int size=palette.size();StructureProcessorProfile.finish(processors,palette);
        require(palette.size()==size,"late decoration inputs need no new processor output states after lighting export");
        require(processors.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("retina_age").getAsJsonArray("kinds").size()==size,"age table includes late material IDs");
        require(processors.getAsJsonArray().get(2).getAsJsonObject().getAsJsonArray("retina_blackstone").size()==size,"blackstone table includes late material IDs");
    }
    static void add(JsonArray raw,List<StructureProcessor> processors,String type,StructureProcessor processor) {
        var json=new JsonObject();json.addProperty("processor_type","minecraft:"+type);if(type.equals("protected_blocks"))json.addProperty("value","minecraft:features_cannot_replace");raw.add(json);processors.add(processor);
    }
    static CompoundTag template(List<BlockState> cases) {
        var raw=new CompoundTag();var palette=new LinkedHashMap<BlockState,Integer>();var blocks=new ListTag();
        for(int i=0;i<cases.size();i++) {
            var state=cases.get(i);var b=new CompoundTag();b.put("pos",StructureProfile.list(i%16,i/256,i/16%16));b.putInt("state",palette.computeIfAbsent(state,s->palette.size()));
            if(state.is(Blocks.CHEST)){var tag=new CompoundTag();tag.putString("id","minecraft:chest");tag.putString("LootTable","minecraft:chests/ruined_portal");b.put("nbt",tag);}blocks.add(b);
        }
        var states=new ListTag();palette.keySet().forEach(state->states.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE,state).getOrThrow()));
        raw.put("palette",states);raw.put("blocks",blocks);raw.put("size",StructureProfile.list(16,(cases.size()+255)/256,16));raw.put("entities",new ListTag());return raw;
    }
    static long mix(long v){v=(v^v>>>30)*0xbf58476d1ce4e5b9L;v=(v^v>>>27)*0x94d049bb133111ebL;return v^v>>>31;}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
