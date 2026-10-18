package art.arcane.retina.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Optional Chunky API adapter. Uses its own iterator/shape, including CSV and custom shapes. */
final class ChunkySelection {
    final Object world;
    private final Object selection;
    private final Object shape;
    private final long count;

    ChunkySelection(Object task) throws ReflectiveOperationException {
        var loader = task.getClass().getClassLoader();
        var type = Class.forName("org.popcraft.chunky.GenerationTask", false, loader);
        selection = type.getMethod("getSelection").invoke(task);
        shape = type.getMethod("getShape").invoke(task);
        count = (long) type.getMethod("getCount").invoke(task);
        var platformWorld = selection.getClass().getMethod("world").invoke(selection);
        world = platformWorld.getClass().getMethod("getWorld").invoke(platformWorld);
    }

    RegionAheadPump.Source regions() { return regions(selection, shape, count); }

    static RegionAheadPump.Source regions(Object selection, Object shape, long count) {
        var loader = selection.getClass().getClassLoader();
        try {
            var selectionType = Class.forName("org.popcraft.chunky.Selection", false, loader);
            var factory = Class.forName("org.popcraft.chunky.iterator.ChunkIteratorFactory", false, loader);
            var iteratorType = Class.forName("org.popcraft.chunky.iterator.ChunkIterator", false, loader);
            var iterator = (Iterator<?>) factory.getMethod("getChunkIterator", selectionType, long.class)
                    .invoke(null, selection, count);
            if (!(boolean) iteratorType.getMethod("process").invoke(iterator))
                throw new IllegalStateException("Chunky could not process its independent region selection");
            var coordinateType = Class.forName("org.popcraft.chunky.util.ChunkCoordinate", false, loader);
            var lookup = MethodHandles.publicLookup();
            var x = lookup.unreflect(coordinateType.getMethod("x")).asType(MethodType.methodType(int.class, Object.class));
            var z = lookup.unreflect(coordinateType.getMethod("z")).asType(MethodType.methodType(int.class, Object.class));
            var shapeType = Class.forName("org.popcraft.chunky.shape.Shape", false, loader);
            var contains = lookup.unreflect(shapeType.getMethod("isBounding", double.class, double.class)).bindTo(shape);
            return new Cursor(iterator, x, z, contains);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not read Chunky's region selection", error);
        }
    }

    private record Cursor(Iterator<?> chunks, MethodHandle x, MethodHandle z, MethodHandle contains,
                          Set<Long> seen) implements RegionAheadPump.Source {
        Cursor(Iterator<?> chunks, MethodHandle x, MethodHandle z, MethodHandle contains) {
            this(chunks, x, z, contains, new HashSet<>());
        }
        @Override public Long next(BooleanSupplier stopped) throws Exception {
            try {
                while (!stopped.getAsBoolean() && chunks.hasNext()) {
                    Object chunk = chunks.next();
                    int cx = (int) x.invokeExact(chunk), cz = (int) z.invokeExact(chunk);
                    long key = ((long) (cz >> 5) << 32) | ((cx >> 5) & 0xffffffffL);
                    if (seen.contains(key)) continue;
                    // Match GenerationTask's block-center test; corner-only region tests miss thin selections.
                    if ((boolean) contains.invokeExact((double) ((cx << 4) + 8), (double) ((cz << 4) + 8))) {
                        seen.add(key);
                        return key;
                    }
                }
                return null;
            } catch (Throwable error) {
                if (error instanceof Exception exception) throw exception;
                if (error instanceof Error fatal) throw fatal;
                throw new IllegalStateException(error);
            }
        }
    }
}
