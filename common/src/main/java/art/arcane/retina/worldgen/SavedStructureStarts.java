package art.arcane.retina.worldgen;

import art.arcane.retina.mixin.StructureStartAccessor;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private record Frozen(int margin,boolean endCity) { }
    private final Map<String,Frozen> definitions;
    private SavedStructureStarts(Map<String,Frozen> definitions){this.definitions=Map.copyOf(definitions);}

    static SavedStructureStarts fromMetadata(JsonObject metadata) {
        var margins=new HashMap<String,Frozen>();
        for(var value:metadata.getAsJsonObject("structures").getAsJsonArray("definitions")) {
            var definition=value.getAsJsonObject();
            if(definition.has("unimplemented")&&definition.get("unimplemented").getAsBoolean())continue;
            var adjustment=definition.getAsJsonObject("config").get("terrain_adaptation");
            margins.put(definition.get("id").getAsString(),new Frozen(
                    adjustment!=null&&!adjustment.getAsString().equals("none")?12:0,
                    definition.get("kind").getAsString().equals("end_city")));
        }
        return margins.isEmpty()?EMPTY:new SavedStructureStarts(margins);
    }

    public boolean hasFrozenPieces(){return !definitions.isEmpty();}

    public StructureStart load(StructurePieceSerializationContext context,CompoundTag tag,long seed) {
        String id=tag.getStringOr("id","");
        var frozen=definitions.get(id);
        if(frozen==null)return StructureStart.loadStaticStart(context,tag,seed);
        var structure=context.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(id));
        if(structure==null)throw new IllegalArgumentException("Unknown saved native structure "+id);
        var pieces=new ArrayList<StructurePiece>();
        for(var child:tag.getListOrEmpty("Children")) {
            if(!(child instanceof CompoundTag piece))
                throw new IllegalArgumentException("Saved native structure requires compound children: "+id);
            if(frozen.endCity()) {
                if(!piece.getStringOr("id","").equals("minecraft:ecp"))
                    throw new IllegalArgumentException("Saved native End city requires ECP children: "+id);
                pieces.add(new EndCityPiece(piece));
            } else pieces.add(new SavedPiece(piece));
        }
        if(pieces.isEmpty())return StructureStart.INVALID_START;
        var container=new PiecesContainer(pieces);
        var start=new StructureStart(structure,new ChunkPos(tag.getIntOr("ChunkX",0),tag.getIntOr("ChunkZ",0)),tag.getIntOr("references",0),container);
        ((StructureStartAccessor)(Object)start).retina$savedBounds(container.calculateBoundingBox().inflatedBy(frozen.margin()));
        return start;
    }

    /** Same map contract as SerializableChunkData.unpackStructureStart. */
    public Map<Structure,StructureStart> read(StructurePieceSerializationContext context,CompoundTag structureData,long seed) {
        var result=new HashMap<Structure,StructureStart>();
        var registry=context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var starts=structureData.getCompoundOrEmpty("starts");
        for(String key:starts.keySet()) {
            var id=Identifier.tryParse(key);var identity=id==null?null:registry.getValue(id);
            if(identity==null)throw new IllegalArgumentException("Unknown saved native structure "+key);
            var start=load(context,starts.getCompoundOrEmpty(key),seed);
            if(start!=null)result.put(identity,start);
        }
        return result;
    }

    /** Only registry identity is resolved; piece loaders can reread live templates. */
    private static StructurePieceType pieceType(CompoundTag tag) {
        var key=Identifier.tryParse(tag.getStringOr("id",""));
        var type=key==null?null:BuiltInRegistries.STRUCTURE_PIECE.getValue(key);
        if(type==null)throw new IllegalArgumentException("Unknown saved native structure piece "+tag.getStringOr("id",""));
        return type;
    }

    private static class SavedPiece extends StructurePiece {
        protected final CompoundTag saved;
        SavedPiece(CompoundTag tag) { this(pieceType(tag),tag); }
        SavedPiece(StructurePieceType type,CompoundTag tag) { super(type,tag);saved=tag.copy(); }
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext context,CompoundTag tag) {
            for(String key:saved.keySet())if(!Set.of("id","BB","O","GD").contains(key))tag.put(key,saved.get(key).copy());
        }
        @Override public void postProcess(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,
                                         RandomSource random,BoundingBox box,ChunkPos chunk,BlockPos reference) {
            throw new UnsupportedOperationException("Saved Retina pieces are metadata; native generation owns placement");
        }
    }

    private static final class EndCityPiece extends SavedPiece {
        private final Rotation savedRotation;
        EndCityPiece(CompoundTag tag) {
            super(StructurePieceType.END_CITY_PIECE,tag);
            savedRotation=Rotation.valueOf(tag.getStringOr("Rot","NONE"));
        }
        @Override public Rotation getRotation(){return savedRotation;}
        @Override public void move(int x,int y,int z) {
            super.move(x,y,z);
            saved.putInt("TPX",saved.getIntOr("TPX",0)+x);saved.putInt("TPY",saved.getIntOr("TPY",0)+y);saved.putInt("TPZ",saved.getIntOr("TPZ",0)+z);
        }
    }
}
