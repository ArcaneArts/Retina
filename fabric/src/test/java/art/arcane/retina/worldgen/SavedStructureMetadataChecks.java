package art.arcane.retina.worldgen;

import art.arcane.retina.core.NativeWorldDefinition;

import com.google.gson.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Loader-transformed classes, saved piece fields, and optional actual MCA inputs. */
final class SavedStructureMetadataChecks {
    static void run() throws Exception {
        try(var f=new EndReferenceFixtures()) {
            var context=new StructurePieceSerializationContext(f.resources,f.registry,null);
            for(String id:List.of("village_plains","bastion_remnant","trial_chambers","desert_pyramid",
                    "jungle_pyramid","swamp_hut","fortress","nether_fossil","ruined_portal_nether")) {
                var definitions=new JsonArray();
                var definition=new JsonObject();definition.addProperty("id","minecraft:"+id);
                definition.addProperty("kind",id);var config=new JsonObject();config.addProperty("terrain_adaptation","none");
                definition.add("config",config);definitions.add(definition);
                var structures=new JsonObject();structures.add("definitions",definitions);
                var metadata=new JsonObject();metadata.add("structures",structures);
                var decoder=SavedStructureStarts.fromMetadata(metadata);
                var tag=new CompoundTag();tag.putString("id","minecraft:"+id);tag.putInt("ChunkX",-33);tag.putInt("ChunkZ",31);tag.putInt("references",2);
                var child=new CompoundTag();child.putString("id","minecraft:jigsaw");child.putIntArray("BB",new int[]{-530,-12,500,-510,130,520});
                child.putInt("O",-1);child.putInt("GD",7);child.putString("rotation","CLOCKWISE_90");
                child.putString("template","snapshot:removed_template");child.putString("payload","saved\0é😀");
                child.putLongArray("signed_longs",new long[]{Long.MIN_VALUE,9007199254740993L,Long.MAX_VALUE});
                var children=new ListTag();children.add(child);tag.put("Children",children);
                var at=new ChunkPos(-33,31);
                var loaded=decoder.load(context,tag,123);
                assertEquals(tag,loaded.createTag(context,at),id);
                assertEquals(List.of(-530,-12,500,-510,130,520),box(loaded.getBoundingBox()),id);
                loaded.addReference();assertEquals(3,loaded.createTag(context,at).getIntOr("references",-1));
                assertThrows(UnsupportedOperationException.class,()->loaded.getPieces().getFirst().postProcess(null,null,null,null,null,null,null));
                config.addProperty("terrain_adaptation","beard_box");
                var adjusted=SavedStructureStarts.fromMetadata(metadata).load(context,tag,123);
                assertEquals(List.of(-542,-24,488,-498,142,532),box(adjusted.getBoundingBox()),id);
                var broken=tag.copy();((CompoundTag)broken.getListOrEmpty("Children").getFirst()).putString("id","snapshot:missing_piece");
                assertTrue(assertThrows(IllegalArgumentException.class,()->decoder.load(context,broken,123)).getMessage().contains("missing_piece"));
            }
            String world=System.getProperty("retina.qa.savedMetadataWorld");
            if(world!=null) checkMca(f,Path.of(world));
        }
    }

    private static void checkMca(EndReferenceFixtures f,Path root) throws Exception {
        if(BuiltInRegistries.STRUCTURE_PIECE.getValue(Identifier.parse("retina:template"))==null) FortressAssemblyTest.registerPiece();
        try(var world=NativeWorldDefinition.open(root.resolve("world/retina.dat"))) {
            var metadata=JsonParser.parseString(world.metadata()).getAsJsonObject();
            long seed=metadata.get("seed").getAsLong();int starts=0,pieces=0;
            var families=new TreeSet<String>();var pieceKinds=new TreeSet<String>();
            // Null template manager is deliberate: no resource callback is available.
            var context=new StructurePieceSerializationContext(f.resources,f.registry,null);
            for(var dimension:List.of(Level.OVERWORLD,Level.NETHER)) {
                var summary=metadata.getAsJsonObject("dimensions").getAsJsonObject(dimension.identifier().toString()).getAsJsonObject("profile_metadata");
                var decoder=SavedStructureStarts.fromMetadata(summary);
                var definitions=new HashMap<String,JsonObject>();
                for(var value:summary.getAsJsonObject("structures").getAsJsonArray("definitions"))
                    definitions.put(value.getAsJsonObject().get("id").getAsString(),value.getAsJsonObject());
                Path folder=root.resolve(dimension.equals(Level.OVERWORLD)?"captured/region":"captured/DIM-1/region");
                try(var storage=new RegionFileStorage(new RegionStorageInfo("saved-metadata",dimension,"chunk"),folder,false)) {
                    for(int z=32;z<64;z++)for(int x=-64;x<-32;x++) {
                        var at=new ChunkPos(x,z);var tag=Objects.requireNonNull(storage.read(at),"missing actual MCA chunk "+at);
                        var raw=tag.getCompoundOrEmpty("structures");var decoded=decoder.read(context,raw,seed);
                        for(String key:raw.getCompoundOrEmpty("starts").keySet()) {
                            var saved=raw.getCompoundOrEmpty("starts").getCompoundOrEmpty(key);
                            if(saved.getStringOr("id","").equals("INVALID"))continue;
                            var identity=f.registry.lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(key));
                            var start=Objects.requireNonNull(decoded.get(identity));
                            assertEquals(saved,start.createTag(context,at),key+" at "+at);
                            var adjustment=definitions.get(key).getAsJsonObject("config").get("terrain_adaptation");
                            int margin=adjustment==null||adjustment.getAsString().equals("none")?0:12;
                            var container=new PiecesContainer(start.getPieces());
                            assertEquals(box(container.calculateBoundingBox().inflatedBy(margin)),box(start.getBoundingBox()));
                            families.add(key);starts++;pieces+=start.getPieces().size();
                            for(var piece:saved.getListOrEmpty("Children"))pieceKinds.add(((CompoundTag)piece).getStringOr("id",""));
                        }
                    }
                }
            }
            assertTrue(starts>0&&pieces>starts,"actual generated starts and pieces");
            System.out.println("QA_EVT "+new Gson().toJson(Map.of("event","saved_structure_mca_metadata","starts",starts,"pieces",pieces,"structures",families,"piece_types",pieceKinds,"live_template_manager",false)));
        }
    }
    private static List<Integer> box(BoundingBox b) {return List.of(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ());}
}
