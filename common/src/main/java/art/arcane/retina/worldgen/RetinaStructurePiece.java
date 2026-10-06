package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

/** Saved bounds for Rust-placed pieces: locate, spawn overrides and references use these normally. */
public final class RetinaStructurePiece extends StructurePiece {
    public static final StructurePieceType TYPE = (context, tag) -> new RetinaStructurePiece(tag);
    private final String template;

    public static void register() {
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, Retina.id("template"), TYPE);
    }

    private RetinaStructurePiece(CompoundTag tag) {
        super(TYPE, tag);
        template = tag.getStringOr("template", "");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("template", template);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox bounds, ChunkPos chunk, BlockPos origin) {
        // Rust already placed the blocks and NBT in each intersecting chunk.
    }
}
