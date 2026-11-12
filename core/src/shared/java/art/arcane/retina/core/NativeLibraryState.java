package art.arcane.retina.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/** Native image selection and process-wide policy shared by the Java transports. */
final class NativeLibraryState {
    private NativeLibraryState() { }

    private static int allowedBackends = 7;
    private static boolean backendFrozen;
    private static boolean debug;

    /** Configure diagnostics before native initialization, without loading the library. */
    static synchronized void configureDebug(boolean enabled) {
        if (backendFrozen && enabled != debug)
            throw new IllegalStateException("Restart Retina to change debug logging");
        debug = enabled;
    }

    /** Set before native loading; does not load the library or probe hardware. */
    static synchronized void allowGpuBackends(boolean directx, boolean metal, boolean vulkan) {
        int mask = (vulkan ? 1 : 0) | (directx ? 2 : 0) | (metal ? 4 : 0);
        if (backendFrozen && mask != allowedBackends)
            throw new IllegalStateException("Retina GPU backends already configured; restart to change them");
        allowedBackends = mask;
    }

    static int backendSelection(int allowed, String override) {
        int selected = override == null ? 0 : backendCode(override);
        int mask = selected == 0 ? 7 : selected == 4 ? 0 : 1 << (selected - 1);
        // Empty is intentional: never enable a backend excluded by configuration.
        return 0x100 | (allowed & mask);
    }

    static synchronized void configure(IntUnaryOperator backend, IntConsumer diagnostics, Runnable flush) {
        String override = System.getProperty("retina.gpuBackend", System.getenv("RETINA_GPU_BACKEND"));
        int code = backendSelection(allowedBackends, override);
        backendFrozen = true;
        if (backend.applyAsInt(code) != 0) {
            throw new IllegalStateException("Retina GPU backend could not be configured; restart to change it");
        }
        diagnostics.accept(debug ? 1 : 0);
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { flush.run(); }
                catch (Throwable error) { System.err.println("Retina cache shutdown flush failed: " + error); }
            }, "retina-cache-shutdown"));
        } catch (IllegalStateException shuttingDown) {
            // Loading during JVM shutdown must still permit the requested native call.
        }
    }

    static int backendCode(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> 0;
            case "vulkan" -> 1;
            case "dx12" -> 2;
            case "metal" -> 3;
            case "cpu" -> 4;
            default -> throw new IllegalArgumentException("Unknown retina.gpuBackend: " + value
                    + "; use auto, cpu, vulkan, dx12 or metal");
        };
    }

    /** Digest of the selected native image, including an explicit development override. */
    static byte[] imageDigest(Path image) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(image), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static Path extract() {
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
        try (var stream = NativeLibraryState.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Native library is not packaged for " + os + "-" + arch + ": " + resource);
            Path directory = Files.createTempDirectory("retina-native-");
            Path library = directory.resolve(name);
            Files.copy(stream, library);
            directory.toFile().deleteOnExit(); library.toFile().deleteOnExit();
            return library;
        } catch (IOException error) { throw new IllegalStateException("Could not load Retina's native library", error); }
    }
}
