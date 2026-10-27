package art.arcane.retina.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** One library image shared by definition I/O and the GPU bridge. Loading is GPU-independent. */
public final class NativeLibrary {
    private NativeLibrary() { }
    private static final class Holder {
        static final SymbolLookup SYMBOLS = SymbolLookup.libraryLookup(extract(), Arena.global());
    }
    public static SymbolLookup symbols() { return Holder.SYMBOLS; }

    private static Path extract() {
        String override = System.getProperty("retina.native.path");
        if (override != null) return Path.of(override).toAbsolutePath();
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os = osName.contains("mac") ? "macos" : osName.contains("win") ? "windows" : "linux";
        String archName = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String arch = switch (archName) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> archName;
        };
        String name = System.mapLibraryName("retina_worldgen");
        String resource = "/natives/" + os + "-" + arch + "/" + name;
        try (var stream = NativeLibrary.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Native library is not packaged for " + os + "-" + arch + ": " + resource);
            Path directory = Files.createTempDirectory("retina-native-");
            Path library = directory.resolve(name);
            Files.copy(stream, library);
            directory.toFile().deleteOnExit(); library.toFile().deleteOnExit();
            return library;
        } catch (IOException error) { throw new IllegalStateException("Could not load Retina's native library", error); }
    }
}
