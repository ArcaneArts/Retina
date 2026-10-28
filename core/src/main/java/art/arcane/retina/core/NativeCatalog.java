package art.arcane.retina.core;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static java.lang.foreign.ValueLayout.*;

/** Seed-independent registry input creates a persistent world, then uses the ordinary loader. */
public final class NativeCatalog {
    private NativeCatalog() { }
    private static final class Calls {
        static final MethodHandle CREATE = Linker.nativeLinker().downcallHandle(
                NativeLibrary.symbols().findOrThrow("retina_create_world_definition"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
    }

    public static NativeWorldDefinition create(Path catalog, String variant, long seed, Path worldRoot) {
        if (Boolean.getBoolean("retina.qa.catalog.failMaterializer"))
            throw new IllegalStateException("Catalog materialization intentionally disabled by QA");
        try (var arena = Arena.ofConfined()) {
            byte[] source = catalog.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            byte[] choice = variant.getBytes(StandardCharsets.UTF_8);
            byte[] destination = worldRoot.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            var output = arena.allocate(JAVA_LONG);
            NativeWorldDefinition.check((int) Calls.CREATE.invokeExact(
                    arena.allocateFrom(JAVA_BYTE, source), (long) source.length,
                    arena.allocateFrom(JAVA_BYTE, choice), (long) choice.length, seed,
                    arena.allocateFrom(JAVA_BYTE, destination), (long) destination.length, output));
            return NativeWorldDefinition.takeOwnership(output.get(JAVA_LONG, 0));
        } catch (Throwable error) { throw NativeWorldDefinition.failure(error); }
    }
}
