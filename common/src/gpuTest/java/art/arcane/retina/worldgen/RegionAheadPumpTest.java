package art.arcane.retina.worldgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RegionAheadPumpTest {
    private static final class Dispatcher implements Executor {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        public void execute(Runnable job) { jobs.add(job); }
        void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var dispatch = new Dispatcher();
        var next = new AtomicInteger();
        var jobs = new ArrayList<CompletableFuture<Void>>();
        var result = new AtomicReference<RegionAheadPump.Result>();
        var pump = new RegionAheadPump(dispatch, 8, () -> stopped -> {
            int value = next.getAndIncrement(); return value < 24 ? (long) value : null;
        }, key -> { var job = new CompletableFuture<Void>(); jobs.add(job); return job; }, result::set);
        pump.start(); dispatch.drain();
        require(jobs.size() == 8, "eight distinct region jobs start before any chunk completion");
        jobs.get(3).complete(null); dispatch.drain();
        require(jobs.size() == 9 && result.get() == null, "publication completion refills the pipeline independently of Chunky");
        pump.close(); pump.close(); dispatch.drain();
        for (var job : jobs) job.complete(null);
        dispatch.drain();
        require(jobs.size() == 9 && result.get().stopped() && result.get().completed() == 9,
                "pause stops admissions but lets the bounded in-flight publications finish");

        var completed = new AtomicInteger();
        next.set(0);
        var immediate = new RegionAheadPump(dispatch, 8, () -> stopped -> {
            int value = next.getAndIncrement(); return value < 10_000 ? (long) value : null;
        }, key -> CompletableFuture.completedFuture(null), outcome -> {
            require(outcome.failure() == null && outcome.completed() == 10_000, "all immediate publications accounted for");
            completed.incrementAndGet();
        });
        immediate.start(); dispatch.drain(); immediate.close(); dispatch.drain();
        require(completed.get() == 1, "completed saves do not recurse or repeat finalization");

        result.set(null);
        var failed = new RegionAheadPump(dispatch, 8, () -> stopped -> 1L,
                key -> CompletableFuture.failedFuture(new IllegalStateException("disk write failed")), result::set);
        failed.start(); dispatch.drain();
        require(result.get().failure() != null && result.get().completed() == 0, "publication failure stops new admissions");

        // Eight sleeping permit holders prove the limit without running a native region or CPU workload.
        var budget = new RegionGenerationBudget();
        var workers = Executors.newFixedThreadPool(8);
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        try {
            for (int i = 0; i < 8; i++) workers.submit(() -> {
                var done = budget.acquire();
                active.incrementAndGet(); entered.countDown();
                try { require(release.await(2, TimeUnit.SECONDS), "release barrier"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                finally { active.decrementAndGet(); done.run(); done.run(); }
            });
            require(entered.await(2, TimeUnit.SECONDS) && active.get() == 8, "eight regions run without a Chunky task");
        } finally { release.countDown(); workers.shutdown(); require(workers.awaitTermination(2, TimeUnit.SECONDS), "workers stopped"); }
        var held = new ArrayList<Runnable>();
        for (int i = 0; i < 8; i++) held.add(budget.acquire());
        var waiting = new CompletableFuture<Runnable>();
        var waiter = Thread.ofPlatform().daemon().start(() -> waiting.complete(budget.acquire()));
        try {
            try {
                waiting.get(50, TimeUnit.MILLISECONDS);
                throw new AssertionError("shared work exceeded its eight-region budget");
            } catch (java.util.concurrent.TimeoutException expected) { }
            held.getFirst().run(); held.getFirst().run();
            var ninth = waiting.get(2, TimeUnit.SECONDS);
            var tenth = new CompletableFuture<Runnable>();
            var nextWaiter = Thread.ofPlatform().daemon().start(() -> tenth.complete(budget.acquire()));
            try {
                tenth.get(50, TimeUnit.MILLISECONDS);
                throw new AssertionError("duplicate release created an extra permit");
            } catch (java.util.concurrent.TimeoutException expected) { }
            finally { ninth.run(); }
            tenth.get(2, TimeUnit.SECONDS).run();
            nextWaiter.join(2000);
            require(!nextWaiter.isAlive(), "waiting work resumes after a permit is released");
        } finally {
            held.forEach(Runnable::run);
            waiting.get(2, TimeUnit.SECONDS).run();
            waiter.join(2000);
        }
        System.out.println("PASS: eight-region ahead pump, refill, pause, failures, immediate saves and shared budget");
    }
}
