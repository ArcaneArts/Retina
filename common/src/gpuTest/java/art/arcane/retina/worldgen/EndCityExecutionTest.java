package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.*;
import java.util.*;

/** Live vanilla generation contexts versus the native production start/query cache. */
public final class EndCityExecutionTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var fixture=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        int candidates=0,valid=0,pieces=0;var cases=new JsonArray();
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var serialization=new StructurePieceSerializationContext(f.resources,f.registry,manager);
            var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY).value();
            var source=new GenerationSource(Level.END,f.generator,f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value());
            var bySeed=new LinkedHashMap<Long,List<JsonObject>>();
            for(var value:fixture.getAsJsonArray("cases")) {var row=value.getAsJsonObject();bySeed.computeIfAbsent(row.get("seed").getAsLong(),s->new ArrayList<>()).add(row);}
            for(var group:bySeed.entrySet()) {
                long seed=group.getKey();var state=f.state(seed);
                var data=EndTerrainProfile.export(f.registry,source,seed,StructureProfile.Context.of(manager,true));
                var exported=JsonParser.parseString(data.json()).getAsJsonObject();var sets=exported.getAsJsonObject("structures").getAsJsonArray("sets");
                int set=-1;for(int i=0;i<sets.size();i++)if(sets.get(i).getAsJsonObject().get("id").getAsString().equals("minecraft:end_cities"))set=i;
                require(set>=0,"actual city set exported");
                try(var nativeProfile=EndDefinitionFixture.create(f,data,seed)) {
                    // Plain-JVM fixture: inspect the actual saved placement objects;
                    // the mixin constructor itself is exercised in the mod harness.
                    var savedSets=new SavedStructureSets(f.registry,JsonParser.parseString(nativeProfile.metadataJson()).getAsJsonObject());
                    var field=SavedStructureSets.class.getDeclaredField("executable");field.setAccessible(true);
                    @SuppressWarnings("unchecked") var advertised=(List<net.minecraft.core.Holder<StructureSet>>)field.get(savedSets);
                    require(advertised.stream().anyMatch(h->h.value().structures().stream().anyMatch(e->e.structure().value()==city)),"saved End city search placement advertised");
                    var root=nativeProfile.request(0, 0);
                    int[] queries=new int[group.getValue().size()*3];
                    for(int i=0;i<group.getValue().size();i++){var row=group.getValue().get(i);queries[i*3]=row.getAsJsonArray("chunk").get(0).getAsInt();queries[i*3+1]=row.getAsJsonArray("chunk").get(1).getAsInt();queries[i*3+2]=set;}
                    int[] answers=NativeTerrain.instance().queryStructureStarts(root,queries);
                    for(int i=0;i<group.getValue().size();i++) {
                        var row=group.getValue().get(i);var at=new ChunkPos(queries[i*3],queries[i*3+1]);
                        var expected=EndCityReferenceTest.capture(f,manager,serialization,city,state,seed,at);
                        require(expected.equals(row),"independent reference still exact seed="+seed+" at="+at);
                        boolean exists=expected.get("valid").getAsBoolean();require((answers[i]>=0)==exists,"native start query seed="+seed+" at="+at);candidates++;
                        if(exists) {
                            var request=nativeProfile.request(at.x(), at.z());
                            var metadata=NativeTerrain.instance().structureStarts(request);var starts=metadata.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                            var start=starts.getCompoundOrEmpty("minecraft:end_city");
                            require(start.getStringOr("id","").equals("minecraft:end_city"),"actual saved start id");
                            require(start.getIntOr("ChunkX",Integer.MIN_VALUE)==at.x()&&start.getIntOr("ChunkZ",Integer.MIN_VALUE)==at.z(),"actual start coordinates");
                            require(StructureProfile.tag(start.getListOrEmpty("Children")).equals(typedPieces(expected.getAsJsonArray("pieces"))),"full saved ECP children seed="+seed+" at="+at);
                            require(start.getIntOr("references",-1)==0,"fresh start references");
                            var reopened=StructureStart.loadStaticStart(serialization,start,seed);
                            require(reopened!=null && reopened.isValid() && reopened.createTag(serialization,at).equals(start),"actual Minecraft city start reopen codec");
                            require(NativeTerrain.instance().structureStarts(request).equals(metadata),"cached metadata stable");
                            pieces+=start.getListOrEmpty("Children").size();valid++;
                        }
                        cases.add(expected);
                    }
                    Files.writeString(out.resolve("profile-"+seed+".json"),data.json());
                }
            }
            var level=EndEntityFixture.create(f.registry);var entity=net.minecraft.world.entity.EntityTypes.SHULKER.create(level,net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            require(entity!=null,"actual source shulker available");entity.setPos(-12.5,81,23.5);
            var entities=new JsonObject();entities.add("shulker",StructureProfile.tag(level.save(entity)));
            for(var rotation:net.minecraft.world.level.block.Rotation.values()) {
                var frame=new net.minecraft.world.entity.decoration.ItemFrame(level,new net.minecraft.core.BlockPos(-13,83,24),rotation.rotate(net.minecraft.core.Direction.SOUTH));
                frame.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA),false);
                entities.add("frame_"+rotation,StructureProfile.tag(level.save(frame)));
            }
            Files.writeString(out.resolve("entity-source.json"),entities.toString());
        }
        var orders=new JsonObject();var orderCases=new JsonArray();
        for(int count:new int[]{0,1,8,24,25,100,289}) {
            var input=new JsonArray();var keys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();int added=0;
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(added<count){input.add(EndReferenceFixtures.ints(x,z));keys.add(ChunkPos.pack(x,z));added++;}
            var row=new JsonObject();row.add("chunks",input);row.add("keys",new Gson().toJsonTree(keys.toLongArray()));orderCases.add(row);
        }
        var input=new JsonArray();var keys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){input.add(EndReferenceFixtures.ints(x,z));keys.add(ChunkPos.pack(x,z));}
        var row=new JsonObject();row.add("chunks",input);row.add("keys",new Gson().toJsonTree(keys.toLongArray()));orderCases.add(row);
        orders.addProperty("schema","retina.end.city-reference-order.v1");orders.add("cases",orderCases);
        Files.writeString(out.resolve("reference-order.json"),orders.toString()+"\n");
        var report=new JsonObject();report.addProperty("schema","retina.end.city-execution.v1");report.addProperty("candidates",candidates);report.addProperty("valid_starts",valid);report.addProperty("saved_pieces",pieces);report.add("cases",cases);
        report.addProperty("full_regions_generated",0);report.addProperty("production_activated",false);
        Files.writeString(out.resolve("validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        System.out.println("QA_EVT {\"event\":\"end_city_execution\",\"candidates\":"+candidates+",\"valid_starts\":"+valid+",\"saved_pieces\":"+pieces+",\"status\":\"pass\"}");
    }
    private static JsonArray typedPieces(JsonArray pieces){var result=new JsonArray();result.add(9);var payload=new JsonArray();payload.add(10);payload.add(pieces);result.add(payload);return result;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
