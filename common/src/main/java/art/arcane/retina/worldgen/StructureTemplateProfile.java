package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Source template transforms: processors see raw states, then mirror precedes rotation. */
final class StructureTemplateProfile {
    static void configure(JsonObject template, Mirror mirror, BlockPos pivot) {
        var transform=new JsonObject();transform.addProperty("mirror",mirror.ordinal());
        transform.add("pivot",StructureProfile.ints(pivot.getX(),pivot.getY(),pivot.getZ()));
        template.add("transform",transform);
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState,Integer> materials) {
        var transforms=new ArrayList<JsonObject>();
        for(var value:structures.getAsJsonArray("templates")) {
            var template=value.getAsJsonObject();
            if(template.has("transform"))transforms.add(template.getAsJsonObject("transform"));
        }
        if(transforms.isEmpty())return;
        // Close the dihedral group before exporting dependent flags and lighting.
        // This includes processor-created states, not just states in template palettes.
        for(var state:List.copyOf(materials.keySet()))for(var mirror:Mirror.values())for(var rotation:Rotation.values())
            materials.computeIfAbsent(state.mirror(mirror).rotate(rotation),s->materials.size());
        for(var transform:transforms) {
            var mirror=Mirror.values()[transform.get("mirror").getAsInt()];var table=new JsonArray();
            for(var state:materials.keySet()) {
                var row=new JsonArray();for(var rotation:Rotation.values())row.add(materials.get(state.mirror(mirror).rotate(rotation)));
                table.add(row);
            }
            transform.add("materials",table);
        }
    }
}
