package art.arcane.retina.minecraft1201;

import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Keeps Nether structure set membership and template indexes in one native profile. */
final class LegacyNetherStructures {
    private LegacyNetherStructures() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             WorldGenerationContext context, Function<ResourceLocation, Optional<CompoundTag>> source) {
        var result = LegacyStructureSets.export(registry, biomes,
                Map.of(NetherFortressStructure.class, "fortress", NetherFossilStructure.class, "nether_fossil", JigsawStructure.class, "jigsaw"),
                config -> {
                    if (config.get("type").getAsString().equals("minecraft:nether_fossil")) {
                        LegacyFeaturePlacement.bindHeight(config.get("height"), context);
                    }
                });
        if (has(result, "nether_fossil")) {
            var missing = LegacyNetherFossils.addTemplates(result, materials, source);
            if (missing.isPresent()) {
                result = LegacyStructureSets.export(registry, biomes,
                        Map.of(NetherFortressStructure.class, "fortress", JigsawStructure.class, "jigsaw"), config -> { });
                result.getAsJsonArray("omissions").add("missing_template:" + missing.get());
            }
        }
        if (has(result, "fortress")) {
            result.add("fortress", LegacyFortressProfile.export(registry, materials, result.getAsJsonArray("templates")));
        }
        return result;
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (structures == null) return;
        LegacyNetherFossils.finish(structures, materials);
        if (structures.has("fortress")) LegacyFortressProfile.finish(structures.getAsJsonObject("fortress"), materials);
    }

    private static boolean has(JsonObject result, String kind) {
        for (var entry : result.getAsJsonArray("definitions")) {
            if (entry.getAsJsonObject().get("kind").getAsString().equals(kind)) return true;
        }
        return false;
    }
}
