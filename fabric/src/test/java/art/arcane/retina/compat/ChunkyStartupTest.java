package art.arcane.retina.compat;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.popcraft.chunky.ChunkyProvider;

import static org.junit.jupiter.api.Assertions.*;

class ChunkyStartupTest {
    @Test
    // The installed-Chunky lane loads the real provider, not our controllable startup fixture.
    // Exercise this simulated transition in the ordinary lane without retinaChunkyJar.
    @DisabledIfSystemProperty(named = "retina.chunky.tests", matches = "true")
    void retriesBeforeStartupAndRebindsOnceAfterTheProviderRestarts() {
        ChunkyProvider.unregister();
        var accessor = new AbstractChunkyAccessor() {
            int attempts;
            int bindings;

            @Override protected void bindOnGenerationProgressEvent() {
                attempts++;
                ChunkyProvider.get();
                bindings++;
            }
        };
        assertDoesNotThrow(accessor::tryRunFirstTimeSetup);
        assertDoesNotThrow(accessor::tryRunFirstTimeSetup);
        assertEquals(2, accessor.attempts);
        assertEquals(0, accessor.bindings);

        ChunkyProvider.register(new Object());
        accessor.tryRunFirstTimeSetup();
        accessor.tryRunFirstTimeSetup();
        assertEquals(3, accessor.attempts);
        assertEquals(1, accessor.bindings);

        ChunkyProvider.unregister();
        accessor.tryRunFirstTimeSetup();
        assertEquals(4, accessor.attempts);
        assertEquals(1, accessor.bindings);
        ChunkyProvider.register(new Object());
        java.util.stream.IntStream.range(0, 16).parallel().forEach(ignored -> accessor.tryRunFirstTimeSetup());
        assertEquals(5, accessor.attempts);
        assertEquals(2, accessor.bindings);
        ChunkyProvider.unregister();
    }

    @Test
    void preservesOtherSetupFailuresIncludingTheSameMessageFromAnotherSource() {
        for (var failure : new RuntimeException[]{new IllegalStateException("Chunky is not loaded."),
                new IllegalStateException("Listener registration failed"), new RuntimeException("Broken integration")}) {
            var accessor = new AbstractChunkyAccessor() {
                @Override protected void bindOnGenerationProgressEvent() { throw failure; }
            };
            assertSame(failure, assertThrows(RuntimeException.class, accessor::tryRunFirstTimeSetup));
        }
    }
}
