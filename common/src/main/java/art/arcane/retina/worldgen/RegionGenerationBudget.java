package art.arcane.retina.worldgen;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared native-region limit for ordinary loads, DH and bulk tasks. */
final class RegionGenerationBudget {
    static final int LIMIT = 8;
    static final RegionGenerationBudget SHARED = new RegionGenerationBudget();
    // Fair admission keeps continuous ahead work from starving ordinary requests.
    private final Semaphore permits = new Semaphore(LIMIT, true);

    Runnable acquire() {
        permits.acquireUninterruptibly();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) permits.release();
        };
    }
}
