package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;

import java.util.ArrayDeque;

/** Shared native-region limit for ordinary loads, DH and bulk tasks. */
final class RegionGenerationBudget {
    static final RegionGenerationBudget SHARED = new RegionGenerationBudget();
    private static final int FOREGROUND_BURST = 4;
    private final int limit;
    private final ArrayDeque<Request> foreground = new ArrayDeque<>();
    private final ArrayDeque<Request> background = new ArrayDeque<>();
    private int active;
    private int consecutiveForeground;

    RegionGenerationBudget() { this(RetinaConfiguration.get().regionParallelism()); }

    RegionGenerationBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Region parallelism must be positive");
        this.limit = limit;
    }

    int limit() { return limit; }

    // Leave admission room for foreground reads alongside a complete ahead batch.
    int admissionCapacity() { return (int) Math.min(Integer.MAX_VALUE, 2L * limit); }

    /** Creating a ticket does not queue or consume native capacity. */
    Request request(boolean foreground) { return new Request(foreground); }

    Runnable acquire() { return request(false).acquire(); }

    synchronized int queuedRequests() { return foreground.size() + background.size(); }

    final class Request {
        private boolean foreground;
        private boolean requested;
        private boolean queued;
        private boolean admitted;
        private boolean released;

        private Request(boolean foreground) { this.foreground = foreground; }

        /** A foreground reader can promote a shared preview before or after queueing. */
        void promote() {
            synchronized (RegionGenerationBudget.this) {
                if (foreground) return;
                foreground = true;
                if (queued) {
                    background.remove(this);
                    RegionGenerationBudget.this.foreground.addLast(this);
                    dispatch();
                }
            }
        }

        Runnable acquire() {
            boolean interrupted = false;
            synchronized (RegionGenerationBudget.this) {
                if (requested) throw new IllegalStateException("Region admission already requested");
                requested = true;
                queued = true;
                (foreground ? RegionGenerationBudget.this.foreground : background).addLast(this);
                dispatch();
                while (!admitted) {
                    try { RegionGenerationBudget.this.wait(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return () -> {
                synchronized (RegionGenerationBudget.this) {
                    if (released) return;
                    released = true;
                    active--;
                    dispatch();
                }
            };
        }
    }

    /** Bounded foreground preference: a waiting background job gets every fifth admission. */
    private void dispatch() {
        while (active < limit && (!foreground.isEmpty() || !background.isEmpty())) {
            Request next;
            if (!foreground.isEmpty() && (background.isEmpty() || consecutiveForeground < FOREGROUND_BURST)) {
                next = foreground.removeFirst();
                consecutiveForeground = Math.min(FOREGROUND_BURST, consecutiveForeground + 1);
            } else {
                next = background.removeFirst();
                consecutiveForeground = 0;
            }
            next.queued = false;
            next.admitted = true;
            active++;
        }
        notifyAll();
    }
}
