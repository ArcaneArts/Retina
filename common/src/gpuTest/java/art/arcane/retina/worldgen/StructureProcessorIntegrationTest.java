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
import java.util.concurrent.*;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;

/** Compare native template processors to Minecraft for complete property-state families. */
final class StructureProcessorIntegrationTest {
    static void run(RegistryAccess registry, JsonObject source) throws Exception {
        latePaletteInputs();
        var cases=new ArrayList<BlockState>();
        for(var block:List.of(Blocks.STONE,Blocks.STONE_BRICKS,Blocks.CHISELED_STONE_BRICKS,Blocks.OBSIDIAN,Blocks.NETHERRACK,Blocks.GOLD_BLOCK))
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
        int checks=0,profiles=0,changed=0,blockEntities=0,transformedChecks=0,boundsChecks=0;
        for(int style=0;style<4;style++)for(int mode=0;mode<8;mode++)for(var world:List.of(Blocks.AIR.defaultBlockState(),Blocks.LAVA.defaultBlockState(),Blocks.BEDROCK.defaultBlockState())) {
            if(style>0 && mode!=6 && (mode!=0 && mode!=3 || !world.isAir()))continue;
            var mirror=style==0?Mirror.NONE:Mirror.values()[style-1];
            var pivot=style==0?BlockPos.ZERO:new BlockPos(5,7,-3);
            float mossiness=mode==1||mode==6||mode==7?.35f:mode==2?1:0;
            var raw=new JsonArray();var processors=new ArrayList<StructureProcessor>();
            if(style>0 && mode==6) {
                var rules=List.of(
                        new ProcessorRule(new RandomBlockMatchTest(Blocks.GOLD_BLOCK,.3f),AlwaysTrueTest.INSTANCE,Blocks.AIR.defaultBlockState()),
                        new ProcessorRule(new RandomBlockMatchTest(Blocks.LAVA,.2f),AlwaysTrueTest.INSTANCE,Blocks.MAGMA_BLOCK.defaultBlockState()),
                        new ProcessorRule(new RandomBlockMatchTest(Blocks.NETHERRACK,.07f),AlwaysTrueTest.INSTANCE,Blocks.MAGMA_BLOCK.defaultBlockState()),
                        new ProcessorRule(new BlockStateMatchTest(Blocks.STONE_STAIRS.defaultBlockState()),new BlockStateMatchTest(world),Blocks.STONE_BRICK_STAIRS.defaultBlockState()));
                for(int i=0;i<2;i++) {
                    var processor=new RuleProcessor(rules);var value=RuleProcessor.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE,processor).getOrThrow().getAsJsonObject();
                    value.addProperty("processor_type","minecraft:rule");raw.add(value);processors.add(processor);
                }
            }
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
            if(style>0)StructureTemplateProfile.configure(exporter.templates.get(0).getAsJsonObject(),mirror,pivot);
            exporter.registerProcessorStates(raw);
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
                var settings=new StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRotationPivot(pivot);var origin=new BlockPos(0,64,0);
                for(int i=0;i<cases.size();i++) {
                    var local=new BlockPos(i%16,i/256,i/16%16);var pos=StructureTemplate.transform(local,mirror,rotation,pivot).offset(origin);
                    var input=cases.get(i);var info=new StructureTemplate.StructureBlockInfo(pos,input,null);
                    for(var processor:processors) {info=processor.processBlock(level,origin,origin,local,info,settings);if(info==null)break;}
                    var result=info==null?world:info.state().mirror(mirror).rotate(rotation);var chunk=ChunkPos.containing(pos);
                    var blocks=expected.computeIfAbsent(chunk,key->{var array=new short[65536];Arrays.fill(array,palette.get(world).shortValue());return array;});
                    blocks[pos.getY()*256+(pos.getZ()&15)*16+(pos.getX()&15)]=palette.get(result).shortValue();
                    if(info!=null&&input.is(Blocks.CHEST)&&result.getBlock() instanceof EntityBlock)expectedEntities.merge(chunk,1,Integer::sum);
                    if(!result.equals(input.mirror(mirror).rotate(rotation)))changed++;checks++;if(style>0)transformedChecks++;
                }
                var sourceTemplate=new StructureTemplate();sourceTemplate.load(registry.lookupOrThrow(Registries.BLOCK),template(cases));
                var box=sourceTemplate.getBoundingBox(settings,origin);
                var start=terrain.structureStarts(new TerrainRequest(seed,0,0,0,256,64,48,.008f,profile)).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("retina:processor_fixture");
                require(start.getListOrEmpty("Children").getCompound(0).orElseThrow().read("BB",net.minecraft.world.level.levelgen.structure.BoundingBox.CODEC).orElseThrow().equals(box),"actual source template bounding box");boundsChecks++;
                for(int z=Math.floorDiv(box.minZ(),16);z<=Math.floorDiv(box.maxZ(),16);z++)for(int x=Math.floorDiv(box.minX(),16);x<=Math.floorDiv(box.maxX(),16);x++)
                    expected.computeIfAbsent(new ChunkPos(x,z),key->{var array=new short[65536];Arrays.fill(array,palette.get(world).shortValue());return array;});
                for(var entry:expected.entrySet()) {
                    var at=entry.getKey();var request=new TerrainRequest(seed,at.x(),at.z(),0,256,64,48,.008f,profile);
                    try(var generated=terrain.generate(request)) {
                        var actual=generated.blocks().toArray(ValueLayout.JAVA_SHORT);int mismatch=Arrays.mismatch(entry.getValue(),actual);
                        require(mismatch<0,"processor style="+style+" mode="+mode+" world="+world+" rotation="+rotation+" chunk="+at+" index="+mismatch+(mismatch<0?"":" expected="+states[Short.toUnsignedInt(entry.getValue()[mismatch])]+" actual="+states[Short.toUnsignedInt(actual[mismatch])]));
                    }
                    int entities=terrain.structureData(request).getListOrEmpty("block_entities").size();require(entities==expectedEntities.getOrDefault(at,0),"lava and protected blocks preserve valid block entities only");blockEntities+=entities;
                }
                if(style==3&&mode==6&&world.isAir()&&rotation==Rotation.NONE)saveFixture(terrain,profile,seed,states,expected);
            }
            if(mode==6&&world.is(Blocks.LAVA))Files.writeString(Path.of(style==0?"build/nether/portal-processor-fixture.json":"build/nether/portal-transform-fixture-"+style+".json"),fixture.toString());
        }
        require(changed>0&&blockEntities>0,"observable processor transitions and retained metadata");
        var report=new JsonObject();report.addProperty("profiles",profiles);report.addProperty("states_per_template",cases.size());report.addProperty("source_comparisons",checks);report.addProperty("changed_states",changed);report.addProperty("retained_block_entities",blockEntities);report.addProperty("transformed_source_comparisons",transformedChecks);report.addProperty("source_bounds_comparisons",boundsChecks);
        Files.writeString(Path.of("build/nether/portal-processors.json"),report.toString());System.out.println("QA_EVT {\"event\":\"nether_portal_processors\",\"status\":\"pass\",\"context\":"+report+"}");
    }
    static void saveFixture(NativeTerrain terrain,int profile,long seed,BlockState[] states,Map<ChunkPos,short[]> expected) throws Exception {
        try(var workers=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<?>>();
            for(var e:expected.entrySet())jobs.add(workers.submit(()->{
                var request=new TerrainRequest(seed,e.getKey().x(),e.getKey().z(),0,256,64,48,.008f,profile);
                try(var chunk=terrain.generate(request)){require(Arrays.equals(e.getValue(),chunk.blocks().toArray(ValueLayout.JAVA_SHORT)),"concurrent transformed template replay");}
            }));
            for(var job:jobs)job.get();
        }
        var directory=Files.createTempDirectory(Path.of("build/nether"),"portal-transform-regions-");
        Files.writeString(Path.of("build/nether/portal-transform-regions-path.txt"),directory.toAbsolutePath().toString());
        var regions=new HashSet<ChunkPos>();expected.keySet().forEach(p->regions.add(new ChunkPos(p.getRegionX()*32,p.getRegionZ()*32)));
        for(var p:regions)terrain.generateRegion(new TerrainRequest(seed,p.x(),p.z(),0,256,64,48,.008f,profile),directory.resolve("r."+p.getRegionX()+"."+p.getRegionZ()+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:nether_wastes");
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-portal-transforms",Level.NETHER,"chunk"),directory,false)) {
            for(var e:expected.entrySet()) {
                var chunk=storage.read(e.getKey());require(chunk!=null,"saved transformed template chunk");
                var metadata=terrain.structureData(new TerrainRequest(seed,e.getKey().x(),e.getKey().z(),0,256,64,48,.008f,profile));
                require(chunk.getCompoundOrEmpty("structures").equals(metadata.getCompoundOrEmpty("structures")),"MCA transformed starts and references");
                require(chunk.getListOrEmpty("block_entities").equals(metadata.getListOrEmpty("block_entities")),"MCA transformed block entities");
                for(var section:McaTestSections.terrain(chunk,0,256)) {
                    int y=section.getIntOr("Y",0)*16;var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for(int yy=0;yy<16;yy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                        require(blocks.get(x,yy,z).equals(states[Short.toUnsignedInt(e.getValue()[(y+yy)*256+z*16+x])]),"transformed template MCA voxel parity");
                }
            }
        }
        var report=new JsonObject();report.addProperty("seed",seed);report.addProperty("chunks",expected.size());report.addProperty("regions",regions.size());report.addProperty("compared_voxels",expected.size()*65536);
        Files.writeString(Path.of("build/nether/portal-transform-mca.json"),report.toString());
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
        var structures=JsonParser.parseString("{\"templates\":[{}],\"pools\":{}}").getAsJsonObject();
        StructureTemplateProfile.configure(structures.getAsJsonArray("templates").get(0).getAsJsonObject(),Mirror.FRONT_BACK,new BlockPos(5,0,-3));
        var tag=JsonParser.parseString("{\"predicate_type\":\"minecraft:tag_match\",\"tag\":\"minecraft:stairs\"}").getAsJsonObject();
        structures.getAsJsonObject("pools").add("processor_outputs",processors);structures.getAsJsonObject("pools").add("tag_fixture",tag);
        var late=Blocks.OAK_STAIRS.defaultBlockState();palette.computeIfAbsent(late,k->palette.size());
        int before=palette.size();StructureProfile.finishMaterials(structures,palette);
        require(palette.size()>before,"transform closure includes late directional inputs");
        int closed=palette.size();StructureProfile.finishMaterials(structures,palette);require(palette.size()==closed,"transform and processor outputs stay closed at final refresh");
        var transform=structures.getAsJsonArray("templates").get(0).getAsJsonObject().getAsJsonObject("transform").getAsJsonArray("materials");
        require(transform.size()==closed,"final transform table covers late material IDs");
        for(var state:palette.keySet())for(var rotation:Rotation.values()) {
            int target=palette.get(state.mirror(Mirror.FRONT_BACK).rotate(rotation));
            require(transform.get(palette.get(state)).getAsJsonArray().get(rotation.ordinal()).getAsInt()==target,"actual source late state transform");
            if(state.is(BlockTags.STAIRS))require(tag.getAsJsonArray("retina_matching").contains(new JsonPrimitive(palette.get(state))),"late transform states in source tag predicates");
        }

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
