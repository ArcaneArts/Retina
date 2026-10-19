package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared native-region limit for ordinary loads, DH and bulk tasks. */
final class RegionGenerationBudget {
    static final RegionGenerationBudget SHARED = new RegionGenerationBudget();
    private final int limit;
    // Fair admission keeps continuous ahead work from starving ordinary requests.
    private final Semaphore permits;

    RegionGenerationBudget() { this(RetinaConfiguration.get().regionParallelism()); }

    RegionGenerationBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Region parallelism must be positive");
        this.limit = limit;
        permits = new Semaphore(limit, true);
    }

    int limit() { return limit; }

    // Leave admission room for foreground reads alongside a complete ahead batch.
    int admissionCapacity() { return (int) Math.min(Integer.MAX_VALUE, 2L * limit); }

    Runnable acquire() {
        permits.acquireUninterruptibly();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) permits.release();
        };
    }
}
