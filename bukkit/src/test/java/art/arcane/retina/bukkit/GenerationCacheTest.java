package art.arcane.retina.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenerationCacheTest {
    @Test @Timeout(10) void concurrentCallersShareOneLoadAndEvictedValuesReload() throws Exception {
        var calls = new AtomicInteger(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var cache = new GenerationCache<String, Integer>(2, 2); var pool = Executors.newFixedThreadPool(4)) {
            Callable<Integer> read = () -> cache.get("shared", () -> {
                calls.incrementAndGet(); started.countDown();
                try { release.await(); } catch (InterruptedException error) { throw new IllegalStateException(error); }
                return 17;
            });
            var first = pool.submit(read); assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = pool.submit(read); var third = pool.submit(read); release.countDown();
            assertEquals(17, first.get()); assertEquals(17, second.get()); assertEquals(17, third.get());
            assertEquals(1, calls.get());
            cache.get("second", calls::incrementAndGet); cache.get("third", calls::incrementAndGet);
            cache.get("shared", calls::incrementAndGet); assertEquals(4, calls.get());
        }
    }

    @Test @Timeout(10) void failedLoadReleasesCapacityAndCanRetry() {
        var cache = new GenerationCache<String, Integer>(1, 1);
        assertThrows(IllegalStateException.class, () -> cache.get("chunk", () -> { throw new IllegalStateException("native failure"); }));
        assertEquals(42, cache.get("chunk", () -> 42));
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get("late", () -> 3));
    }
}
