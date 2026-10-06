package art.arcane.retina.compat;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor;
import org.junit.jupiter.api.Test;
import org.popcraft.chunky.ChunkyProvider;

import static org.junit.jupiter.api.Assertions.*;

class ChunkyStartupTest {
    @Test
    void retriesAfterSpawnChunksUntilChunkyStartsAndBindsOnlyOnce() {
        ChunkyProvider.loaded = false;
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

        ChunkyProvider.loaded = true;
        accessor.tryRunFirstTimeSetup();
        accessor.tryRunFirstTimeSetup();
        assertEquals(3, accessor.attempts);
        assertEquals(1, accessor.bindings);
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
