package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.LightingProfileModel;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Loaded light properties and exact paired face coverage, uploaded once per palette. */
final class LightingExport {
    static JsonObject export(Iterable<BlockState> palette, boolean sky) {
        var model = new LightingProfileModel<VoxelShape, AABB>(VoxelShape::toAabbs);
        var directions = Direction.values();
        for (BlockState state : palette) {
            model.addState(state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), state.getLightEmission(),
                    direction -> LightEngine.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, directions[direction]));
        }
        return model.build(sky, Shapes::faceShapeOccludes);
    }
}
