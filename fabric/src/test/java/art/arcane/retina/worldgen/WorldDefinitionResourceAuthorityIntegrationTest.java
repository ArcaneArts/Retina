package art.arcane.retina.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;

/** Runs frozen structure installation with the real loader's accessor mixins. */
@EnabledIfSystemProperty(named="retina.native.path", matches=".+")
class WorldDefinitionResourceAuthorityIntegrationTest {
    @Test void liveDatapackChangesDoNotOverrideSavedGeneration() throws Exception {
        EndFixtureBootstrap.initialize();
        WorldDefinitionResourceAuthorityTest.main(new String[]{Path.of("build/world-definition-resource-authority").toAbsolutePath().toString()});
    }
}
