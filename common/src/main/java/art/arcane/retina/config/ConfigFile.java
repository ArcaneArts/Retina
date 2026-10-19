package art.arcane.retina.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads existing files without rewriting comments or unknown platform-specific settings. */
public final class ConfigFile {
    private ConfigFile() { }

    public static RetinaConfig loadDirectory(Path directory) throws IOException {
        // Deterministic precedence if multiple formats exist; never hide an invalid selected file.
        for (String name : new String[]{"retina.toml", "retina.yaml", "retina.yml"}) {
            try {
                return read(directory.resolve(name), name.endsWith("toml") ? ConfigFormat.TOML : ConfigFormat.YAML);
            } catch (NoSuchFileException missing) { }
        }
        return loadOrCreate(directory.resolve("retina.toml"), ConfigFormat.TOML);
    }

    /** Future plugin hosts can choose their own filename and codec, including YAML. */
    public static RetinaConfig loadOrCreate(Path file, ConfigCodec codec) throws IOException {
        try {
            return read(file, codec);
        } catch (NoSuchFileException missing) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                codec.write(writer, RetinaConfig.DEFAULT);
            } catch (FileAlreadyExistsException raced) {
                // Another initializer supplied the file; read its actual contents below.
            }
            return read(file, codec);
        }
    }

    private static RetinaConfig read(Path file, ConfigCodec codec) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            return RetinaConfig.from(codec.read(reader));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid Retina configuration " + file + ": " + invalid.getMessage(), invalid);
        }
    }
}
