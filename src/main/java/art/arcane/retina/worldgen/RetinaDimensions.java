package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import java.util.Map;

/** Packs can replace dimensions during bake; preserve an explicitly selected Retina preset. */
public final class RetinaDimensions {
    private RetinaDimensions() { }

    public static Registry<LevelStem> retainSelection(Map<ResourceKey<LevelStem>, LevelStem> selected, Registry<LevelStem> packs) {
        boolean changed = packs.registryKeySet().stream().anyMatch(key -> selected.containsKey(key)
                && selected.get(key).generator() instanceof RetinaChunkGenerator
                && !(packs.getValueOrThrow(key).generator() instanceof RetinaChunkGenerator));
        if (!changed) return packs;
        var result = new MappedRegistry<LevelStem>(Registries.LEVEL_STEM, packs.registryLifecycle());
        for (var key : packs.registryKeySet()) {
            var stem = packs.getValueOrThrow(key);
            var choice = selected.get(key);
            if (choice != null && choice.generator() instanceof RetinaChunkGenerator retina
                    && !(stem.generator() instanceof RetinaChunkGenerator)) {
                stem = new LevelStem(stem.type(), retina.withDatapackGenerator(stem.generator(), stem.type().value()));
                Retina.LOGGER.info("Retaining Retina {} for {} with {} datapack biomes", retina.mode(), key.identifier(), stem.generator().getBiomeSource().possibleBiomes().size());
            }
            result.register(key, stem, packs.registrationInfo(key).orElseThrow());
        }
        return result.freeze();
    }
}
