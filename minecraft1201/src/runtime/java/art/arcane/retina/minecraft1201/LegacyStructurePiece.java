package art.arcane.retina.minecraft1201;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/** Restores the saved bounds of a piece whose blocks were installed by the native engine. */
public final class LegacyStructurePiece extends StructurePiece {
    public static final ResourceLocation ID = new ResourceLocation("retina", "template");
    public static final StructurePieceType TYPE = (context, tag) -> new LegacyStructurePiece(tag);
    private final String template;

    private LegacyStructurePiece(CompoundTag tag) {
        super(TYPE, tag);
        template = tag.getString("template");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("template", template);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox bounds, ChunkPos chunk, BlockPos origin) {
        // The native engine has already placed this piece in every intersecting chunk.
    }
}
