package art.arcane.retina.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Process settings are loaded before worldgen hooks create any workers or budgets. */
public final class RetinaConfiguration {
    private static volatile RetinaConfig current = RetinaConfig.DEFAULT;

    private RetinaConfiguration() { }

    public static RetinaConfig get() { return current; }

    public static void initialize(Path directory) {
        try {
            current = ConfigFile.loadDirectory(directory);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not load Retina configuration from " + directory, error);
        }
    }

    /** Alternative hosts select their own path/format before starting any generation. */
    public static void initialize(Path file, ConfigCodec codec) {
        try {
            current = ConfigFile.loadOrCreate(file, codec);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not load Retina configuration " + file, error);
        }
    }
}
