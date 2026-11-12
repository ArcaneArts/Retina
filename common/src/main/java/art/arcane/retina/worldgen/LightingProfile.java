package art.arcane.retina.worldgen;

import art.arcane.retina.definition.LightingProfileModel;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Loaded light properties and exact paired face coverage, uploaded once per palette. */
final class LightingProfile {
    static JsonObject export(Iterable<BlockState> palette, boolean sky) {
        var model = new LightingProfileModel<VoxelShape, AABB>(VoxelShape::toAabbs);
        var directions = Direction.values();
        for (BlockState state : palette) {
            model.addState(state.getLightDampening(), state.getLightEmission(),
                    direction -> LightEngine.getOcclusionShape(state, directions[direction]));
        }
        return model.build(sky, Shapes::faceShapeOccludes);
    }
}
