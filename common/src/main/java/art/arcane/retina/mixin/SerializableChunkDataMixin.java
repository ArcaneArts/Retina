package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaChunkGenerator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.Map;

/** Native MCA reopen must use the same saved geometry as initial installation. */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
    @WrapOperation(method="read",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;unpackStructureStart(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;Lnet/minecraft/nbt/CompoundTag;J)Ljava/util/Map;"))
    private Map<Structure,StructureStart> retina$savedStarts(StructurePieceSerializationContext context,CompoundTag tag,long seed,
            Operation<Map<Structure,StructureStart>> original,@Local(argsOnly=true) ServerLevel level) {
        if(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator retina && retina.hasFrozenStructurePieces())
            return retina.readSavedStructureStarts(context,tag,seed);
        return original.call(context,tag,seed);
    }
}
