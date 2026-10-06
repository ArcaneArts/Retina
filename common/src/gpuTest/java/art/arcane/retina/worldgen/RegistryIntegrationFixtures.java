package art.arcane.retina.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.resources.ResourceManager;

/** World registries with resolved biome tags, rather than bootstrap construction holders. */
final class RegistryIntegrationFixtures {
    static RegistryAccess.Frozen load(ResourceManager resources) {
        var builtin = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var world = RegistryDataLoader.load(resources, builtin.listRegistries().toList(),
                RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
        return new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(builtin.registries(), world.registries())).freeze();
    }
}
