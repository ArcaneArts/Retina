package art.arcane.retina.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** One library image shared by definition I/O and the GPU bridge. Loading is GPU-independent. */
public final class NativeLibrary {
    private NativeLibrary() { }
    private static int allowedBackends = 7;
    private static boolean backendFrozen;

    /** Set before native loading; does not load the library or probe hardware. */
    public static synchronized void allowGpuBackends(boolean directx, boolean metal, boolean vulkan) {
        int mask = (vulkan ? 1 : 0) | (directx ? 2 : 0) | (metal ? 4 : 0);
        if (backendFrozen && mask != allowedBackends)
            throw new IllegalStateException("Retina GPU backends already configured; restart to change them");
        allowedBackends = mask;
    }

    static int backendSelection(int allowed, String override) {
        int selected = override == null ? 0 : backendCode(override);
        int mask = selected == 0 ? 7 : 1 << (selected - 1);
        // Empty is intentional: never enable a backend excluded by configuration.
        return 0x100 | (allowed & mask);
    }
    private static final class Holder {
        static final Path IMAGE = extract();
        static final SymbolLookup SYMBOLS = configuredSymbols(IMAGE);
    }
    public static SymbolLookup symbols() { return Holder.SYMBOLS; }

    private static synchronized SymbolLookup configuredSymbols(Path image) {
        SymbolLookup symbols = SymbolLookup.libraryLookup(image, Arena.global());
        String backend = System.getProperty("retina.gpuBackend", System.getenv("RETINA_GPU_BACKEND"));
        int code = backendSelection(allowedBackends, backend);
        backendFrozen = true;
        var configure = Linker.nativeLinker().downcallHandle(
                symbols.findOrThrow("retina_configure_gpu_backend"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        try {
            int result = (int) configure.invokeExact(code);
            if (result != 0) throw new IllegalStateException("Retina GPU backend could not be configured; restart to change it");
        } catch (RuntimeException | Error error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Could not configure Retina GPU backend", error); }
        return symbols;
    }

    static int backendCode(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> 0;
            case "vulkan" -> 1;
            case "dx12" -> 2;
            case "metal" -> 3;
            default -> throw new IllegalArgumentException("Unknown retina.gpuBackend: " + value
                    + "; use auto, vulkan, dx12 or metal");
        };
    }

    /** Digest of the selected native image, including an explicit development override. */
    public static byte[] imageDigest() throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(Holder.IMAGE), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

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
