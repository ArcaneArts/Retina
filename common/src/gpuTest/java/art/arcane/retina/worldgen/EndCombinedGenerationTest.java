package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.lang.foreign.ValueLayout;
import java.nio.file.*;
import java.util.*;

/** Shared source world: neighboring city chunks followed by all loaded End feature roots. */
public final class EndCombinedGenerationTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var cases=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject().getAsJsonArray("cases");
        var rotations=new LinkedHashMap<String,JsonObject>();
        for(var value:cases) {
            var row=value.getAsJsonObject();if(!row.get("valid").getAsBoolean()||!row.get("ship").getAsBoolean())continue;
            String rotation=row.getAsJsonArray("pieces").get(0).getAsJsonArray().get(1).getAsJsonObject().getAsJsonArray("Rot").get(1).getAsString();
            rotations.putIfAbsent(rotation,row);
        }
        if(rotations.size()!=4)throw new AssertionError("All city rotations required");
        int index=0;
        for(var row:rotations.values()) {
            var chunk=row.getAsJsonArray("chunk");
            run(out.resolve("case-"+index++),row.get("seed").getAsLong(),new ChunkPos(chunk.get(0).getAsInt(),chunk.get(1).getAsInt()));
        }
        run(out.resolve("region-edge"),42,new ChunkPos(-1,-73));
    }
    private static void run(Path out,long seed,ChunkPos center) throws Exception {
        Files.createDirectories(out);
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("templates")).createAccess("source")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
            var placement=(RandomSpreadStructurePlacement)f.registry.lookupOrThrow(Registries.STRUCTURE_SET).getValueOrThrow(BuiltinStructureSets.END_CITIES).placement();
            var source=new GenerationSource(Level.END,f.generator,f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value());
            var data=EndTerrainProfile.export(f.registry,source,seed,StructureProfile.Context.of(manager,true));
            Files.writeString(out.resolve("profile.json"),data.json());var profile=BiomeTerrainProfile.register(data);
            var state=f.state(seed);var columns=new HashMap<Long,NoiseColumn>();
            var entityLevel=EndEntityFixture.create(f.registry);
            var capture=f.new Capture(seed,-1,p->{
                if(p.getY()<0||p.getY()>=256)return Blocks.AIR.defaultBlockState();
                long key=((long)p.getX()<<32)^Integer.toUnsignedLong(p.getZ());
                return columns.computeIfAbsent(key,k->f.generator.getBaseColumn(p.getX(),p.getZ(),f.height,state)).getBlock(p.getY());
            },entityLevel);
            var starts=new TreeMap<ChunkPos,StructureStart>(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
            // Bound the full chain of feature reads/writes, plus the city edge
            // update. A single feature's reach alone does not bound composition.
            var exported=JsonParser.parseString(data.json()).getAsJsonObject();int reach=1;
            for(var value:exported.getAsJsonArray("decorations"))reach=Math.addExact(reach,value.getAsJsonObject().get("reach").getAsInt());
            int radius=(reach+15)/16;
            int minX=center.x()-radius,maxX=center.x()+1+radius,minZ=center.z()-radius,maxZ=center.z()+1+radius;
            for(int gx=Math.floorDiv(minX-8,placement.spacing());gx<=Math.floorDiv(maxX+8,placement.spacing());gx++)
                for(int gz=Math.floorDiv(minZ-8,placement.spacing());gz<=Math.floorDiv(maxZ+8,placement.spacing());gz++) {
                    var at=placement.getPotentialStructureChunk(seed,gx*placement.spacing(),gz*placement.spacing());
                    var start=EndCityPlacementTest.start(f,manager,city,seed,at);if(start.isValid())starts.put(at,start);
                }
            var steps=FeatureSorter.buildFeaturesPerStep(new ArrayList<>(f.biomes.possibleBiomes()),b->b.value().getGenerationSettings().features(),true);
            int cityIndex=f.registry.lookupOrThrow(Registries.STRUCTURE).stream().filter(s->s.step()==city.value().step()).toList().indexOf(city.value());
            for(int step=0;step<Math.max(steps.size(),GenerationStep.Decoration.values().length);step++) {
                if(step==city.value().step().ordinal())for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                    var at=new ChunkPos(x,z);var keys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                    for(var entry:starts.entrySet())if(Math.abs(entry.getKey().x()-x)<=8&&Math.abs(entry.getKey().z()-z)<=8
                            &&entry.getValue().getBoundingBox().intersects(at.getMinBlockX(),at.getMinBlockZ(),at.getMaxBlockX(),at.getMaxBlockZ()))keys.add(entry.getKey().pack());
                    var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,x*16,z*16);random.setFeatureSeed(d,cityIndex,step);
                    var box=new BoundingBox(x*16,1,z*16,x*16+15,255,z*16+15);
                    for(long key:keys)starts.get(ChunkPos.unpack(key)).placeInChunk(capture.level,null,f.generator,random,box,at);
                }
                if(step<steps.size())for(int index=0;index<steps.get(step).features().size();index++) {
                    var placed=steps.get(step).features().get(index);
                    for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                        var random=new WorldgenRandom(new XoroshiroRandomSource(0));long d=random.setDecorationSeed(seed,x*16,z*16);random.setFeatureSeed(d,index,step);
                        new FeaturePlacer(capture.level,f.generator).placeWithBiomeCheck(placed,random,new BlockPos(x*16,0,z*16));
                    }
                }
            }
            var mismatches=new JsonArray();int differences=0,metadataDifferences=0,chunks=0,entities=0,blockEntities=0;
            for(int z=center.z();z<=center.z()+1;z++)for(int x=center.x();x<=center.x()+1;x++) {
                var at=new ChunkPos(x,z);var request=new TerrainRequest(seed,x,z,0,256,64,0,1,profile.nativeId());
                try(var chunk=NativeTerrain.instance().generate(request)) {
                    for(int y=0;y<256;y++)for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                        var pos=new BlockPos(x*16+dx,y,z*16+dz);var expected=capture.state(pos);
                        var actual=profile.materials()[Short.toUnsignedInt(chunk.blocks().get(ValueLayout.JAVA_SHORT,(y*256L+dz*16+dx)*2))];
                        if(!expected.equals(actual)){differences++;if(mismatches.size()<40){var row=new JsonObject();row.addProperty("pos",pos.toShortString());row.addProperty("source",expected.toString());row.addProperty("native",actual.toString());mismatches.add(row);}}
                    }
                }
                var expected=new TreeMap<String,CompoundTag>();capture.blockEntities.forEach((p,e)->{if(ChunkPos.containing(p).equals(at))expected.put(p.toShortString(),e.saveWithFullMetadata(f.registry));});
                var metadata=NativeTerrain.instance().structureData(request);
                var actual=new TreeMap<String,CompoundTag>();for(var value:metadata.getListOrEmpty("block_entities")){var tag=(CompoundTag)value;actual.put(new BlockPos(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0)).toShortString(),tag);}
                if(!expected.equals(actual)){metadataDifferences++;Files.writeString(out.resolve("metadata-"+x+"-"+z+".txt"),"source="+expected+"\nnative="+actual+"\n");}
                blockEntities+=expected.size();
                var sourceEntities=capture.entities.stream().filter(e->ChunkPos.containing(e.blockPosition()).equals(at)).map(entityLevel::save).toList();
                var nativeEntities=metadata.getListOrEmpty("entities");
                if(sourceEntities.size()!=nativeEntities.size())throw new AssertionError("Combined entity count at "+at);
                for(int i=0;i<sourceEntities.size();i++)EndCityPlacementTest.checkEntity(sourceEntities.get(i),(CompoundTag)nativeEntities.get(i));
                entities+=sourceEntities.size();
                chunks++;
            }
            var report=new JsonObject();report.addProperty("schema","retina.end.combined-generation.v1");report.addProperty("seed",seed);report.add("first_chunk",EndReferenceFixtures.ints(center.x(),center.z()));report.addProperty("chunks",chunks);report.addProperty("guarded_source_chunks",(maxX-minX+1)*(maxZ-minZ+1));report.addProperty("block_differences",differences);report.addProperty("metadata_chunk_differences",metadataDifferences);report.add("mismatches",mismatches);
            report.addProperty("block_entities_compared",blockEntities);report.addProperty("entities_compared",entities);
            Files.writeString(out.resolve("validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("QA_EVT "+report);
            if(differences!=0||metadataDifferences!=0)throw new AssertionError("Combined End generation differs; see "+out.resolve("validation.json"));
        }
    }
}
