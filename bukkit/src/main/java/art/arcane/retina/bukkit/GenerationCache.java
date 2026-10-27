package art.arcane.retina.bukkit;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Coalesces admitted work and bounds completed values plus concurrent loaders. */
final class GenerationCache<K, V> implements AutoCloseable {
    private final LinkedHashMap<K, CompletableFuture<V>> entries = new LinkedHashMap<>(16, .75F, true);
    private final Semaphore loaders;
    private final int retained;
    private int active;
    private boolean closed;

    GenerationCache(int retained, int concurrent) {
        if (retained < 1 || concurrent < 1) throw new IllegalArgumentException("Cache sizes must be positive");
        this.retained = retained; this.loaders = new Semaphore(concurrent, true);
    }

    V get(K key, Supplier<V> load) {
        CompletableFuture<V> result;
        synchronized (this) {
            requireOpen(); result = entries.get(key);
        }
        if (result != null) return value(result);
        // Wait before admitting a new key, so pending keys cannot grow the map.
        loaders.acquireUninterruptibly();
        boolean owner;
        synchronized (this) {
            if (closed) { loaders.release(); requireOpen(); }
            result = entries.get(key); owner = result == null;
            if (owner) { result = new CompletableFuture<>(); entries.put(key, result); active++; }
        }
        if (!owner) { loaders.release(); return value(result); }
        try {
            result.complete(load.get());
        } catch (Throwable error) {
            result.completeExceptionally(error);
            synchronized (this) { entries.remove(key, result); }
        } finally {
            synchronized (this) {
                active--;
                var iterator = entries.entrySet().iterator();
                int completed = entries.size() - active;
                while (completed > retained && iterator.hasNext()) {
                    if (iterator.next().getValue().isDone()) { iterator.remove(); completed--; }
                }
                notifyAll();
            }
            loaders.release();
        }
        return value(result);
    }

    V getIfPresent(K key) {
        CompletableFuture<V> result;
        synchronized (this) { requireOpen(); result = entries.get(key); }
        return result == null ? null : value(result);
    }

    private static <V> V value(CompletableFuture<V> future) {
        try { return future.join(); }
        catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw error;
        }
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("World generation cache is closed"); }

    @Override public void close() {
        boolean interrupted = false;
        synchronized (this) {
            closed = true;
            while (active > 0) {
                try { wait(); }
                catch (InterruptedException error) { interrupted = true; }
            }
            entries.clear();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
