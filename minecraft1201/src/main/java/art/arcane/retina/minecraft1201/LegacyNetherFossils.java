package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Function;

/** Binds 1.20.1 fossil placements and normalized templates to the shared native floor search. */
final class LegacyNetherFossils {
    private LegacyNetherFossils() { }

    static Optional<ResourceLocation> addTemplates(JsonObject result, LinkedHashMap<BlockState, Integer> materials,
                                                   Function<ResourceLocation, Optional<CompoundTag>> source) {
        var rawTemplates = new ArrayList<CompoundTag>(14);
        for (int index = 1; index <= 14; index++) {
            var id = id(index);
            var template = source.apply(id);
            if (template.isEmpty()) return Optional.of(id);
            rawTemplates.add(template.get());
        }
        var templates = result.getAsJsonArray("templates");
        var omissions = result.getAsJsonArray("omissions");
        var indices = new JsonArray();
        for (int index = 0; index < rawTemplates.size(); index++) {
            indices.add(templates.size());
            templates.add(LegacyTemplateNbt.template(id(index + 1).toString(), rawTemplates.get(index), 1, materials, omissions));
        }

        var fossils = new JsonObject();
        fossils.add("templates", indices);
        fossils.addProperty("structure_context_random", true);
        fossils.addProperty("vanilla_piece_nbt", true);
        result.add("fossils", fossils);
        return Optional.empty();
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (!structures.has("fossils")) return;
        var fossils = structures.getAsJsonObject("fossils");
        var floor = new JsonArray();
        var air = new JsonArray();
        materials.forEach((state, index) -> {
            if (state.isAir()) air.add(index);
            if (state.is(Blocks.SOUL_SAND) || state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) {
                floor.add(index);
            }
        });
        fossils.add("floor", floor);
        fossils.add("air", air);
    }

    private static ResourceLocation id(int index) {
        return new ResourceLocation("nether_fossils/fossil_" + index);
    }

}
