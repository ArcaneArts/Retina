package art.arcane.retina;

import net.fabricmc.api.ModInitializer;
import art.arcane.retina.worldgen.TerrainWorldgen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Retina implements ModInitializer {
    public static final String MOD_ID = "retina";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        TerrainWorldgen.initialize();
        LOGGER.info("Retina initialized");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
