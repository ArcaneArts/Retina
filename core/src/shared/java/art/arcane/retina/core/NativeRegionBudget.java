package art.arcane.retina.core;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Shared native-region limit for ordinary loads, DH and bulk tasks. */
public class NativeRegionBudget {
    private static final int FOREGROUND_BURST = 4;
    private final int limit;
    private final ArrayDeque<Request> foreground = new ArrayDeque<>();
    private final ArrayDeque<Request> background = new ArrayDeque<>();
    private int active;
    private int consecutiveForeground;

    public NativeRegionBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Region parallelism must be positive");
        this.limit = limit;
    }

    public int limit() { return limit; }

    // Leave admission room for foreground reads alongside a complete ahead batch.
    public int admissionCapacity() { return (int) Math.min(Integer.MAX_VALUE, 2L * limit); }

    /** Creating a ticket does not queue or consume native capacity. */
    public Request request(boolean foreground) { return new Request(foreground); }

    public Runnable acquire() { return request(false).acquire(); }

    public synchronized int queuedRequests() { return foreground.size() + background.size(); }

    public Scope scope() { return new Scope(); }

    /** One owner's tickets. Closing cancels waiters without releasing claimed native permits. */
    public final class Scope implements AutoCloseable {
        private final Set<Request> requests = new HashSet<>();
        private boolean closed;

        public Runnable acquire(boolean foreground) {
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

    public final class Request {
        private boolean foreground;
        private boolean requested;
        private boolean queued;
        private boolean admitted;
        private boolean released;
        private boolean claimed;
        private boolean cancelled;

        private Request(boolean foreground) { this.foreground = foreground; }

        /** A foreground reader can promote a shared preview before or after queueing. */
        public void promote() {
            synchronized (NativeRegionBudget.this) {
                if (foreground || cancelled) return;
                foreground = true;
                if (queued) {
                    background.remove(this);
                    NativeRegionBudget.this.foreground.addLast(this);
                    dispatch();
                }
            }
        }

        public boolean isCancelled() {
            synchronized (NativeRegionBudget.this) { return cancelled; }
        }

        /** Cancel only work whose caller has not claimed its permit. Running work drains normally. */
        public boolean cancel() {
            synchronized (NativeRegionBudget.this) {
                if (claimed || cancelled) return false;
                cancelled = true;
                if (queued) {
                    (foreground ? NativeRegionBudget.this.foreground : background).remove(this);
                    queued = false;
                }
                if (admitted) { active--; admitted = false; }
                dispatch();
                return true;
            }
        }

        public Runnable acquire() {
            boolean interrupted = false;
            try {
                synchronized (NativeRegionBudget.this) {
                    if (requested) throw new IllegalStateException("Region admission already requested");
                    requested = true;
                    if (!cancelled) {
                        queued = true;
                        (foreground ? NativeRegionBudget.this.foreground : background).addLast(this);
                        dispatch();
                    }
                    while (!admitted && !cancelled) {
                        try { NativeRegionBudget.this.wait(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                    if (cancelled) throw new CancellationException("Retina region admission cancelled");
                    claimed = true;
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            return () -> {
                synchronized (NativeRegionBudget.this) {
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
