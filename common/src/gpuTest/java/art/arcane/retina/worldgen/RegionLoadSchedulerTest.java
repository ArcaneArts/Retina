package art.arcane.retina.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** No native generation: controllable preparations and a serialized reader expose scheduling races. */
public final class RegionLoadSchedulerTest {
    private static final class Tasks implements Executor {
        final List<Runnable> queued = new ArrayList<>();
        public void execute(Runnable task) { queued.add(task); }
        void run(int index) { queued.remove(index).run(); }
    }
    private static final class Lease implements AutoCloseable {
        final AtomicInteger releases = new AtomicInteger();
        public void close() { require(releases.incrementAndGet() == 1, "lease released only once"); }
    }
    private static RegionLoadScheduler scheduler(Executor executor, int capacity) {
        return new RegionLoadScheduler(executor, capacity, lease -> {
            try { lease.close(); } catch (Exception error) { throw new AssertionError(error); }
        });
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var tasks = new Tasks();
        try (var scheduler = scheduler(tasks, 2)) {
            var a = new Lease(); var b = new Lease(); var c = new Lease();
            var readA = new CompletableFuture<Integer>();
            var duplicateA = new CompletableFuture<Integer>();
            var readB = new CompletableFuture<Integer>();
            var first = scheduler.load(1, () -> true, () -> a, () -> readA);
            var duplicate = scheduler.load(1, () -> false, () -> { throw new AssertionError("duplicate generation"); }, () -> duplicateA);
            var second = scheduler.load(2, () -> true, () -> b, () -> readB);
            var third = scheduler.load(3, () -> true, () -> c, () -> CompletableFuture.completedFuture(3));
            var cancelledWait = scheduler.load(4, () -> true, () -> { throw new AssertionError("cancelled admission"); },
                    () -> CompletableFuture.completedFuture(4));
            cancelledWait.cancel(false);
            require(tasks.queued.size() == 2 && !third.isDone(), "distinct region preparations are bounded");
            tasks.run(1); // Region B is ready before region A.
            require(!second.isDone() && b.releases.get() == 0, "read completion owns the prepared lease");
            require(tasks.queued.size() == 1, "ready but unread region still consumes capacity");
            readB.complete(2);
            require(second.join() == 2 && !first.isDone(), "a slow region does not hold up another region");
            require(b.releases.get() == 1 && tasks.queued.size() == 2, "finished reads admit waiting regions");
            tasks.run(1);
            require(third.join() == 3 && c.releases.get() == 1, "deferred region eventually finishes");
            first.cancel(false);
            tasks.run(0);
            require(a.releases.get() == 0, "cancelled observer cannot release a lease while a read uses it");
            readA.complete(1);
            require(a.releases.get() == 0 && !duplicate.isDone(), "the last attached read keeps the shared lease pinned");
            duplicateA.complete(1);
            require(tasks.queued.isEmpty(), "cancelled capacity waiter never starts generation");
            require(duplicate.join() == 1 && a.releases.get() == 1, "coalesced reads and cancellation release once");
        }

        tasks = new Tasks();
        try (var scheduler = scheduler(tasks, 1)) {
            var lease = new Lease();
            var missing = new IllegalStateException("preparation failed");
            var failed = scheduler.load(1, () -> true, () -> { throw missing; },
                    () -> { throw new AssertionError("read after failed preparation"); });
            var skipped = scheduler.load(2, () -> true, () -> lease, () -> CompletableFuture.completedFuture("pending write"));
            tasks.run(0);
            require(failed.isCompletedExceptionally(), "preparation failure reaches the chunk request");
            tasks.run(0);
            require(skipped.join().equals("pending write") && lease.releases.get() == 1,
                    "pending-write shortcut releases even when storage.read was skipped");
            var throwing = scheduler.load(3, () -> true, Lease::new, () -> { throw new IllegalArgumentException("enqueue failed"); });
            tasks.run(0);
            require(throwing.isCompletedExceptionally(), "read enqueue failure reaches the caller");
            var retry = scheduler.load(4, () -> true, Lease::new, () -> CompletableFuture.completedFuture(4));
            require(tasks.queued.size() == 1, "read enqueue failure releases capacity");
            tasks.run(0);
            require(retry.join() == 4, "read failure does not prevent subsequent requests");
        }
        try (var scheduler = scheduler(task -> { throw new RejectedExecutionException("stopped executor"); }, 1)) {
            require(scheduler.load(1, () -> true, Lease::new, () -> CompletableFuture.completedFuture(1)).isCompletedExceptionally(),
                    "executor rejection completes the request");
            require(scheduler.load(2, () -> true, Lease::new, () -> CompletableFuture.completedFuture(2)).isCompletedExceptionally(),
                    "executor rejection does not strand capacity");
        }
        tasks = new Tasks();
        var scheduler = scheduler(tasks, 1);
        var abandonedRead = new CompletableFuture<Integer>();
        var lease = new Lease();
        var abandoned = scheduler.load(1, () -> true, () -> lease, () -> abandonedRead);
        var waiting = scheduler.load(2, () -> true, Lease::new, () -> CompletableFuture.completedFuture(2));
        tasks.run(0);
        scheduler.close(); scheduler.close();
        require(abandoned.isCompletedExceptionally() && waiting.isCompletedExceptionally() && lease.releases.get() == 1,
                "I/O shutdown fails abandoned and capacity-waiting reads and releases leases");
        require(scheduler.load(3, () -> false, Lease::new, () -> CompletableFuture.completedFuture(3)).isCompletedExceptionally(),
                "closed scheduler cannot enqueue another read");

        // Two real workers reach preparation together; unrelated serialized I/O remains responsive.
        var workers = Executors.newFixedThreadPool(2);
        var io = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var late = new Lease();
        var concurrent = scheduler(workers, 4);
        try {
            var one = concurrent.load(1, () -> true, () -> {
                started.countDown(); await(release); return late;
            }, () -> { throw new AssertionError("closed preparation must not enqueue I/O"); });
            var two = concurrent.load(2, () -> true, () -> {
                started.countDown(); await(release); return new Lease();
            }, () -> CompletableFuture.supplyAsync(() -> 2, io));
            require(started.await(2, TimeUnit.SECONDS), "two distinct regions prepare concurrently");
            require(CompletableFuture.supplyAsync(() -> "scan/write", io).get(2, TimeUnit.SECONDS).equals("scan/write"),
                    "region preparation does not occupy the serialized I/O queue");
            concurrent.close();
            require(one.isCompletedExceptionally() && two.isCompletedExceptionally(), "shutdown releases blocked observers");
        } finally {
            concurrent.close(); release.countDown(); workers.shutdown(); io.shutdown();
            require(workers.awaitTermination(2, TimeUnit.SECONDS) && io.awaitTermination(2, TimeUnit.SECONDS), "workers terminated");
        }
        require(late.releases.get() == 1, "preparation completing after shutdown releases its late lease");
        System.out.println("PASS: parallel region admission, ready-first reads, bounds, deduplication, cancellation, failures and shutdown");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
