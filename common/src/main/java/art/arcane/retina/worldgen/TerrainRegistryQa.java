package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in checks that loader biome modifications reach the actual native profile. */
public final class TerrainRegistryQa {
    private TerrainRegistryQa() { }

    public static void check(RetinaChunkGenerator generator) {
        String feature = System.getProperty("retina.qa.expectedFeature", "");
        String output = System.getProperty("retina.qa.profileOutput", "");
        if (feature.isBlank() && output.isBlank()) return;
        var profile = generator.profile();
        if (profile == null) throw new IllegalStateException("Registry QA requires a loaded biome profile");
        if (!feature.isBlank()) {
            boolean registered = profile.biomes().stream().anyMatch(biome -> biome.value().getGenerationSettings()
                    .features().stream().anyMatch(step -> step.stream().anyMatch(placed -> placed.unwrapKey()
                            .map(key -> key.identifier().toString().equals(feature)).orElse(false))));
            boolean exported = JsonParser.parseString(profile.json()).getAsJsonObject().getAsJsonArray("decorations")
                    .asList().stream().anyMatch(recipe -> recipe.getAsJsonObject().get("source").getAsString().startsWith(feature));
            if (!registered || !exported) throw new IllegalStateException("Effective biome feature missing from native profile: " + feature
                    + " (registered=" + registered + ", exported=" + exported + ")");
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_effective_registry_feature\",\"status\":\"pass\",\"context\":{\"feature\":\"{}\"}}", feature);
        }
        if (!output.isBlank()) {
            try {
                Path path = Path.of(output);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, profile.json());
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        }
    }
}
