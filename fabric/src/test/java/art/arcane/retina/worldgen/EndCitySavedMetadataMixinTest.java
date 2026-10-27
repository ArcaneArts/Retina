package art.arcane.retina.worldgen;

import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the source fixtures with the actual loader-transformed Minecraft classes. */
class EndCitySavedMetadataMixinTest {
    @Test void savedCityGeometrySurvivesLiveTemplateAndRegistryChanges(@TempDir Path directory) throws Exception {
        EndFixtureBootstrap.initialize();
        SavedStructureMetadataChecks.run();
        EndCitySavedMetadataTest.main(new String[]{directory.toString(),System.getProperty("retina.end.cityFixture")});
        assertTrue(Arrays.stream(SerializableChunkData.class.getDeclaredMethods())
                .anyMatch(method->method.getName().contains("retina$savedStarts")),"MCA read interception applied");
    }
}
