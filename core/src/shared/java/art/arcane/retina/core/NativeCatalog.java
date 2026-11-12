package art.arcane.retina.core;

import java.nio.file.Path;

/** Seed-independent registry input creates a persistent world, then uses the ordinary loader. */
public final class NativeCatalog {
    private NativeCatalog() { }

    public static NativeWorldDefinition create(Path catalog, String variant, long seed, Path worldRoot) {
        if (Boolean.getBoolean("retina.qa.catalog.failMaterializer")) {
            throw new IllegalStateException("Catalog materialization intentionally disabled by QA");
        }
        return NativeWorldDefinition.takeOwnership(
                NativeLibrary.definitionTransport().create(catalog, variant, seed, worldRoot));
    }
}
