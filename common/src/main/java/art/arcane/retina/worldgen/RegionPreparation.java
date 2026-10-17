package art.arcane.retina.worldgen;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns a prefetched cache lease even when its caller skips waiting for it.
 * Closing schedules exactly one release, including completion after shutdown. */
final class RegionPreparation implements AutoCloseable {
    private final CompletableFuture<AutoCloseable> future;
    private final Consumer<AutoCloseable> release;
    private final AtomicBoolean closed = new AtomicBoolean();

    RegionPreparation(CompletableFuture<AutoCloseable> future, Consumer<AutoCloseable> release) {
        this.future = Objects.requireNonNull(future);
        this.release = Objects.requireNonNull(release);
    }

    void await() { future.join(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) future.thenAccept(release);
    }
}
