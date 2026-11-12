package art.arcane.retina.minecraft1201;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** DH's API identifies its detached generation threads without a loader dependency. */
final class LegacyDistantHorizons {
    private static final MethodHandle IS_DH_THREAD = findThreadCheck();

    private LegacyDistantHorizons() { }

    static boolean isGenerationThread() {
        if (IS_DH_THREAD == null) return false;
        try {
            return (boolean) IS_DH_THREAD.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot check the Distant Horizons generation thread", error);
        }
    }

    private static MethodHandle findThreadCheck() {
        try {
            var api = Class.forName("com.seibel.distanthorizons.api.DhApi", false, LegacyDistantHorizons.class.getClassLoader());
            return MethodHandles.publicLookup().unreflect(api.getMethod("isDhThread"));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }
}
