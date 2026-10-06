package art.arcane.retina;

import art.arcane.retina.platform.RetinaPlatform;
import art.arcane.retina.worldgen.TerrainWorldgen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Retina {
    public static final String MOD_ID = "retina";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Retina() { }

    public static void initialize(RetinaPlatform platform) {
        TerrainWorldgen.initialize(platform);
        LOGGER.info("Retina initialized");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
