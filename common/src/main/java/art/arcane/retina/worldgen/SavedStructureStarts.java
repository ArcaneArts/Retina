package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import art.arcane.retina.mixin.StructureStartAccessor;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import java.util.*;

/** Saved native metadata must not load generation rules from today's templates. */
public final class SavedStructureStarts {
    public static final SavedStructureStarts EMPTY=new SavedStructureStarts(Map.of());
    private final Map<String,Integer> endCities;
    private SavedStructureStarts(Map<String,Integer> endCities){this.endCities=Map.copyOf(endCities);}

    static SavedStructureStarts fromMetadata(JsonObject metadata) {
        var margins=new HashMap<String,Integer>();
        for(var value:metadata.getAsJsonObject("structures").getAsJsonArray("definitions")) {
            var definition=value.getAsJsonObject();
            if(!definition.get("kind").getAsString().equals("end_city")
                    || definition.has("unimplemented")&&definition.get("unimplemented").getAsBoolean())continue;
            var adjustment=definition.getAsJsonObject("config").get("terrain_adaptation");
            margins.put(definition.get("id").getAsString(),adjustment!=null&&!adjustment.getAsString().equals("none")?12:0);
        }
        return margins.isEmpty()?EMPTY:new SavedStructureStarts(margins);
    }

    public boolean hasFrozenPieces(){return !endCities.isEmpty();}

    public StructureStart load(StructurePieceSerializationContext context,CompoundTag tag,long seed) {
        String id=tag.getStringOr("id","");
        var margin=endCities.get(id);
        if(margin==null)return StructureStart.loadStaticStart(context,tag,seed);
        var structure=context.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(id));
        if(structure==null)throw new IllegalArgumentException("Unknown saved End city structure "+id);
        var pieces=new ArrayList<StructurePiece>();
        for(var child:tag.getListOrEmpty("Children")) {
            if(!(child instanceof CompoundTag piece) || !piece.getStringOr("id","").equals("minecraft:ecp"))
                throw new IllegalArgumentException("Saved native End city requires ECP children: "+id);
            pieces.add(new EndCityPiece(piece));
        }
        if(pieces.isEmpty())return StructureStart.INVALID_START;
        var container=new PiecesContainer(pieces);
        var start=new StructureStart(structure,new ChunkPos(tag.getIntOr("ChunkX",0),tag.getIntOr("ChunkZ",0)),tag.getIntOr("references",0),container);
        ((StructureStartAccessor)(Object)start).retina$savedBounds(container.calculateBoundingBox().inflatedBy(margin));
        return start;
    }

    /** Same map contract as SerializableChunkData.unpackStructureStart. */
    public Map<Structure,StructureStart> read(StructurePieceSerializationContext context,CompoundTag structureData,long seed) {
        var result=new HashMap<Structure,StructureStart>();
        var registry=context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var starts=structureData.getCompoundOrEmpty("starts");
        for(String key:starts.keySet()) {
            var id=Identifier.tryParse(key);var identity=id==null?null:registry.getValue(id);
            if(identity==null){Retina.LOGGER.error("Unknown structure start: {}",key);continue;}
            var start=load(context,starts.getCompoundOrEmpty(key),seed);
            if(start!=null)result.put(identity,start);
        }
        return result;
    }

    private static final class EndCityPiece extends StructurePiece {
        private final CompoundTag saved;
        private final Rotation savedRotation;
        EndCityPiece(CompoundTag tag) {
            super(StructurePieceType.END_CITY_PIECE,tag);
            saved=tag.copy();savedRotation=Rotation.valueOf(tag.getStringOr("Rot","NONE"));
        }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
            for(String key:saved.keySet())if(!Set.of("id","BB","O","GD").contains(key))tag.put(key,saved.get(key).copy());
        }
        @Override public Rotation getRotation(){return savedRotation;}
        @Override public void move(int x,int y,int z) {
            super.move(x,y,z);
            saved.putInt("TPX",saved.getIntOr("TPX",0)+x);saved.putInt("TPY",saved.getIntOr("TPY",0)+y);saved.putInt("TPZ",saved.getIntOr("TPZ",0)+z);
        }
        @Override public void postProcess(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,
                                         RandomSource random,BoundingBox box,ChunkPos chunk,BlockPos reference) {
            throw new UnsupportedOperationException("Saved Retina pieces are metadata; native generation owns placement");
        }
    }
}
