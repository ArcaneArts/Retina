package art.arcane.retina.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** FFM entry point. Definition I/O and terrain calls use one image. */
public final class NativeLibrary {
    private NativeLibrary() { }

    static DefinitionTransport definitionTransport() { return FfmDefinitionTransport.INSTANCE; }

    public static void configureDebug(boolean enabled) {
        NativeLibraryState.configureDebug(enabled);
    }

    public static void allowGpuBackends(boolean directx, boolean metal, boolean vulkan) {
        NativeLibraryState.allowGpuBackends(directx, metal, vulkan);
    }

    static int backendSelection(int allowed, String override) {
        return NativeLibraryState.backendSelection(allowed, override);
    }

    static int backendCode(String value) {
        return NativeLibraryState.backendCode(value);
    }

    private static final class Holder {
        static final Path IMAGE = NativeLibraryState.extract();
        static final SymbolLookup SYMBOLS = configuredSymbols(IMAGE);
    }

    public static SymbolLookup symbols() { return Holder.SYMBOLS; }

    public static byte[] imageDigest() throws IOException {
        return NativeLibraryState.imageDigest(Holder.IMAGE);
    }

    private static SymbolLookup configuredSymbols(Path image) {
        SymbolLookup symbols = SymbolLookup.libraryLookup(image, Arena.global());
        Linker linker = Linker.nativeLinker();
        MethodHandle backend = linker.downcallHandle(symbols.findOrThrow("retina_configure_gpu_backend"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        MethodHandle diagnostics = linker.downcallHandle(symbols.findOrThrow("retina_configure_debug"),
                FunctionDescriptor.ofVoid(JAVA_INT));
        MethodHandle flush = linker.downcallHandle(symbols.findOrThrow("retina_flush_pipeline_caches"),
                FunctionDescriptor.ofVoid());
        NativeLibraryState.configure(code -> {
            try { return (int) backend.invokeExact(code); }
            catch (RuntimeException | Error error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException("Could not configure Retina GPU backend", error); }
        }, enabled -> {
            try { diagnostics.invokeExact(enabled); }
            catch (Throwable error) { throw new IllegalStateException("Could not configure Retina diagnostics", error); }
        }, () -> {
            try { flush.invokeExact(); }
            catch (RuntimeException | Error error) { throw error; }
            catch (Throwable error) { throw new IllegalStateException("Could not flush Retina pipeline caches", error); }
        });
        return symbols;
    }
}
