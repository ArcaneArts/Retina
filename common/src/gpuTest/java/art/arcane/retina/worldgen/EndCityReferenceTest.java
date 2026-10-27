package art.arcane.retina.worldgen;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.*;
import java.util.*;

/** Independent actual structure contexts, template geometry, and saved city pieces. */
public final class EndCityReferenceTest {
    private static final String[] NAMES={"base_floor","base_roof","bridge_end","bridge_gentle_stairs","bridge_piece","bridge_steep_stairs","fat_tower_base","fat_tower_middle","fat_tower_top","second_floor_1","second_floor_2","second_roof","ship","third_floor_1","third_floor_2","third_roof","tower_base","tower_piece","tower_top"};
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var report=new JsonObject();var cases=new JsonArray();
        try(var f=new EndReferenceFixtures();var storage=LevelStorageSource.createDefault(out.resolve("template-storage")).createAccess("templates")) {
            var templates=new StructureTemplateManager(f.resources,storage,DataFixers.getDataFixer(),f.registry.lookupOrThrow(Registries.BLOCK));
            var context=new StructurePieceSerializationContext(f.resources,f.registry,templates);
            var city=f.registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.END_CITY).value();
            var set=f.registry.lookupOrThrow(Registries.STRUCTURE_SET).getValueOrThrow(BuiltinStructureSets.END_CITIES);
            var placement=(RandomSpreadStructurePlacement)set.placement();
            report.add("placement",StructurePlacement.CODEC.encodeStart(f.registry.createSerializationContext(JsonOps.INSTANCE),placement).getOrThrow());
            var validBiomes=new JsonArray();city.biomes().forEach(b->validBiomes.add(b.unwrapKey().orElseThrow().identifier().toString()));report.add("valid_biomes",validBiomes);
            var sizes=new JsonObject();for(String name:NAMES) {
                var size=templates.getOrCreate(Identifier.withDefaultNamespace("end_city/"+name)).getSize();
                require(size.getX()>0&&size.getY()>0&&size.getZ()>0,"actual source template loaded: "+name);
                sizes.add(name,EndReferenceFixtures.ints(size.getX(),size.getY(),size.getZ()));
            }
            var catalog=new JsonObject();catalog.add("sizes",sizes);report.add("catalog",catalog);
            for(long seed:new long[]{123456789,42}) {
                var state=f.state(seed);boolean ship=false,noShip=false;
                for(int i=0;i<80&&!(ship&&noShip);i++) {
                    var at=placement.getPotentialStructureChunk(seed,(i%10-5)*20,(i/10-4)*20);
                    var row=capture(f,templates,context,city,state,seed,at);cases.add(row);
                    if(row.get("valid").getAsBoolean()){if(row.get("ship").getAsBoolean())ship=true;else noShip=true;}
                }
                require(ship&&noShip,"actual city with and without ship");
            }
            for(long seed:new long[]{0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
                var state=f.state(seed);
                for(var grid:new int[][]{{-5,-4},{-2,-4},{4,-4},{3,2}}) {
                    cases.add(capture(f,templates,context,city,state,seed,placement.getPotentialStructureChunk(seed,grid[0]*20,grid[1]*20)));
                }
                int accepted=0;
                for(int i=0;i<200&&accepted<2;i++) {
                    var row=capture(f,templates,context,city,state,seed,placement.getPotentialStructureChunk(seed,(i%16-8)*20,(i/16-8)*20));
                    if(row.get("valid").getAsBoolean()) {cases.add(row);accepted++;}
                }
                require(accepted==2,"source cities exercise signed seed "+seed);
            }
        }
        report.addProperty("schema","retina.end.city-reference.v1");report.addProperty("minecraft",SharedConstants.getCurrentVersion().name());report.addProperty("full_regions_generated",0);report.add("cases",cases);
        report.addProperty("payload_sha256",EndReferenceFixtures.hash(report));
        Files.writeString(out.resolve("reference.json"),new GsonBuilder().setPrettyPrinting().create().toJson(EndReferenceFixtures.canonical(report))+"\n");
        System.out.println("QA_EVT {\"event\":\"end_city_reference\",\"cases\":"+cases.size()+",\"status\":\"pass\"}");
    }
    static JsonObject capture(EndReferenceFixtures f,StructureTemplateManager templates,StructurePieceSerializationContext serialization,Structure city,RandomState state,long seed,ChunkPos at) {
        var context=new Structure.GenerationContext(f.registry,f.generator,f.biomes,state.createClimateSampler(SamplerContext.EMPTY_UNCACHED),state,templates,seed,at,f.height,city.biomes()::contains);
        var row=new JsonObject();row.addProperty("seed",seed);row.add("chunk",EndReferenceFixtures.ints(at.x(),at.z()));
        boolean eligible=context.couldValidBiomeExistInTerrainColumn(at.getBlockX(7),at.getBlockZ(7));row.addProperty("column_biome_eligible",eligible);
        if(eligible) {
            var preview=new WorldgenRandom(new LegacyRandomSource(0));preview.setLargeFeatureSeed(seed,at.x(),at.z());
            var rotation=Rotation.getRandom(preview);int dx=rotation==Rotation.CLOCKWISE_90||rotation==Rotation.CLOCKWISE_180?-5:5;int dz=rotation==Rotation.CLOCKWISE_180||rotation==Rotation.COUNTERCLOCKWISE_90?-5:5;
            var heights=new JsonArray();for(var delta:new int[][]{{0,0},{0,dz},{dx,0},{dx,dz}}) {
                int x=at.getBlockX(7)+delta[0],z=at.getBlockZ(7)+delta[1];heights.add(EndReferenceFixtures.ints(x,z,f.generator.getFirstOccupiedHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,f.height,state)));
            }
            row.add("corner_heights",heights);
        }
        var stub=city.findValidGenerationPoint(context);row.addProperty("valid",stub.isPresent());
        if(stub.isPresent()) {
            var pieces=stub.get().getPiecesBuilder().build().pieces();var tags=new JsonArray();boolean ship=false;
            for(var piece:pieces) {var tag=piece.createTag(serialization);ship|=tag.getStringOr("Template","").equals("ship");tags.add(StructureProfile.tag(tag));}
            row.add("start",EndReferenceFixtures.ints(stub.get().position().getX(),stub.get().position().getY(),stub.get().position().getZ()));row.addProperty("ship",ship);row.add("pieces",tags);
        }
        row.addProperty("next_long",context.random().nextLong());return row;
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
