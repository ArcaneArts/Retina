package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Keeps Nether structure set membership and template indexes in one native profile. */
final class LegacyNetherStructures {
    private LegacyNetherStructures() { }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             WorldGenerationContext context, Function<ResourceLocation, Optional<CompoundTag>> source) {
        return export(registry, biomes, materials, context, source, Optional.empty());
    }

    static JsonObject export(RegistryAccess registry, List<Holder<Biome>> biomes, LinkedHashMap<BlockState, Integer> materials,
                             WorldGenerationContext context, Function<ResourceLocation, Optional<CompoundTag>> source,
                             Optional<Set<ResourceLocation>> allowedSets) {
        var templateOmissions = new JsonArray();
        var portalTemplates = LegacyPortalProfile.load(source, templateOmissions);
        var families = new HashMap<Class<? extends Structure>, String>(Map.of(
                NetherFortressStructure.class, "fortress", NetherFossilStructure.class, "nether_fossil",
                JigsawStructure.class, "jigsaw", RuinedPortalStructure.class, "ruined_portal"));
        var result = exportSets(registry, biomes, context, families, !portalTemplates.isEmpty(), allowedSets);
        if (has(result, "nether_fossil")) {
            var missing = LegacyNetherFossils.addTemplates(result, materials, source);
            if (missing.isPresent()) {
                families.remove(NetherFossilStructure.class);
                result = exportSets(registry, biomes, context, families, !portalTemplates.isEmpty(), allowedSets);
                templateOmissions.add("missing_template:" + missing.get());
            }
        }
        if (has(result, "fortress")) {
            result.add("fortress", LegacyFortressProfile.export(registry, materials, result.getAsJsonArray("templates")));
        }
        if (has(result, "ruined_portal")) LegacyPortalProfile.add(result, portalTemplates, materials);
        var omissions = result.getAsJsonArray("omissions");
        templateOmissions.forEach(omissions::add);
        return result;
    }

    private static JsonObject exportSets(RegistryAccess registry, List<Holder<Biome>> biomes, WorldGenerationContext context,
                                         Map<Class<? extends Structure>, String> families,
                                         boolean portalsAvailable, Optional<Set<ResourceLocation>> allowedSets) {
        return LegacyStructureSets.export(registry, biomes, families, config -> {
            if (config.get("type").getAsString().equals("minecraft:nether_fossil")) {
                LegacyFeaturePlacement.bindHeight(config.get("height"), context);
            }
        }, (kind, config) -> !kind.equals("ruined_portal") || portalsAvailable && LegacyPortalProfile.supported(config), allowedSets);
    }

    static void finish(JsonObject structures, LinkedHashMap<BlockState, Integer> materials) {
        if (structures == null) return;
        LegacyNetherFossils.finish(structures, materials);
        if (structures.has("fortress")) LegacyFortressProfile.finish(structures.getAsJsonObject("fortress"), materials);
        LegacyPortalProfile.finish(structures, materials);
    }

    private static boolean has(JsonObject result, String kind) {
        for (var entry : result.getAsJsonArray("definitions")) {
            if (entry.getAsJsonObject().get("kind").getAsString().equals(kind)) return true;
        }
        return false;
    }
}
