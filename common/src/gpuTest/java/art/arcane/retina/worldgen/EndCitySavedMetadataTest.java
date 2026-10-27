package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Real source starts, changed live registries/templates, and a template-access trap. */
public final class EndCitySavedMetadataTest {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        var fixture=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        int starts=0,pieces=0,changed=0;var reads=new AtomicInteger();var forbidden=new AtomicInteger();
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var manager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var context=new StructurePieceSerializationContext(f.resources,f.registry,manager);
            var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY);
            var source=new GenerationSource(Level.END,f.generator,f.registry.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END).value());
            var data=EndTerrainProfile.export(f.registry,source,123456789,StructureProfile.Context.of(manager,true));
            var metadata=JsonParser.parseString(data.json()).getAsJsonObject();
            var decoder=SavedStructureStarts.fromMetadata(metadata);
            require(decoder.hasFrozenPieces(),"source city definition selected");
            var altered=Structure.DIRECT_CODEC.encodeStart(f.registry.createSerializationContext(JsonOps.INSTANCE),city.value()).getOrThrow().getAsJsonObject();
            altered.addProperty("terrain_adaptation","beard_box");
            var liveCity=Structure.DIRECT_CODEC.parse(f.registry.createSerializationContext(JsonOps.INSTANCE),altered).getOrThrow();
            var liveStructures=new MappedRegistry<Structure>(Registries.STRUCTURE,Lifecycle.stable());
            Registry.register(liveStructures,BuiltinStructures.END_CITY,liveCity);liveStructures.freeze();
            var liveRegistry=new RegistryAccess.ImmutableRegistryAccess(Stream.concat(f.registry.registries()
                    .filter(entry->!entry.key().equals(Registries.STRUCTURE)),Stream.of(new RegistryAccess.RegistryEntry<>(Registries.STRUCTURE,liveStructures)))).freeze();
            var tiny=new StructureTemplate();var tag=new CompoundTag();var size=new ListTag();for(int n:new int[]{1,1,1})size.add(IntTag.valueOf(n));tag.put("size",size);
            tiny.load(f.registry.lookupOrThrow(Registries.BLOCK),tag);
            var changedManager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK)) {
                @Override public StructureTemplate getOrCreate(Identifier id){reads.incrementAndGet();return tiny;}
            };
            var trapManager=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK)) {
                @Override public StructureTemplate getOrCreate(Identifier id){forbidden.incrementAndGet();throw new AssertionError("Live template callback: "+id);}
            };
            var changedContext=new StructurePieceSerializationContext(f.resources,liveRegistry,changedManager);
            var trapContext=new StructurePieceSerializationContext(f.resources,liveRegistry,trapManager);
            for(var value:fixture.getAsJsonArray("cases")) {
                var row=value.getAsJsonObject();if(!row.get("valid").getAsBoolean())continue;
                long seed=row.get("seed").getAsLong();var at=new ChunkPos(row.getAsJsonArray("chunk").get(0).getAsInt(),row.getAsJsonArray("chunk").get(1).getAsInt());
                var original=EndCityPlacementTest.start(f,manager,city,seed,at);var saved=original.createTag(context,at);
                // This is the actual failure mode, not a hypothetical resource
                // identity check: the source decoder changes saved geometry.
                var vanilla=StructureStart.loadStaticStart(changedContext,saved,seed);
                require(vanilla!=null&&vanilla.isValid(),"changed vanilla decoder ran");
                require(!vanilla.createTag(changedContext,at).equals(saved),"live templates change source piece boxes");
                changed++;
                var decoded=decoder.load(trapContext,saved,seed);
                require(decoded!=null&&decoded.isValid(),"saved city decoded without templates");
                require(decoded.createTag(trapContext,at).equals(saved),"all source saved NBT retained");
                require(box(decoded.getBoundingBox()).equals(box(original.getBoundingBox())),"start box ignores changed live adjustment");
                if(starts==0) {
                    var adjustedMetadata=metadata.deepCopy();
                    for(var definition:adjustedMetadata.getAsJsonObject("structures").getAsJsonArray("definitions"))
                        if(definition.getAsJsonObject().get("id").getAsString().equals("minecraft:end_city"))
                            definition.getAsJsonObject().getAsJsonObject("config").addProperty("terrain_adaptation","beard_box");
                    var adjusted=SavedStructureStarts.fromMetadata(adjustedMetadata).load(trapContext,saved,seed);
                    require(box(adjusted.getBoundingBox()).equals(box(original.getBoundingBox().inflatedBy(12))),"saved adjustment margin retained");
                }
                require(decoded.getPieces().size()==original.getPieces().size(),"all pieces retained");
                for(int i=0;i<decoded.getPieces().size();i++) {
                    var actual=decoded.getPieces().get(i);var expected=original.getPieces().get(i);
                    require(actual.getRotation()==expected.getRotation(),"source piece rotation");
                    require(actual.getGenDepth()==expected.getGenDepth(),"signed generation depth");
                    require(box(actual.getBoundingBox()).equals(box(expected.getBoundingBox())),"source piece bounds");pieces++;
                }
                var packed=new CompoundTag();var table=new CompoundTag();table.put("minecraft:end_city",saved);packed.put("starts",table);
                require(decoder.read(trapContext,packed,seed).get(liveCity).createTag(trapContext,at).equals(saved),"saved MCA map decode contract");
                decoded.addReference();require(decoded.createTag(trapContext,at).getIntOr("references",-1)==saved.getIntOr("references",0)+1,"explorer-map reference mutation");
                try { decoded.getPieces().getFirst().postProcess(null,null,null,null,null,null,null);throw new AssertionError("Metadata piece generated blocks"); }
                catch(UnsupportedOperationException expected){require(expected.getMessage().contains("native generation"),"placement ownership error");}
                starts++;
            }
            require(starts==21&&pieces==1398&&changed==21&&reads.get()>0&&forbidden.get()==0,"complete fixture coverage and no hidden template reads");
            var none=new JsonObject();var structures=new JsonObject();structures.add("definitions",new JsonArray());none.add("structures",structures);
            require(!SavedStructureStarts.fromMetadata(none).hasFrozenPieces(),"other generator paths untouched");
            var invalid=new CompoundTag();invalid.putString("id","INVALID");require(decoder.load(trapContext,invalid,0)==StructureStart.INVALID_START,"invalid-start sentinel preserved");
        }
        var report=new JsonObject();report.addProperty("schema","retina.end.saved-city-metadata.v1");report.addProperty("source_starts",starts);report.addProperty("source_pieces",pieces);report.addProperty("changed_source_decodes",changed);report.addProperty("source_live_template_reads",reads.get());report.addProperty("native_live_template_reads",forbidden.get());
        report.addProperty("full_regions_generated",0);report.addProperty("production_activated",false);
        Files.writeString(out.resolve("validation.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");System.out.println("QA_EVT "+report);
    }
    static List<Integer> box(BoundingBox b){return List.of(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
