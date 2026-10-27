package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;

/** Exact city-only chunk execution against source StructureStart.placeInChunk. */
public final class EndCityPlacementTest {
    record Target(long seed,ChunkPos chunk,boolean dense) { Target(long seed,ChunkPos chunk){this(seed,chunk,false);} }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var fixture=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        int chunks=0,blockEntities=0,entities=0,loot=0,maxReferences=0;boolean crossing=false;var rows=new JsonArray();
        var blockEntityKinds=new TreeMap<String,Integer>();var entityKinds=new TreeMap<String,Integer>();
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
            var placement=(RandomSpreadStructurePlacement)f.registry.lookupOrThrow(Registries.STRUCTURE_SET).getValueOrThrow(BuiltinStructureSets.END_CITIES).placement();
            var denseJson=net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC
                    .encodeStart(f.registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),placement).getOrThrow().getAsJsonObject();
            denseJson.addProperty("spacing",1);denseJson.addProperty("separation",0);
            var densePlacement=(RandomSpreadStructurePlacement)net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC
                    .parse(f.registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),denseJson).getOrThrow();
            var source=new GenerationSource(Level.END,f.generator,f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value());
            var entityLevel=EndEntityFixture.create(f.registry);
            var selected=new LinkedHashMap<String,JsonObject>();
            // Exercise each rotation, preferring ships so all marker families
            // are covered without generating whole regions.
            for(var value:fixture.getAsJsonArray("cases")) {
                var row=value.getAsJsonObject();if(!row.get("valid").getAsBoolean())continue;
                String rotation=row.getAsJsonArray("pieces").get(0).getAsJsonArray().get(1).getAsJsonObject().getAsJsonArray("Rot").get(1).getAsString();
                if(!selected.containsKey(rotation)||row.get("ship").getAsBoolean())selected.put(rotation,row);
            }
            require(selected.size()==4,"all source city rotations available");
            var targets=new LinkedHashSet<Target>();var extraBlocks=new HashSet<Block>();
            for(var row:selected.values()) {
                long seed=row.get("seed").getAsLong();var at=new ChunkPos(row.getAsJsonArray("chunk").get(0).getAsInt(),row.getAsJsonArray("chunk").get(1).getAsInt());
                var start=start(f,manager,city,seed,at);var families=new HashSet<String>();
                for(var piece:start.getPieces()) {
                    var template=(TemplateStructurePiece)piece;
                    for(var block:List.of(Blocks.BREWING_STAND,Blocks.DRAGON_WALL_HEAD,Blocks.ENDER_CHEST))if(!extraBlocks.contains(block)) {
                        var found=template.template().filterBlocks(template.templatePosition(),template.placeSettings(),block);
                        if(!found.isEmpty()){targets.add(new Target(seed,ChunkPos.containing(found.getFirst().pos())));extraBlocks.add(block);}
                    }
                    for(var marker:template.template().filterBlocks(template.templatePosition(),template.placeSettings(),Blocks.STRUCTURE_BLOCK)) {
                        String kind=marker.nbt().getStringOr("metadata","");
                        if(families.add(kind))targets.add(new Target(seed,ChunkPos.containing(marker.pos())));
                    }
                    if(!crossing && (Math.floorDiv(piece.getBoundingBox().minX(),512)!=Math.floorDiv(at.getMinBlockX(),512)
                            || Math.floorDiv(piece.getBoundingBox().minZ(),512)!=Math.floorDiv(at.getMinBlockZ(),512))) {
                        targets.add(new Target(seed,new ChunkPos(Math.floorDiv(piece.getBoundingBox().minX(),16),Math.floorDiv(piece.getBoundingBox().minZ(),16))));crossing=true;
                    }
                }
                boolean gap=false;var box=start.getBoundingBox();
                for(int x=Math.max(at.x()-8,Math.floorDiv(box.minX(),16));x<=Math.min(at.x()+8,Math.floorDiv(box.maxX(),16))&&!gap;x++)
                    for(int z=Math.max(at.z()-8,Math.floorDiv(box.minZ(),16));z<=Math.min(at.z()+8,Math.floorDiv(box.maxZ(),16))&&!gap;z++) {
                        var candidate=new ChunkPos(x,z);
                        if(start.getPieces().stream().noneMatch(p->p.getBoundingBox().intersects(candidate.getMinBlockX(),candidate.getMinBlockZ(),candidate.getMaxBlockX(),candidate.getMaxBlockZ()))) {
                            targets.add(new Target(seed,candidate));gap=true;
                        }
                    }
            }
            for(var row:selected.values()) {
                targets.add(new Target(row.get("seed").getAsLong(),new ChunkPos(row.getAsJsonArray("chunk").get(0).getAsInt(),row.getAsJsonArray("chunk").get(1).getAsInt()),true));
                if(targets.stream().filter(Target::dense).count()==2)break;
            }
            var profiles=new HashMap<String,BiomeTerrainProfile>();var starts=new HashMap<Target,StructureStart>();
            for(var target:targets) {
                long seed=target.seed();var at=target.chunk();
                var activePlacement=target.dense()?densePlacement:placement;
                var profile=profiles.computeIfAbsent(seed+":"+target.dense(),s->{
                    var full=EndTerrainProfile.export(f.registry,source,seed,StructureProfile.Context.of(manager,true));
                    var json=JsonParser.parseString(full.json()).getAsJsonObject();json.add("decorations",new JsonArray());json.remove("terrain_features");
                    if(target.dense())for(var value:json.getAsJsonObject("structures").getAsJsonArray("sets")) {
                        var set=value.getAsJsonObject();if(set.get("id").getAsString().equals("minecraft:end_cities"))set.add("placement",denseJson.deepCopy());
                    }
                    for(var biome:json.getAsJsonArray("biomes"))biome.getAsJsonObject().add("decorations",new JsonArray());
                    return BiomeTerrainProfile.register(new TerrainProfileData(full.seaLevel(),full.materials(),full.biomes(),json.toString()));
                });
                var state=f.state(seed);var columns=new HashMap<Long,NoiseColumn>();
                var capture=f.new Capture(seed,-1,p->{
                    if(p.getY()<0||p.getY()>=256)return Blocks.AIR.defaultBlockState();
                    long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
                    return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
                },entityLevel);
                var referenced=new TreeMap<ChunkPos,StructureStart>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
                for(int gx=Math.floorDiv(at.x()-8,activePlacement.spacing());gx<=Math.floorDiv(at.x()+8,activePlacement.spacing());gx++)
                    for(int gz=Math.floorDiv(at.z()-8,activePlacement.spacing());gz<=Math.floorDiv(at.z()+8,activePlacement.spacing());gz++) {
                        var candidate=activePlacement.getPotentialStructureChunk(seed,gx*activePlacement.spacing(),gz*activePlacement.spacing());
                        if(Math.abs(candidate.x()-at.x())>8||Math.abs(candidate.z()-at.z())>8)continue;
                        var value=starts.computeIfAbsent(new Target(seed,candidate),key->start(f,manager,city,key.seed(),key.chunk()));
                        if(value.isValid()&&value.getBoundingBox().intersects(at.getMinBlockX(),at.getMinBlockZ(),at.getMaxBlockX(),at.getMaxBlockZ()))referenced.put(candidate,value);
                    }
                var keys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();referenced.keySet().forEach(c->keys.add(c.pack()));
                int decorationIndex=f.registry.lookupOrThrow(Registries.STRUCTURE).stream()
                        .filter(value->value.step()==city.value().step()).toList().indexOf(city.value());
                // Compare finished neighboring chunks: source template edge
                // updates can reshape a stair in a previously placed chunk.
                for(int nz=at.z()-1;nz<=at.z()+1;nz++)for(int nx=at.x()-1;nx<=at.x()+1;nx++) {
                    var neighbor=new ChunkPos(nx,nz);
                    var nearby=new TreeMap<ChunkPos,StructureStart>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
                    for(int gx=Math.floorDiv(nx-8,activePlacement.spacing());gx<=Math.floorDiv(nx+8,activePlacement.spacing());gx++)
                        for(int gz=Math.floorDiv(nz-8,activePlacement.spacing());gz<=Math.floorDiv(nz+8,activePlacement.spacing());gz++) {
                            var candidate=activePlacement.getPotentialStructureChunk(seed,gx*activePlacement.spacing(),gz*activePlacement.spacing());
                            if(Math.abs(candidate.x()-nx)>8||Math.abs(candidate.z()-nz)>8)continue;
                            var value=starts.computeIfAbsent(new Target(seed,candidate),key->start(f,manager,city,key.seed(),key.chunk()));
                            if(value.isValid()&&value.getBoundingBox().intersects(neighbor.getMinBlockX(),neighbor.getMinBlockZ(),neighbor.getMaxBlockX(),neighbor.getMaxBlockZ()))nearby.put(candidate,value);
                        }
                    var neighborKeys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();nearby.keySet().forEach(c->neighborKeys.add(c.pack()));
                    var random=new WorldgenRandom(new XoroshiroRandomSource(0));long decoration=random.setDecorationSeed(seed,nx*16,nz*16);
                    random.setFeatureSeed(decoration,decorationIndex,city.value().step().ordinal());
                    var bounds=new BoundingBox(nx*16,f.height.getMinY()+1,nz*16,nx*16+15,f.height.getMaxY(),nz*16+15);
                    for(long key:neighborKeys)nearby.get(ChunkPos.unpack(key)).placeInChunk(capture.level,null,f.generator,random,bounds,neighbor);
                }
                var request=new TerrainRequest(seed,at.x(),at.z(),0,256,64,0,1,profile.nativeId());
                try(var chunk=NativeTerrain.instance().generate(request)) {
                    for(int y=0;y<256;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        var pos=new BlockPos(at.getMinBlockX()+x,y,at.getMinBlockZ()+z);var wanted=capture.state(pos);
                        var actual=profile.materials()[Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT,(y*256L+z*16+x)*2))];
                        require(actual.equals(wanted),"city block seed="+seed+" chunk="+at+" pos="+pos+" source="+wanted+" actual="+actual);
                    }
                }
                var metadata=NativeTerrain.instance().structureData(request);var wantedBlockEntities=new TreeMap<String,CompoundTag>();
                capture.blockEntities.forEach((pos,entity)->{if(ChunkPos.containing(pos).equals(at))wantedBlockEntities.put(pos.toShortString(),entity.saveWithFullMetadata(f.registry));});
                var actualBlockEntities=new TreeMap<String,CompoundTag>();
                for(var value:metadata.getListOrEmpty("block_entities")){var tag=(CompoundTag)value;actualBlockEntities.put(new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0)).toShortString(),tag);}
                require(actualBlockEntities.equals(wantedBlockEntities),"city block entity data seed="+seed+" chunk="+at+" source="+wantedBlockEntities+" actual="+actualBlockEntities);
                wantedBlockEntities.values().forEach(tag->blockEntityKinds.merge(tag.getStringOr("id",""),1,Integer::sum));
                var wantedEntities=capture.entities.stream().filter(entity->ChunkPos.containing(entity.blockPosition()).equals(at)).map(entityLevel::save).toList();var actualEntities=metadata.getListOrEmpty("entities");
                require(wantedEntities.size()==actualEntities.size(),"city entity count");
                for(int i=0;i<wantedEntities.size();i++) {
                    var actual=(CompoundTag)actualEntities.get(i);checkEntity(wantedEntities.get(i),actual);
                    require(entityLevel.roundTrip(actual).equals(actual),"actual city entity reopen codec");
                    entityKinds.merge(actual.getStringOr("id",""),1,Integer::sum);
                }
                var refs=metadata.getCompoundOrEmpty("structures").getCompoundOrEmpty("References").getLongArray("minecraft:end_city").orElse(new long[0]);
                require(Arrays.equals(refs,keys.toLongArray()),"source reference iteration order");
                var row=new JsonObject();row.addProperty("seed",seed);row.add("chunk",EndReferenceFixtures.ints(at.x(),at.z()));row.addProperty("source_starts",keys.size());row.addProperty("dense_placement_variant",target.dense());maxReferences=Math.max(maxReferences,keys.size());
                row.addProperty("block_entities",wantedBlockEntities.size());row.addProperty("entities",wantedEntities.size());row.addProperty("source_writes",capture.blocks.size());rows.add(row);
                chunks++;blockEntities+=wantedBlockEntities.size();entities+=wantedEntities.size();loot+=wantedBlockEntities.values().stream().filter(t->t.contains("LootTable")).count();
            }
        }
        Files.writeString(out.resolve("cases.json"),rows.toString()+"\n");
        require(chunks>0&&entities>0&&loot>0&&crossing&&maxReferences>1,"meaningful marker, shared-stream and boundary coverage; maxReferences="+maxReferences);
        var report=new JsonObject();report.addProperty("schema","retina.end.city-placement.v1");report.addProperty("city_only_chunks",chunks);report.addProperty("stored_block_states_exact",chunks*65536);report.addProperty("block_entities_exact",blockEntities);report.addProperty("entities",entities);report.addProperty("loot_tables_exact",loot);report.addProperty("cross_region",crossing);report.add("cases",rows);
        report.add("block_entity_kinds",new Gson().toJsonTree(blockEntityKinds));report.add("entity_kinds",new Gson().toJsonTree(entityKinds));
        report.addProperty("max_referenced_starts",maxReferences);report.addProperty("runtime_entity_differences","UUID; shulker constructor yaw (independent runtime RNG, range checked). Item-frame rotation and all remaining fields compare exactly.");
        report.addProperty("full_regions_generated",0);Files.writeString(out.resolve("validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("QA_EVT "+report);
    }
    static StructureStart start(EndReferenceFixtures f,StructureTemplateManager manager,Holder.Reference<Structure> city,long seed,ChunkPos at) {
        var state=f.state(seed);return city.value().generate(city,Level.END,f.registry,f.generator,f.biomes,state.createClimateSampler(SamplerContext.EMPTY_UNCACHED),state,manager,seed,at,0,f.height,city.value().biomes()::contains);
    }
    static void checkEntity(CompoundTag source,CompoundTag actual) {
        var a=source.copy();var b=actual.copy();a.remove("UUID");b.remove("UUID");
        if(a.getStringOr("id","").equals("minecraft:shulker")) {
            var ar=a.getListOrEmpty("Rotation");var br=b.getListOrEmpty("Rotation");
            require(ar.getFloatOr(0,-1)>=0&&ar.getFloatOr(0,-1)<(float)(2*Math.PI)&&br.getFloatOr(0,-1)>=0&&br.getFloatOr(0,-1)<(float)(2*Math.PI),"actual constructor yaw range");
            ar.set(0,FloatTag.valueOf(0));br.set(0,FloatTag.valueOf(0));
        }
        require(a.equals(b),"source city entity fields source="+a+" actual="+b);
    }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
