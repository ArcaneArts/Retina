package art.arcane.retina.worldgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Uses Chunky's actual shape/iterator implementations from the optional test-runtime jar. */
@EnabledIfSystemProperty(named = "retina.chunky.tests", matches = "true")
class ChunkySelectionIntegrationTest {
    @Test void matchesActualChunkySelectionsAndResumeOffsets() throws Exception {
        var selectionClass = Class.forName("org.popcraft.chunky.Selection");
        var factory = Class.forName("org.popcraft.chunky.iterator.ChunkIteratorFactory");
        var shapeFactory = Class.forName("org.popcraft.chunky.shape.ShapeFactory");
        var shapeInterface = Class.forName("org.popcraft.chunky.shape.Shape");
        var contains = shapeInterface.getMethod("isBounding", double.class, double.class);
        var coordinate = Class.forName("org.popcraft.chunky.util.ChunkCoordinate");
        var x = coordinate.getMethod("x"); var z = coordinate.getMethod("z");
        for (String shapeName : new String[]{"circle", "square", "ellipse", "rectangle", "star", "triangle"}) {
            var builder = selectionClass.getMethod("builder", Class.forName("org.popcraft.chunky.Chunky"),
                    Class.forName("org.popcraft.chunky.platform.World")).invoke(null, null, null);
            var builderClass = builder.getClass();
            builderClass.getMethod("center", double.class, double.class).invoke(builder, -513.0, 496.0);
            builderClass.getMethod("radiusX", double.class).invoke(builder, 600.0);
            builderClass.getMethod("radiusZ", double.class).invoke(builder, 290.0);
            builderClass.getMethod("shape", String.class).invoke(builder, shapeName);
            var selection = builderClass.getMethod("build").invoke(builder);
            var shape = shapeFactory.getMethod("getShape", selectionClass).invoke(null, selection);
            for (long skip : new long[]{0, 1337}) {
                var original = (Iterator<?>) factory.getMethod("getChunkIterator", selectionClass, long.class)
                        .invoke(null, selection, skip);
                Set<Long> expected = new LinkedHashSet<>();
                while (original.hasNext()) {
                    var chunk = original.next(); int cx = (int) x.invoke(chunk), cz = (int) z.invoke(chunk);
                    if ((boolean) contains.invoke(shape, (double) ((cx << 4) + 8), (double) ((cz << 4) + 8)))
                        expected.add(((long) (cz >> 5) << 32) | ((cx >> 5) & 0xffffffffL));
                }
                var source = ChunkySelection.regions(selection, shape, skip);
                var actual = new java.util.ArrayList<Long>();
                for (Long key; (key = source.next(() -> false)) != null;) actual.add(key);
                assertEquals(new java.util.ArrayList<>(expected), actual, shapeName + " resume " + skip);
                assertNull(ChunkySelection.regions(selection, shape, skip).next(() -> true));
            }
        }
    }

    @Test void actualTaskReceivesOptionalMixin() throws Exception {
        var methods = java.util.Arrays.stream(Class.forName("org.popcraft.chunky.GenerationTask").getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertTrue(methods.stream().anyMatch(name -> name.contains("retina$generateSelection")));
        assertTrue(methods.stream().anyMatch(name -> name.contains("retina$stopSelection")));
    }
}
