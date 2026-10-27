package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real datapack reload changes generation, while saved A remains authoritative under live B. */
public final class WorldDefinitionResourceAuthorityTest {
    private static final long SEED=123456789L;
    private static final String FLAT="minecraft:the_end";
    private static final Identifier PIECE=Identifier.parse("definition_test:piece");
    private static final List<ChunkPos> CHUNKS=List.of(new ChunkPos(-1,-1),new ChunkPos(0,0),new ChunkPos(1,1));
    private record Captured(List<String> biomes,List<CompoundTag> chunks,int diamonds,int gold) {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();NativeRegistryLoadingTest.registerCodecs();
        if(BuiltInRegistries.STRUCTURE_PIECE.getValue(Identifier.parse("retina:template"))==null) {
            FortressAssemblyTest.registerPiece();
        }
        Path output=Path.of(args[0]);Files.createDirectories(output);
        Path work=Files.createTempDirectory(output,"live-resources-");
        JsonObject noise,structure,pool;
        try(var resources=resources(null)) {
            noise=json(resources,"minecraft:worldgen/noise/temperature.json");
            structure=json(resources,"minecraft:worldgen/structure/village_plains.json");
            pool=json(resources,"minecraft:worldgen/template_pool/village/plains/town_centers.json");
        }
        writePack(work.resolve("pack-a"),noise,structure,pool,false);
        writePack(work.resolve("pack-b"),noise,structure,pool,true);
        Path savedA;Captured expected;
        try(var resources=resources(work.resolve("pack-a"))) {
            var registry=load(resources);
            try(var storage=LevelStorageSource.createDefault(work.resolve("storage-a")).createAccess("templates")) {
                var templates=new StructureTemplateManager(resources,storage,DataFixers.getDataFixer(),registry.lookupOrThrow(Registries.BLOCK));
                savedA=compile(work.resolve("world-a"),registry,resources,templates);
                expected=capture(savedA,registry,PalettedContainerFactory.create(registry));
                require(expected.diamonds()>0 && expected.gold()==0,"actual A template placed diamond blocks");
                require(expected.chunks().stream().mapToInt(tag->tag.getListOrEmpty("block_entities").size()).sum()>0,"actual A template exported block-entity NBT");
            }
        }
        // No A registry, template manager, selected generator or compiler palette escapes that scope.
        try(var resources=resources(work.resolve("pack-b"))) {
            var registry=load(resources);var factory=PalettedContainerFactory.create(registry);
            try(var storage=LevelStorageSource.createDefault(work.resolve("storage-b")).createAccess("templates")) {
                var templates=new StructureTemplateManager(resources,storage,DataFixers.getDataFixer(),registry.lookupOrThrow(Registries.BLOCK));
                var actualTemplate=templates.get(PIECE).orElseThrow().save(new CompoundTag());
                require(BlockState.CODEC.parse(NbtOps.INSTANCE,actualTemplate.getListOrEmpty("palette").get(0)).getOrThrow().is(Blocks.GOLD_BLOCK),"live B template manager sees changed resource");
                Path savedB=compile(work.resolve("world-b"),registry,resources,templates);
                var fresh=capture(savedB,registry,factory);
                require(fresh.gold()>0 && fresh.diamonds()==0,"fresh B compilation uses changed live template");
                int noiseChanges=0;
                for(int i=0;i<expected.biomes().size();i++)if(!expected.biomes().get(i).equals(fresh.biomes().get(i)))noiseChanges++;
                require(noiseChanges>0,"fresh B compilation changes canonical biome output from live noise");
                var identities=new HolderLookup.Provider() {
                    @Override public java.util.stream.Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() {throw new AssertionError("Live generation registry enumeration");}
                    @Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                        if(!key.equals(Registries.BIOME) && !key.equals(Registries.STRUCTURE))throw new AssertionError("Live generation lookup: "+key);
                        return registry.lookup(key).map(value->value);
                    }
                };
                byte[] before=Files.readAllBytes(savedA);
                String previous=System.setProperty("retina.qa.definition.failCompiler","true");
                try {
                    var reopened=capture(savedA,identities,factory);
                    require(expected.equals(reopened),"saved A generation remains exact while B resources and registries are live");
                    require(Arrays.equals(before,Files.readAllBytes(savedA)),"reopen preserves saved definition bytes");
                    install(savedA,identities,registry,resources,templates,factory,expected.chunks().get(1));
                } finally {
                    if(previous==null)System.clearProperty("retina.qa.definition.failCompiler");
                    else System.setProperty("retina.qa.definition.failCompiler",previous);
                }
                var receipt=new JsonObject();receipt.addProperty("event","live_resource_definition_authority");receipt.addProperty("status","pass");
                receipt.addProperty("canonical_biome_probes",expected.biomes().size());receipt.addProperty("noise_changed_probes",noiseChanges);
                receipt.addProperty("decoded_chunks",expected.chunks().size());receipt.addProperty("block_entities",expected.chunks().stream().mapToInt(tag->tag.getListOrEmpty("block_entities").size()).sum());receipt.addProperty("saved_diamond_blocks",expected.diamonds());receipt.addProperty("fresh_gold_blocks",fresh.gold());
                receipt.addProperty("saved_a",savedA.toString());receipt.addProperty("fresh_b",savedB.toString());
                Files.writeString(work.resolve("receipt.json"),new GsonBuilder().setPrettyPrinting().create().toJson(receipt));
                System.out.println("QA_EVT "+receipt);
            }
        }
    }

    private static MultiPackResourceManager resources(Path custom) {
        var packs=new ArrayList<PackResources>();packs.add(ServerPacksSource.createVanillaPackSource().fullResources());
        if(custom!=null)packs.add(new PathPackResources(new PackLocationInfo("live-definition-pack",Component.literal("Live definition fixture"),PackSource.DEFAULT,Optional.empty()),custom));
        return new MultiPackResourceManager(PackType.SERVER_DATA,packs);
    }
    private static JsonObject json(ResourceManager resources,String key) throws Exception {
        try(var reader=new InputStreamReader(resources.getResourceOrThrow(Identifier.parse(key)).open(),StandardCharsets.UTF_8)) {return JsonParser.parseReader(reader).getAsJsonObject();}
    }
    private static RegistryAccess.Frozen load(ResourceManager resources) {
        var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
        return RegistryIntegrationFixtures.load(resources);
    }
    private static Path compile(Path root,RegistryAccess registry,ResourceManager resources,StructureTemplateManager templates) throws Exception {
        var normal=registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions();
        var stems=new LinkedHashMap<>(normal);
        var settings=JsonParser.parseString("""
                {"layers":[{"height":1,"block":"minecraft:bedrock"},{"height":2,"block":"minecraft:dirt"},{"height":1,"block":"minecraft:grass_block"}],
                 "biome":"minecraft:plains","features":false,"lakes":false,"structure_overrides":["definition_test:frozen"]}
                """);
        var flat=new FlatLevelSource(FlatLevelGeneratorSettings.CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),settings).getOrThrow());
        stems.put(LevelStem.END,new LevelStem(normal.get(LevelStem.OVERWORLD).type(),flat));
        var document=WorldDefinitionCompiler.compile(registry,new WorldOptions(SEED,true,false),stems,StructureProfile.Context.of(templates,true),
                resources.listPacks().map(PackResources::packId).toList(),"Live resource authority fixture");
        return WorldDefinitionFile.writeNew(root,document);
    }
    private static Captured capture(Path file,HolderLookup.Provider identities,PalettedContainerFactory factory) throws Exception {
        var biomes=new ArrayList<String>();var chunks=new ArrayList<CompoundTag>();int diamonds=0,gold=0;
        try(var world=open(file)) {
            var noise=RetinaChunkGenerator.fromDefinition(world.dimension(Level.OVERWORLD),identities);
            try {
                noise.bindWorld(factory);
                for(int z=-47;z<=81;z+=32)for(int x=-62;x<=62;x+=31) {
                    var values=NativeTerrain.instance().sampleColumns(noise.request(x,z));
                    for(int i=0;i<256;i+=17)biomes.add(noise.profile().biomes().get(values.biome(i)).unwrapKey().orElseThrow().identifier().toString());
                }
            } finally {noise.closePreviews();}
            var flat=RetinaChunkGenerator.fromDefinition(world.dimension(FLAT),identities);
            try {
                flat.bindWorld(factory);
                for(var at:CHUNKS) {
                    var tag=flat.savedDefinition().chunk(at.x(),at.z());chunks.add(tag);
                    diamonds+=count(tag,Blocks.DIAMOND_BLOCK);gold+=count(tag,Blocks.GOLD_BLOCK);
                }
            } finally {flat.closePreviews();}
        }
        return new Captured(List.copyOf(biomes),List.copyOf(chunks),diamonds,gold);
    }
    private static WorldDefinitionContext open(Path file) {
        var nativeWorld=NativeWorldDefinition.open(file);
        try {
            for(String key:List.of("minecraft:overworld","minecraft:the_nether",FLAT))
                nativeWorld.bindDimension(key,2,NativeWorldDefinition.ProgramExecution.INTERPRETER);
            return new WorldDefinitionContext(nativeWorld);
        } catch(Throwable error) {nativeWorld.close();throw error;}
    }
    private static int count(CompoundTag chunk,Block wanted) {
        var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());int found=0;
        for(var item:chunk.getListOrEmpty("sections")) {
            var section=(CompoundTag)item;if(!section.contains("block_states"))continue;var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(blocks.get(x,y,z).is(wanted))found++;
        }
        return found;
    }
    private static void install(Path file,HolderLookup.Provider identities,RegistryAccess registry,ResourceManager resources,
                                StructureTemplateManager templates,PalettedContainerFactory factory,CompoundTag expected) throws Exception {
        try(var world=open(file)) {
            var saved=world.dimension(FLAT);var type=registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions().dimensions().get(LevelStem.OVERWORLD).type().value();
            saved.bindDimensionType(type);var generator=RetinaChunkGenerator.fromDefinition(saved,identities);
            try {
                generator.bindWorld(factory);var bounds=LevelHeightAccessor.create(type.minY(),type.height());
                var random=RandomState.create(registry.lookupOrThrow(Registries.NOISE),SEED+37,registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value());
                var proto=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,bounds,factory,null);
                generator.createBiomes(random,Blender.empty(),null,proto).join();
                generator.buildTerrain(proto,Blender.empty(),random,null,null,null,Set.copyOf(generator.profile().biomes())).join();
                var codec=PalettedContainer.codecRW(BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
                for(var item:expected.getListOrEmpty("sections")) {
                    var section=(CompoundTag)item;if(!section.contains("block_states"))continue;int sectionY=section.getByteOr("Y",(byte)0)*16;
                    var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                    for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                        require(proto.getBlockState(new BlockPos(x,sectionY+y,z)).equals(blocks.get(x,y,z)),"platform installs frozen A blocks with live B random state");
                }
                generator.installDecorationData(proto,expected,new StructurePieceSerializationContext(resources,registry,templates),registry);
                require(!proto.getAllStarts().isEmpty(),"frozen structure starts install against live B identities");
                for(var item:expected.getListOrEmpty("block_entities")) {
                    var tag=(CompoundTag)item;var at=new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));
                    require(tag.equals(proto.getBlockEntityNbt(at)),"frozen block-entity NBT installs through live B context");
                }
            } finally {generator.closePreviews();}
        }
    }
    private static void writePack(Path root,JsonObject noise,JsonObject structure,JsonObject pool,boolean changed) throws Exception {
        var actualNoise=noise.deepCopy();if(changed)actualNoise.addProperty("base_amplitude",20.0);
        writeJson(root,"data/minecraft/worldgen/noise/temperature.json",actualNoise);
        var actualStructure=structure.deepCopy();actualStructure.add("biomes",JsonParser.parseString("[\"minecraft:plains\"]"));
        actualStructure.addProperty("start_pool","definition_test:root");actualStructure.addProperty("size",0);actualStructure.addProperty("terrain_adaptation","none");
        actualStructure.add("start_height",JsonParser.parseString("{\"absolute\":80}"));actualStructure.remove("project_start_to_heightmap");
        writeJson(root,"data/definition_test/worldgen/structure/frozen.json",actualStructure);
        var element=pool.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("element").deepCopy();
        element.addProperty("element_type","minecraft:single_pool_element");element.addProperty("location",PIECE.toString());element.addProperty("processors","minecraft:empty");element.addProperty("projection","rigid");
        var entry=new JsonObject();entry.addProperty("weight",1);entry.add("element",element);var elements=new JsonArray();elements.add(entry);
        var actualPool=new JsonObject();actualPool.addProperty("fallback","minecraft:empty");actualPool.add("elements",elements);
        writeJson(root,"data/definition_test/worldgen/template_pool/root.json",actualPool);
        writeJson(root,"data/definition_test/worldgen/structure_set/frozen.json",JsonParser.parseString("{\"structures\":[{\"structure\":\"definition_test:frozen\",\"weight\":1}],\"placement\":{\"type\":\"minecraft:random_spread\",\"spacing\":1,\"separation\":0,\"salt\":73}}"));
        var template=new CompoundTag();template.putInt("DataVersion",SharedConstants.getCurrentVersion().dataVersion().version());template.put("size",ints(3,1,1));
        var palette=new ListTag();palette.add(NbtUtils.writeBlockState((changed?Blocks.GOLD_BLOCK:Blocks.DIAMOND_BLOCK).defaultBlockState()));
        palette.add(NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState()));template.put("palette",palette);
        var blocks=new ListTag();for(int x=0;x<3;x++) {var block=new CompoundTag();block.put("pos",ints(x,0,0));block.putInt("state",x==1?1:0);
            if(x==1){var nbt=new CompoundTag();nbt.putString("id","minecraft:chest");nbt.putString("retina_marker",changed?"B":"A");nbt.putLong("LootTableSeed",Long.MIN_VALUE+17);block.put("nbt",nbt);}blocks.add(block);}
        template.put("blocks",blocks);template.put("entities",new ListTag());var file=root.resolve("data/definition_test/structure/piece.nbt");Files.createDirectories(file.getParent());NbtIo.writeCompressed(template,file);
    }
    private static ListTag ints(int... values) {var out=new ListTag();for(int value:values)out.add(IntTag.valueOf(value));return out;}
    private static void writeJson(Path root,String name,JsonElement value) throws IOException {var file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,value.toString());}
    private static void require(boolean test,String message) {if(!test)throw new AssertionError(message);}
}
