package art.arcane.retina;

import art.arcane.retina.platform.RetinaPlatform;
import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.worldgen.TerrainWorldgen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Retina {
    public static final String MOD_ID = "retina";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Retina() { }

    public static void initialize(RetinaPlatform platform) {
        RetinaConfiguration.initialize(platform.configDirectory());
        var settings = RetinaConfiguration.get();
        art.arcane.retina.core.NativeLibrary.configureDebug(settings.debug());
        art.arcane.retina.core.NativeLibrary.allowGpuBackends(settings.allowDirectx(), settings.allowMetal(), settings.allowVulkan());
        if (!RetinaConfiguration.get().worldgenEnabled()) LOGGER.info("Retina worldgen disabled: all GPU backends are disallowed");
        TerrainWorldgen.initialize(platform);
        LOGGER.info("Retina initialized");
        debug("MCA parallelism: {} (configured {}, available processors {})", RetinaConfiguration.get().regionParallelism(),
                RetinaConfiguration.get().mcaParallelism(), Runtime.getRuntime().availableProcessors());
    }

    /** Explicit diagnostics switch, independent of the loader's global log level. */
    public static void debug(String message, Object... arguments) {
        if (RetinaConfiguration.get().debug()) LOGGER.info("[debug] " + message, arguments);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
