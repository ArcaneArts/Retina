package art.arcane.retina.core;

import java.io.IOException;
import java.nio.file.Path;

/** JNI entry point for the Java 17 runtime. */
public final class NativeLibrary {
    private NativeLibrary() { }

    public static void configureDebug(boolean enabled) {
        NativeLibraryState.configureDebug(enabled);
    }

    public static void allowGpuBackends(boolean directx, boolean metal, boolean vulkan) {
        NativeLibraryState.allowGpuBackends(directx, metal, vulkan);
    }

    public static byte[] imageDigest() throws IOException {
        return NativeLibraryState.imageDigest(Holder.IMAGE);
    }

    static DefinitionTransport definitionTransport() { return Holder.TRANSPORT; }

    private static final class Holder {
        static final Path IMAGE = NativeLibraryState.extract();
        static final DefinitionTransport TRANSPORT = load();

        private static DefinitionTransport load() {
            System.load(IMAGE.toString());
            NativeLibraryState.configure(JniDefinitionTransport::configureBackend,
                    JniDefinitionTransport::configureDebug, JniDefinitionTransport::flushPipelineCaches);
            return JniDefinitionTransport.INSTANCE;
        }
    }
}
