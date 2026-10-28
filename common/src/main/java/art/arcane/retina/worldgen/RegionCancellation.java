package art.arcane.retina.worldgen;

import java.util.ArrayList;
import java.util.List;

/** Signals that all observers abandoned preparation; callbacks only cancel unclaimed work. */
final class RegionCancellation implements AutoCloseable {
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    synchronized boolean isCancelled() { return cancelled; }

    Runnable onCancel(Runnable callback) {
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
