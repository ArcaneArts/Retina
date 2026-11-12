package art.arcane.retina.core;

import java.util.ArrayList;
import java.util.List;

/** Signals that all observers abandoned preparation; callbacks only cancel unclaimed work. */
public final class RegionCancellation implements AutoCloseable {
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    public synchronized boolean isCancelled() { return cancelled; }

    public Runnable onCancel(Runnable callback) {
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return () -> { synchronized (this) { callbacks.remove(callback); } };
            }
        }
        callback.run();
        return () -> {};
    }

    @Override public void close() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        pending.forEach(Runnable::run);
    }
}
