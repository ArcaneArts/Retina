package art.arcane.retina.worldgen;

import art.arcane.retina.config.RetinaConfiguration;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

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

    Scope scope() { return new Scope(); }

    /** One owner's tickets. Closing cancels waiters without releasing claimed native permits. */
    final class Scope implements AutoCloseable {
        private final Set<Request> requests = new HashSet<>();
        private boolean closed;

        Runnable acquire(boolean foreground) {
            Request ticket;
            synchronized (this) {
                if (closed) throw new CancellationException("Retina region owner is closed");
                ticket = request(foreground);
                requests.add(ticket);
            }
            Runnable release;
            try {
                release = ticket.acquire();
            } catch (Throwable error) {
                synchronized (this) { requests.remove(ticket); }
                throw error;
            }
            synchronized (this) {
                if (closed) {
                    release.run();
                    requests.remove(ticket);
                    throw new CancellationException("Retina region owner closed before generation");
                }
            }
            return () -> {
                release.run();
                synchronized (this) { requests.remove(ticket); }
            };
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            for (var ticket : requests) ticket.cancel();
        }
    }

    final class Request {
        private boolean foreground;
        private boolean requested;
        private boolean queued;
        private boolean admitted;
        private boolean released;
        private boolean claimed;
        private boolean cancelled;

        private Request(boolean foreground) { this.foreground = foreground; }

        /** A foreground reader can promote a shared preview before or after queueing. */
        void promote() {
            synchronized (RegionGenerationBudget.this) {
                if (foreground || cancelled) return;
                foreground = true;
                if (queued) {
                    background.remove(this);
                    RegionGenerationBudget.this.foreground.addLast(this);
                    dispatch();
                }
            }
        }

        boolean isCancelled() {
            synchronized (RegionGenerationBudget.this) { return cancelled; }
        }

        /** Cancel only work whose caller has not claimed its permit. Running work drains normally. */
        boolean cancel() {
            synchronized (RegionGenerationBudget.this) {
                if (claimed || cancelled) return false;
                cancelled = true;
                if (queued) {
                    (foreground ? RegionGenerationBudget.this.foreground : background).remove(this);
                    queued = false;
                }
                if (admitted) { active--; admitted = false; }
                dispatch();
                return true;
            }
        }

        Runnable acquire() {
            boolean interrupted = false;
            try {
                synchronized (RegionGenerationBudget.this) {
                    if (requested) throw new IllegalStateException("Region admission already requested");
                    requested = true;
                    if (!cancelled) {
                        queued = true;
                        (foreground ? RegionGenerationBudget.this.foreground : background).addLast(this);
                        dispatch();
                    }
                    while (!admitted && !cancelled) {
                        try { RegionGenerationBudget.this.wait(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (cancelled) throw new CancellationException("Retina region admission cancelled");
                    claimed = true;
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
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
