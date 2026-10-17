package art.arcane.retina.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic lease ownership checks; no Minecraft, terrain worker or GPU. */
public final class RegionPreparationTest {
    private static final class Lease implements AutoCloseable {
        final AtomicInteger releases = new AtomicInteger();
        @Override public void close() { releases.incrementAndGet(); }
    }

    private static RegionPreparation preparation(CompletableFuture<AutoCloseable> future) {
        return new RegionPreparation(future, lease -> {
            try { lease.close(); }
            catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var used = new Lease();
        var ready = preparation(CompletableFuture.completedFuture(used));
        ready.await();
        require(used.releases.get() == 0, "await keeps the region pinned until publication finishes");
        ready.close(); ready.close();
        require(used.releases.get() == 1, "publication and later shutdown release once");

        var skipped = new Lease();
        var delayed = new CompletableFuture<AutoCloseable>();
        var unused = preparation(delayed);
        unused.close(); unused.close();
        require(!delayed.isCancelled(), "discarding the prefetch does not abandon its eventual lease");
        require(skipped.releases.get() == 0, "nothing released before completion");
        delayed.complete(skipped);
        unused.close();
        require(skipped.releases.get() == 1, "saved-terrain fast path releases a late lease without awaiting");

        var failed = new CompletableFuture<AutoCloseable>();
        var releases = new AtomicInteger();
        var failure = new RegionPreparation(failed, ignored -> releases.incrementAndGet());
        failure.close();
        var cause = new IllegalStateException("generation failed");
        failed.completeExceptionally(cause);
        try { failure.await(); throw new AssertionError("failure must reach the caller"); }
        catch (CompletionException error) { require(error.getCause() == cause, "original failure preserved"); }
        failure.close();
        require(releases.get() == 0, "failed generation has no lease to release");

        var cancelled = new CompletableFuture<AutoCloseable>();
        var cancellation = new RegionPreparation(cancelled, ignored -> releases.incrementAndGet());
        cancelled.cancel(false); cancellation.close(); cancellation.close();
        require(releases.get() == 0, "cancelled generation has no lease to release");

        var callers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var joined = new CountDownLatch(2);
            var pending = new CompletableFuture<AutoCloseable>();
            var shared = preparation(pending);
            var lease = new Lease();
            var shutdown = callers.submit(() -> { joined.countDown(); start.await(); shared.close(); return null; });
            var publication = callers.submit(() -> { joined.countDown(); start.await(); shared.close(); return null; });
            require(joined.await(5, TimeUnit.SECONDS), "both close paths reached the barrier");
            start.countDown();
            shutdown.get(5, TimeUnit.SECONDS); publication.get(5, TimeUnit.SECONDS);
            pending.complete(lease);
            require(lease.releases.get() == 1, "concurrent shutdown/publication close releases exactly once");
        } finally {
            start.countDown();
            callers.shutdownNow();
            require(callers.awaitTermination(5, TimeUnit.SECONDS), "test callers stopped");
        }
        System.out.println("PASS: region lease use, skipped await, delayed completion, failure, cancellation and concurrent close");
    }
}
