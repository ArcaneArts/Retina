package art.arcane.retina.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VanillaUtilityModsTest {
    @TempDir Path temporary;

    @Test void namesVersionsAndUnreadableOriginsCannotAssertNeutrality() throws Exception {
        Path fake = temporary.resolve("Chunky.jar");
        Files.writeString(fake, "not the audited artifact");
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky", "1.5.3", List.of(fake)));
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky", "1.5.3", List.of(temporary)));
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky", "1.5.3", List.of(fake.resolve("missing"))));
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky", "1.5.3", List.of()));
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky", "1.5.3", List.of(fake, fake)));
        assertFalse(VanillaUtilityMods.supported("fabric", "26.3", "chunky-addons", "1.5.3", List.of(fake)));
    }

    @Test @EnabledIfEnvironmentVariable(named = "RETINA_TEST_CHUNKY_ARTIFACTS", matches = ".+")
    void exactPublishedArtifactsOnlyAndRepackagedCopiesUseLiveExport() throws Exception {
        Path root = Path.of(System.getenv("RETINA_TEST_CHUNKY_ARTIFACTS"));
        for (var sample : List.of(new String[]{"fabric", "1.5.3", "Chunky-Fabric-1.5.3.jar"},
                new String[]{"neoforge", "1.5.4", "Chunky-NeoForge-1.5.4.jar"})) {
            Path original = root.resolve(sample[2]);
            assertTrue(VanillaUtilityMods.supported(sample[0], "26.3", "chunky", sample[1], List.of(original)));
            assertFalse(VanillaUtilityMods.supported(sample[0], "future", "chunky", sample[1], List.of(original)));
            assertFalse(VanillaUtilityMods.supported(sample[0], "26.3", "chunky", "future", List.of(original)));
            Path modified = temporary.resolve(sample[2]);
            Files.copy(original, modified);
            Files.writeString(modified, "repackaged", java.nio.file.StandardOpenOption.APPEND);
            assertFalse(VanillaUtilityMods.supported(sample[0], "26.3", "chunky", sample[1], List.of(modified)));
        }
    }
}
