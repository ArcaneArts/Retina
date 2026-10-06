package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Loaded light properties and exact paired face coverage, uploaded once per palette. */
final class LightingProfile {
    static JsonObject export(Iterable<BlockState> palette, boolean sky) {
        var shapes = new ArrayList<VoxelShape>();
        var ids = new LinkedHashMap<List<AABB>, Integer>();
        var states = new JsonArray();
        for (var state : palette) {
            var row = new JsonArray();
            row.add(state.getLightDampening() | state.getLightEmission() << 4);
            for (var direction : Direction.values()) {
                var face = LightEngine.getOcclusionShape(state, direction);
                var boxes = List.copyOf(face.toAabbs());
                int id = ids.computeIfAbsent(boxes, ignored -> { shapes.add(face); return shapes.size() - 1; });
                row.add(id);
            }
            row.add(0); // Eight u32s match the storage-buffer structure.
            states.add(row);
        }
        int stride = (shapes.size() + 31) / 32;
        var blocked = new JsonArray();
        for (var from : shapes) for (int word = 0; word < stride; word++) {
            int bits = 0;
            for (int bit = 0; bit < 32 && word * 32 + bit < shapes.size(); bit++)
                if (Shapes.faceShapeOccludes(from, shapes.get(word * 32 + bit))) bits |= 1 << bit;
            blocked.add(Integer.toUnsignedLong(bits));
        }
        var result = new JsonObject();
        result.addProperty("sky", sky);
        result.addProperty("faces", shapes.size());
        result.add("states", states);
        result.add("blocked", blocked);
        return result;
    }
}
