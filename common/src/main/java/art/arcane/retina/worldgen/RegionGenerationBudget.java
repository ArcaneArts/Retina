package art.arcane.retina.worldgen;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared native-region limit. Bulk tasks borrow extra slots without multiplying Rust worker pools. */
final class RegionGenerationBudget {
    static final int BULK_LIMIT = 8;
    static final RegionGenerationBudget SHARED = new RegionGenerationBudget(2, BULK_LIMIT);
    private final int ordinaryLimit;
    private final int bulkLimit;
    private final ArrayDeque<Object> waiters = new ArrayDeque<>();
    private int active;
    private int bulkUsers;

    RegionGenerationBudget(int ordinaryLimit, int bulkLimit) {
        this.ordinaryLimit = ordinaryLimit;
        this.bulkLimit = bulkLimit;
    }

    synchronized Runnable bulk() {
        bulkUsers++;
        notifyAll();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) synchronized (this) {
                bulkUsers--;
                notifyAll();
            }
        };
    }

    Runnable acquire() {
        boolean interrupted = false;
        synchronized (this) {
            var ticket = new Object();
            waiters.add(ticket);
            // Retain FIFO admission so continuous ahead work cannot starve an ordinary request.
            while (waiters.peek() != ticket || active >= (bulkUsers == 0 ? ordinaryLimit : bulkLimit)) {
                try { wait(); }
                catch (InterruptedException error) { interrupted = true; }
            }
            waiters.remove();
            active++;
            notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
        var closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) synchronized (this) {
                active--;
                notifyAll();
            }
        };
    }
}
