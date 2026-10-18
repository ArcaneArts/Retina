package art.arcane.retina.worldgen;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Defers enqueueing reads until their immutable region is ready. Live storage stays on its I/O queue. */
final class RegionLoadScheduler implements AutoCloseable {
    private static final class Entry {
        final CompletableFuture<AutoCloseable> prepared = new CompletableFuture<>();
        final CompletableFuture<Void> stopped = new CompletableFuture<>();
        final RegionPreparation owner;
        int readers;

        Entry(Consumer<AutoCloseable> release) { owner = new RegionPreparation(prepared, release); }
    }

    private final Executor executor;
    private final int capacity;
    private final Consumer<AutoCloseable> release;
    private final Map<Long, Entry> entries = new HashMap<>();
    private CompletableFuture<Void> available = new CompletableFuture<>();
    private boolean closed;

    RegionLoadScheduler(Executor executor, int capacity, Consumer<AutoCloseable> release) {
        if (capacity < 1) throw new IllegalArgumentException("Region capacity must be positive");
        this.executor = executor;
        this.capacity = capacity;
        this.release = release;
    }

    <T> CompletableFuture<T> load(long key, BooleanSupplier needsPreparation,
                                Supplier<AutoCloseable> prepare, Supplier<CompletableFuture<T>> read) {
        Entry entry;
        boolean start = false;
        CompletableFuture<Void> wait = null;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(closedError());
            entry = entries.get(key);
            // Join existing work even if a concurrent save has since created the destination.
            if (entry == null) {
                if (!needsPreparation.getAsBoolean()) return read.get();
                if (entries.size() == capacity) wait = available;
                else {
                    entry = new Entry(release);
                    entries.put(key, entry);
                    start = true;
                }
            }
            if (entry != null) entry.readers++;
        }
        if (wait != null) {
            // Waiting requests consume no preparation worker and never fall back onto the I/O queue.
            return wait.thenCompose(ignored -> load(key, needsPreparation, prepare, read));
        }
        Entry shared = entry;
        var completion = new CompletableFuture<T>();
        // Do not return this owner future: cancelling an observer must not skip lease cleanup.
        completion.whenComplete((value, error) -> finish(key, shared));
        shared.stopped.whenComplete((ignored, error) -> {
            if (error != null) completion.completeExceptionally(error);
        });
        shared.prepared.thenCompose(ignored -> {
            synchronized (this) {
                if (closed) return CompletableFuture.<T>failedFuture(closedError());
                return read.get(); // Only enqueues a read; it must not wait for I/O here.
            }
        }).whenComplete((value, error) -> {
            if (error != null) completion.completeExceptionally(error);
            else completion.complete(value);
        });
        if (start) {
            try {
                executor.execute(() -> {
                    try {
                        synchronized (this) {
                            if (closed) throw closedError();
                        }
                        shared.prepared.complete(prepare.get());
                    } catch (Throwable error) { shared.prepared.completeExceptionally(error); }
                });
            } catch (Throwable error) { shared.prepared.completeExceptionally(error); }
        }
        return completion.copy();
    }

    private void finish(long key, Entry entry) {
        CompletableFuture<Void> signal = null;
        synchronized (this) {
            if (--entry.readers != 0) return;
            if (entries.remove(key, entry)) {
                signal = available;
                available = new CompletableFuture<>();
            }
        }
        entry.owner.close();
        if (signal != null) signal.complete(null);
    }

    /** Called after the owning I/O queue is shut down; its abandoned reads must also release leases. */
    @Override public void close() {
        Entry[] pending;
        CompletableFuture<Void> signal;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = entries.values().toArray(Entry[]::new);
            signal = available;
        }
        var error = closedError();
        signal.completeExceptionally(error);
        for (var entry : pending) entry.stopped.completeExceptionally(error);
    }

    private static IllegalStateException closedError() {
        return new IllegalStateException("Retina region loader is closed");
    }
}
