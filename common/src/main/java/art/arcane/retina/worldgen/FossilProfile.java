package art.arcane.retina.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.LinkedHashMap;

/** Loaded template geometry and block predicates; native code searches columns and places fossils. */
final class FossilProfile {
    static JsonObject export(StructureProfile profile) {
        var out = new JsonObject(); var templates = new JsonArray();
        for (int i = 1; i <= 14; i++) {
            String id = "minecraft:nether_fossils/fossil_" + i;
            int template = profile.template(id);
            if (template < 0) throw new IllegalStateException("Missing Nether fossil template " + id);
            templates.add(template);
        }
        out.add("templates", templates);
        out.add("dried_ghast", profile.rotations(Blocks.DRIED_GHAST.defaultBlockState()));
        return out;
    }

    static void finish(JsonObject out, LinkedHashMap<BlockState, Integer> materials) {
        var floor = new JsonArray(); var air = new JsonArray();
        materials.forEach((state, id) -> {
            if (state.isAir()) air.add(id);
            if (state.is(Blocks.SOUL_SAND) || state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) floor.add(id);
        });
        out.add("floor", floor); out.add("air", air);
    }
}
