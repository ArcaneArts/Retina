package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DisabledWorldgenTest {
    @TempDir Path folder;

    @Test void disabledBackendsKeepOriginalGeneratorsWithoutOpeningDefinitions() throws Exception {
        EndFixtureBootstrap.initialize();
        Path config = folder.resolve("retina.toml");
        Files.writeString(config, "allow_directx=false\nallow_metal=false\nallow_vulkan=false\n");
        RetinaConfiguration.initialize(folder);
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
            // A null server cannot supply a path or registries: disabled startup must not touch either.
            assertNull(WorldDefinitions.initialize(null));
            var builtin = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, builtin)
                    .forEach(net.minecraft.core.Registry.PendingTags::apply);
            var registry = RegistryIntegrationFixtures.load(resources);
            for (var preset : List.of(WorldPresets.NORMAL, WorldPresets.FLAT)) {
                var dimensions = registry.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset)
                        .value().createWorldDimensions().dimensions();
                for (var entry : dimensions.entrySet()) {
                    var dimension = entry.getKey().equals(LevelStem.OVERWORLD) ? Level.OVERWORLD
                            : entry.getKey().equals(LevelStem.NETHER) ? Level.NETHER : Level.END;
                    var stem = entry.getValue();
                    assertSame(stem.generator(), RetinaGenerators.adapt(null, dimension, stem, stem.generator()));
                    assertSame(stem.generator(), RetinaGenerators.adapt(dimension, stem, stem.generator()));
                }
            }
            var biome = registry.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            var legacy = new RetinaChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),
                    -64, 384, 64, 48, .008F, "mca");
            var error = assertThrows(IllegalStateException.class,
                    () -> RetinaGenerators.adapt(null, Level.OVERWORLD, null, legacy));
            assertTrue(error.getMessage().contains("legacy save"));
            assertFalse(Files.exists(folder.resolve("retina.dat")));
        } finally {
            Files.writeString(config, "");
            RetinaConfiguration.initialize(folder);
        }
    }
}
