package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real registered pools and templates; forced placement isolates geometry from rare biome selection. */
public final class NativeStructureIntegrationTest {
    private static final long SEED=123456789L;
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var legacy = new CompoundTag(); legacy.putInt("DataVersion", 3120);
        var palette = new ListTag(); var log = new CompoundTag(); log.putString("Name", "minecraft:oak_log");
        var properties = new CompoundTag(); properties.putString("axis", "x"); log.put("Properties", properties); palette.add(log);
        legacy.put("palette", palette); legacy.put("blocks", new ListTag()); legacy.put("entities", new ListTag());
        var size = new ListTag(); size.add(IntTag.valueOf(1)); size.add(IntTag.valueOf(1)); size.add(IntTag.valueOf(1)); legacy.put("size", size);
        var upgraded = StructureProfile.upgradeTemplate(legacy);
        require(net.minecraft.world.level.block.state.BlockState.CODEC.parse(NbtOps.INSTANCE, upgraded.getListOrEmpty("palette").get(0)).getOrThrow()
                .equals(Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X)), "legacy structure palettes migrate through Minecraft's data fixer with properties intact");
        var frozen=MappedRegistry.class.getDeclaredField("frozen");frozen.setAccessible(true);frozen.setBoolean(BuiltInRegistries.STRUCTURE_PIECE,false);
        RetinaStructurePiece.register();var bind=Holder.Reference.class.getDeclaredMethod("bindValue",Object.class);bind.setAccessible(true);bind.invoke(BuiltInRegistries.STRUCTURE_PIECE.get(Identifier.parse("retina:template")).orElseThrow(),RetinaStructurePiece.TYPE);frozen.setBoolean(BuiltInRegistries.STRUCTURE_PIECE,true);
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            var builtin=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,builtin).forEach(Registry.PendingTags::apply);
            var loaded=RegistryDataLoader.load(resources,builtin.listRegistries().toList(),RegistryDataLoader.WORLD_REGISTRIES,Runnable::run).join();
            var registry=new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtin.registries(),loaded.registries())).freeze();
            var biomes=registry.lookupOrThrow(Registries.BIOME).listElements().map(b->(Holder<Biome>)b).toList();
            var profile=BiomeTerrainProfile.load(registry,new RetinaBiomeSource(biomes,256,.55f),-64,384,SEED,
                    registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value(),StructureProfile.Context.of(resources));
            Files.writeString(Path.of("build/structure-profile.json"),profile.json());
            var data=JsonParser.parseString(profile.json()).getAsJsonObject();var structures=data.getAsJsonObject("structures");
            require(structures.getAsJsonArray("definitions").size()>=10,"villages, bastions and standalone structures exported");
            require(structures.getAsJsonArray("templates").size()>100,"authentic template library exported");
            var codec=PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
            var context=new StructurePieceSerializationContext(resources,registry,null);
            event("registered_structure_export","\"definitions\":"+structures.getAsJsonArray("definitions").size()+",\"templates\":"+structures.getAsJsonArray("templates").size()+",\"materials\":"+profile.materials().length);
            var nativeTerrain=NativeTerrain.instance();
            var stateGenerator=new RetinaChunkGenerator(new RetinaBiomeSource(biomes,256,.55f),-64,384,64,48,.008f,"mca");
            var structureState=stateGenerator.createState(registry.lookupOrThrow(Registries.STRUCTURE_SET),net.minecraft.world.level.levelgen.RandomState.create(registry.lookupOrThrow(Registries.NOISE),SEED,registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()),SEED);
            require(structureState.possibleStructureSets().stream().noneMatch(s->s.value().placement() instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement),"unsupported stronghold searches are not scheduled");
            require(structureState.possibleStructureSets().stream().anyMatch(s->s.unwrapKey().orElseThrow().identifier().toString().equals("minecraft:villages")),"village placement remains available for locate");
            event("native_structure_state","\"sets\":"+structureState.possibleStructureSets().size());
            villageTerrainChecks(data,profile,nativeTerrain);
            villageGrassChecks(data,profile,nativeTerrain);
            for(String name:List.of("minecraft:village_plains","minecraft:pillager_outpost")) {
                var fixture=force(data,name);var setName=name.contains("village")?"minecraft:villages":"minecraft:pillager_outposts";
                var placement=structures.getAsJsonArray("sets").asList().stream().map(JsonElement::getAsJsonObject).filter(o->o.get("id").getAsString().equals(setName)).findFirst().orElseThrow().getAsJsonObject("placement").deepCopy();
                placement.remove("exclusion_zone");fixture.getAsJsonObject("structures").getAsJsonArray("sets").get(0).getAsJsonObject().add("placement",placement);
                var actual=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.parse(registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),placement).getOrThrow();
                int id=nativeTerrain.registerProfile(fixture.toString());int checked=0;
                for(int[] at:List.of(new int[]{-129,33},new int[]{-33,-33},new int[]{0,0},new int[]{32,64},new int[]{12345,-98765},new int[]{-600,600},new int[]{512,-400},new int[]{77,99})) {
                    var candidate=actual.getPotentialStructureChunk(SEED,at[0],at[1]);
                    var starts=nativeTerrain.structureStarts(request(id,candidate.x(),candidate.z())).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                    require(starts.contains(name)==actual.applyAdditionalChunkRestrictions(candidate.x(),candidate.z(),SEED),"native placement matches the game's random spread/frequency at "+candidate);checked++;
                }
                event("minecraft_structure_placement_parity","\"structure\":\""+name+"\",\"cases\":"+checked);
            }
            for(String name:List.of("minecraft:village_plains","minecraft:bastion_remnant","minecraft:trial_chambers","minecraft:desert_pyramid","minecraft:jungle_pyramid","minecraft:swamp_hut")) {
                var modified=force(data,name);int id=nativeTerrain.registerProfile(modified.toString());var request=request(id,0,0);
                long chunksBefore=nativeTerrain.timings(id).chunks();
                int selected=nativeTerrain.queryStructureStarts(request,new int[]{0,0,0})[0];
                require(selected>=0 && modified.getAsJsonObject("structures").getAsJsonArray("definitions").get(selected).getAsJsonObject().get("id").getAsString().equals(name),"cold query selects exact production start for "+name);
                require(nativeTerrain.timings(id).chunks()==chunksBefore,"structure queries never assemble chunks");
                var starts=nativeTerrain.structureStarts(request).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                require(starts.contains(name),"native start for "+name+": "+starts.keySet());
                var start=StructureStart.loadStaticStart(context,starts.getCompoundOrEmpty(name),SEED);
                require(start!=null && start.isValid(),"Minecraft decodes the native piece bounds for "+name);
                int pieces=starts.getCompoundOrEmpty(name).getListOrEmpty("Children").size();
                if(name.contains("village"))require(houseCount(starts.getCompoundOrEmpty(name))>=3,"village contains full houses, not just streets and stalls");
                require(!name.contains("village") && !name.contains("bastion") && !name.contains("trial") || pieces>8,"multipart assembly for "+name+": "+pieces);
                int chestCount=0,entityCount=0,spawnerCount=0,edited=0;var chunks=new LinkedHashMap<ChunkPos,short[]>();var tags=new LinkedHashMap<ChunkPos,CompoundTag>();
                var bounds=start.getBoundingBox();
                for(int z=Math.floorDiv(bounds.minZ(),16);z<=Math.floorDiv(bounds.maxZ(),16);z++) for(int x=Math.floorDiv(bounds.minX(),16);x<=Math.floorDiv(bounds.maxX(),16);x++) {
                    var pos=new ChunkPos(x,z);var r=request(id,x,z);
                    try(var generated=nativeTerrain.generate(r)) {var bytes=generated.blocks().toArray(ValueLayout.JAVA_SHORT);chunks.put(pos,bytes);
                        for(short block:bytes){var state=profile.materials()[Short.toUnsignedInt(block)];require(!state.is(Blocks.JIGSAW) && !state.is(Blocks.STRUCTURE_BLOCK),"editor blocks were replaced");}
                    }
                    var tag=nativeTerrain.structureData(r);tags.put(pos,tag);
                    for(var v:tag.getListOrEmpty("block_entities")) {var be=(CompoundTag)v;String type=be.getStringOr("id","");
                        require(!type.isEmpty(),"block entity retains its registered type");
                        require(Math.floorDiv(be.getIntOr("x",Integer.MAX_VALUE),16)==x && Math.floorDiv(be.getIntOr("z",Integer.MAX_VALUE),16)==z,"block entity belongs to its chunk");
                        if(be.contains("LootTable"))chestCount++;
                        if(type.contains("spawner") || type.contains("vault"))spawnerCount++;
                    }
                    entityCount+=tag.getListOrEmpty("entities").size();
                    if(tag.getCompoundOrEmpty("structures").getCompoundOrEmpty("References").contains(name))edited++;
                }
                if(name.contains("pyramid"))require(chestCount>=2,"captured temples preserve loot chests");
                if(name.contains("trial"))require(spawnerCount>0,"trial chambers preserve configured spawners/vaults");
                if(name.contains("swamp"))require(entityCount==2,"hut creates one witch and cat");
                require(edited>0,"native structure references cover placed pieces");
                // Run disjoint requests concurrently and verify the cached plans are request-order independent.
                try(var workers=Executors.newFixedThreadPool(8)) {
                    var jobs=new ArrayList<Future<?>>();for(var e:chunks.entrySet()){var pos=e.getKey();jobs.add(workers.submit(()->{
                        try(var generated=nativeTerrain.generate(request(id,pos.x(),pos.z()))) {require(Arrays.equals(e.getValue(),generated.blocks().toArray(ValueLayout.JAVA_SHORT)),"parallel structure blocks repeat exactly");}
                        require(tags.get(pos).equals(nativeTerrain.structureData(request(id,pos.x(),pos.z()))),"parallel metadata repeats exactly");
                    }));} for(var job:jobs)job.get();
                }
                if(name.contains("village") || name.contains("pyramid") || name.contains("trial")) {
                    var directory=Files.createTempDirectory("retina-structure-mca-");var regions=new HashSet<ChunkPos>();
                    for(var pos:chunks.keySet())regions.add(new ChunkPos(pos.getRegionX()*32,pos.getRegionZ()*32));
                    long begin=System.nanoTime();
                    for(var region:regions)nativeTerrain.generateRegion(request(id,region.x(),region.z()),directory.resolve("r."+region.getRegionX()+"."+region.getRegionZ()+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:plains");
                    try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-structures",Level.OVERWORLD,"chunk"),directory,false)) {
                        for(var e:chunks.entrySet()){var tag=storage.read(e.getKey());require(tag!=null,"MCA chunk exists");
                            var expected=tags.get(e.getKey());for(String key:List.of("structures","block_entities","entities"))require(Objects.equals(tag.get(key),expected.get(key)),"MCA metadata agrees with chunk path: "+key+" "+e.getKey());
                            for(var section:McaTestSections.terrain(tag,-64,384)) {
                                int layer=section.getIntOr("Y",0)*16+64;
                                var blocks=codec.parse(NbtOps.INSTANCE,section.getCompoundOrEmpty("block_states")).getOrThrow();
                                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)require(blocks.get(x,y,z).equals(profile.materials()[Short.toUnsignedInt(e.getValue()[(layer+y)*256+z*16+x])]),"MCA and per-chunk blocks agree at "+e.getKey());
                            }
                        }
                    }
                    event("structure_mca_chunk_parity","\"structure\":\""+name+"\",\"regions\":"+regions.size()+",\"chunks\":"+chunks.size()+",\"ms\":"+(System.nanoTime()-begin)/1e6);
                }
                event("rust_structure_assembly","\"structure\":\""+name+"\",\"pieces\":"+pieces+",\"chunks\":"+chunks.size()+",\"loot\":"+chestCount+",\"entities\":"+entityCount+",\"spawners\":"+spawnerCount);
            }
        }
    }
    private static int houseCount(CompoundTag start) {
        int count=0;
        for(var child:start.getListOrEmpty("Children")) {
            String template=((CompoundTag)child).getStringOr("template","");
            if(template.contains("/houses/") && !template.contains("accessory") && !template.contains("farm") && !template.contains("pen"))count++;
        }
        return count;
    }
    private static void villageTerrainChecks(JsonObject source,BiomeTerrainProfile profile,NativeTerrain terrain) {
        var fixture=force(source,"minecraft:village_plains");int id=terrain.registerProfile(fixture.toString());
        for(boolean underwater:List.of(false,true)) {
            var request=new TerrainRequest(SEED,0,0,-64,384,underwater?-32:120,underwater?0:48,.008f,id);
            var start=terrain.structureStarts(request).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:village_plains");
            require(houseCount(start)>=3,"terrain village contains houses: underwater="+underwater+" count="+houseCount(start));
            int verified=0;var chunks=new HashMap<ChunkPos,short[]>();
            for(var value:start.getListOrEmpty("Children")) {
                var child=(CompoundTag)value;String name=child.getStringOr("template","");
                if(!name.contains("/houses/") || name.contains("accessory") || name.contains("farm") || name.contains("pen"))continue;
                int[] bb=child.getIntArray("BB").orElseThrow();
                if(underwater)require(bb[1]>=62,"water-surface village floor is above the seabed: "+Arrays.toString(bb));
                int roof=0;
                for(int z=bb[2];z<=bb[5];z++)for(int x=bb[0];x<=bb[3];x++) {
                    var pos=new ChunkPos(Math.floorDiv(x,16),Math.floorDiv(z,16));
                    var blocks=chunks.computeIfAbsent(pos,p->{try(var generated=terrain.generate(new TerrainRequest(SEED,p.x(),p.z(),-64,384,request.baseHeight(),request.amplitude(),.008f,id))){return generated.blocks().toArray(ValueLayout.JAVA_SHORT);}});
                    for(int y=bb[1]+3;y<=bb[4];y++) {
                        var block=profile.materials()[Short.toUnsignedInt(blocks[(y+64)*256+Math.floorMod(z,16)*16+Math.floorMod(x,16)])];
                        if(block.is(net.minecraft.tags.BlockTags.PLANKS) || block.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) || block.is(Blocks.GLASS_PANE) || block.is(Blocks.COBBLESTONE) || block.is(Blocks.MOSSY_COBBLESTONE))roof++;
                    }
                }
                require(roof>8,"house walls/roof were placed for "+name+": "+roof);verified++;
            }
            event("village_terrain_houses","\"underwater\":"+underwater+",\"houses\":"+verified+",\"chunks\":"+chunks.size());
        }
    }
    private static void villageGrassChecks(JsonObject source,BiomeTerrainProfile profile,NativeTerrain terrain) throws Exception {
        var fixture=force(source,"minecraft:village_plains");var materials=profile.materials();
        int shortGrass=Arrays.asList(materials).indexOf(Blocks.SHORT_GRASS.defaultBlockState());
        int lower=Arrays.asList(materials).indexOf(Blocks.TALL_GRASS.defaultBlockState());
        int upper=Arrays.asList(materials).indexOf(Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        int grass=Arrays.asList(materials).indexOf(Blocks.GRASS_BLOCK.defaultBlockState());
        int dirt=Arrays.asList(materials).indexOf(Blocks.DIRT.defaultBlockState());
        require(shortGrass>=0 && lower>=0 && upper>=0 && grass>=0 && dirt>=0,"grass fixture uses loaded registry states");
        var recipes=new JsonArray();
        for(int[] state:List.of(new int[]{shortGrass,0},new int[]{lower,upper})) {
            var recipe=JsonParser.parseString("{\"source\":\"test:village_grass\",\"salt\":0,\"density\":256,\"low_density\":256,\"noise_count\":false,\"rarity\":1,\"tries\":1,\"spread\":[0,0,0],\"kind\":\"plant\",\"states\":[{\"lower\":0,\"upper\":0,\"weight\":1,\"band\":0,\"dry\":false}],\"placement\":[{\"type\":\"count\",\"count\":256},{\"type\":\"in_square\"},{\"type\":\"heightmap\",\"map\":3}]}").getAsJsonObject();
            recipe.addProperty("placement_salt",recipes.size()+42);
            var variant=recipe.getAsJsonArray("states").get(0).getAsJsonObject();variant.addProperty("lower",state[0]);variant.addProperty("upper",state[1]);recipes.add(recipe);
        }
        fixture.add("decorations",recipes);
        for(var value:fixture.getAsJsonArray("biomes")) {
            var biome=value.getAsJsonObject();biome.add("terrain",JsonParser.parseString("[0,0,0]"));
            biome.addProperty("top",grass);biome.addProperty("filler",dirt);biome.addProperty("underwater",dirt);
            biome.add("decorations",JsonParser.parseString("[0,1]"));
        }
        var legacy=fixture.deepCopy();legacy.remove("plant_floor_masks");
        int id=terrain.registerProfile(fixture.toString()),oldId=terrain.registerProfile(legacy.toString());
        var start=terrain.structureStarts(request(id,0,0)).getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty("minecraft:village_plains");
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var child:start.getListOrEmpty("Children")) {
            int[] b=((CompoundTag)child).getIntArray("BB").orElseThrow();minX=Math.min(minX,b[0]);minZ=Math.min(minZ,b[2]);maxX=Math.max(maxX,b[3]);maxZ=Math.max(maxZ,b[5]);
        }
        var affected=new LinkedHashMap<ChunkPos,short[]>();int removed=0,onRoad=0,surviving=0;
        for(int z=Math.floorDiv(minZ,16);z<=Math.floorDiv(maxZ,16);z++)for(int x=Math.floorDiv(minX,16);x<=Math.floorDiv(maxX,16);x++) {
            short[] old,now;
            try(var generated=terrain.generate(request(oldId,x,z))){old=generated.blocks().toArray(ValueLayout.JAVA_SHORT);}
            try(var generated=terrain.generate(request(id,x,z))){now=generated.blocks().toArray(ValueLayout.JAVA_SHORT);}
            boolean road=false;
            for(int i=0;i<now.length;i++) {
                var before=materials[Short.toUnsignedInt(old[i])];var after=materials[Short.toUnsignedInt(now[i])];
                if(old[i]!=now[i]) {
                    require((before.is(Blocks.SHORT_GRASS)||before.is(Blocks.TALL_GRASS)) && after.isAir(),"final grass cleanup changes only unsupported vegetation");removed++;
                }
                if(i>=256 && (old[i]==shortGrass || old[i]==lower)) {
                    var floor=materials[Short.toUnsignedInt(old[i-256])];
                    if(floor.is(Blocks.DIRT_PATH)||floor.is(Blocks.GRAVEL)) {onRoad++;road=true;require(after.isAir(),"village roads clear their grass");}
                }
                if(now[i]==shortGrass || now[i]==lower) {
                    require(i>=256 && materials[Short.toUnsignedInt(now[i-256])].is(net.minecraft.tags.BlockTags.SUPPORTS_VEGETATION),"surviving village grass has registered support");surviving++;
                }
                if(now[i]==upper)require(i>=256 && now[i-256]==lower,"removed grass leaves no upper half");
            }
            if(road)affected.put(new ChunkPos(x,z),now);
        }
        require(onRoad>20 && removed>onRoad && surviving>100,"fixture reproduces road overlap, both grass heights, and supported survivors");
        var chosen=affected.keySet().iterator().next();int rx=Math.floorDiv(chosen.x(),32),rz=Math.floorDiv(chosen.z(),32);
        var directory=Files.createTempDirectory("retina-village-grass-");int compared=0;
        var codec=PalettedContainer.codecRW(net.minecraft.world.level.block.state.BlockState.CODEC,Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),Blocks.AIR.defaultBlockState());
        try {
            terrain.generateRegion(request(id,rx*32,rz*32),directory.resolve("r."+rx+"."+rz+".mca"),SharedConstants.getCurrentVersion().dataVersion().version(),"minecraft:plains");
            try(var storage=new RegionFileStorage(new RegionStorageInfo("retina-village-grass",Level.OVERWORLD,"chunk"),directory,false)) {
                for(var entry:affected.entrySet()) {
                    var pos=entry.getKey();if(Math.floorDiv(pos.x(),32)!=rx || Math.floorDiv(pos.z(),32)!=rz)continue;
                    var tag=storage.read(pos);var raw=entry.getValue();var maps=new int[6][256];
                    var sections=McaTestSections.terrain(tag,-64,384);
                    for(int section=0;section<24;section++) {
                        var states=codec.parse(NbtOps.INSTANCE,sections.get(section).getCompoundOrEmpty("block_states")).getOrThrow();
                        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                            int layer=section*16+y,c=z*16+x;var state=states.get(x,y,z);
                            require(state.equals(materials[Short.toUnsignedInt(raw[layer*256+c])]),"road grass cleanup has chunk/MCA parity");
                            for(var type:net.minecraft.world.level.levelgen.Heightmap.Types.values())if(type.isOpaque().test(state))maps[type.ordinal()][c]=layer+1;
                        }
                    }
                    for(var type:net.minecraft.world.level.levelgen.Heightmap.Types.values()) {
                        var bits=new net.minecraft.util.SimpleBitStorage(9,256,tag.getCompoundOrEmpty("Heightmaps").getLongArray(type.getSerializationKey()).orElseThrow());
                        for(int c=0;c<256;c++)require(bits.get(c)==maps[type.ordinal()][c],"road cleanup updates final heightmaps");
                    }
                    compared++;
                }
            }
        }finally{try(var files=Files.walk(directory)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}}
        require(compared>0,"road-overlap chunks decoded from native MCA");
        event("village_grass_final_support","\"road_overlaps\":"+onRoad+",\"removed_blocks\":"+removed+",\"surviving_grass\":"+surviving+",\"mca_chunks\":"+compared);
    }
    private static JsonObject force(JsonObject source,String name) {
        var out=source.deepCopy();out.remove("registry_program");out.add("decorations",new JsonArray());out.add("ores",new JsonArray());out.add("cave_noises",new JsonArray());out.add("terrain_features",new JsonObject());
        for(var b:out.getAsJsonArray("biomes")){b.getAsJsonObject().addProperty("snow_surface",false);b.getAsJsonObject().add("ores",new JsonArray());b.getAsJsonObject().add("decorations",new JsonArray());}
        var structures=out.getAsJsonObject("structures");int index=-1;var definitions=structures.getAsJsonArray("definitions");
        for(int i=0;i<definitions.size();i++)if(definitions.get(i).getAsJsonObject().get("id").getAsString().equals(name))index=i;
        require(index>=0,"registered definition "+name);var allowed=new JsonArray();for(int i=0;i<out.getAsJsonArray("biomes").size();i++)allowed.add(i);definitions.get(index).getAsJsonObject().add("biomes",allowed);
        var set=new JsonObject();set.addProperty("id","retina:forced");set.add("placement",JsonParser.parseString("{\"type\":\"minecraft:random_spread\",\"spacing\":4096,\"separation\":4095,\"salt\":0}"));
        var entries=new JsonArray();var entry=new JsonObject();entry.addProperty("definition",index);entry.addProperty("weight",1);entries.add(entry);set.add("entries",entries);var sets=new JsonArray();sets.add(set);structures.add("sets",sets);return out;
    }
    private static TerrainRequest request(int profile,int x,int z){return new TerrainRequest(SEED,x,z,-64,384,120,0,.008f,profile);}
    private static void event(String name,String context){System.out.println("QA_EVT {\"event\":\""+name+"\",\"status\":\"pass\",\"context\":{"+context+"}}");}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
