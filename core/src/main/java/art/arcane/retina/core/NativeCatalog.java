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
        static final MethodHandle MATERIALIZE = Linker.nativeLinker().downcallHandle(
                NativeLibrary.symbols().findOrThrow("retina_materialize_world_definition"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG));
    }

    public static NativeWorldDefinition create(Path catalog, String variant, long seed, Path worldRoot) {
        if (Boolean.getBoolean("retina.qa.catalog.failMaterializer"))
            throw new IllegalStateException("Catalog materialization intentionally disabled by QA");
        try (var arena = Arena.ofConfined()) {
            byte[] source = catalog.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            byte[] choice = variant.getBytes(StandardCharsets.UTF_8);
            byte[] destination = worldRoot.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
            NativeWorldDefinition.check((int) Calls.MATERIALIZE.invokeExact(
                    arena.allocateFrom(JAVA_BYTE, source), (long) source.length,
                    arena.allocateFrom(JAVA_BYTE, choice), (long) choice.length, seed,
                    arena.allocateFrom(JAVA_BYTE, destination), (long) destination.length));
        } catch (Throwable error) { throw NativeWorldDefinition.failure(error); }
        return NativeWorldDefinition.open(worldRoot.resolve("retina.dat"));
    }
}
