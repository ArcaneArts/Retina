package art.arcane.retina.worldgen;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real queued requests establish priority and bounded background progress without native work. */
public final class RegionGenerationBudgetTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static CompletableFuture<Runnable> queue(ExecutorService workers, RegionGenerationBudget budget,
                                                    RegionGenerationBudget.Request request, int expected) throws Exception {
        var future = CompletableFuture.supplyAsync(request::acquire, workers);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (budget.queuedRequests() != expected && System.nanoTime() < deadline) Thread.sleep(1);
        require(budget.queuedRequests() == expected && !future.isDone(), "request reached the held budget queue");
        return future;
    }

    public static void main(String[] args) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new RegionGenerationBudget(1);
            var held = budget.acquire();
            var background = queue(workers, budget, budget.request(false), 1);
            var foreground = new ArrayList<CompletableFuture<Runnable>>();
            for (int i = 0; i < 6; i++) foreground.add(queue(workers, budget, budget.request(true), i + 2));
            held.run();
            for (int i = 0; i < 4; i++) {
                var release = foreground.get(i).get(2, TimeUnit.SECONDS);
                require(!background.isDone(), "foreground burst precedes waiting background");
                release.run(); release.run();
            }
            var releaseBackground = background.get(2, TimeUnit.SECONDS);
            require(!foreground.get(4).isDone(), "waiting background is admitted after four foreground requests");
            releaseBackground.run();
            for (int i = 4; i < 6; i++) foreground.get(i).get(2, TimeUnit.SECONDS).run();
            require(budget.queuedRequests() == 0, "all queued requests complete");

            for (boolean beforeQueue : new boolean[]{false, true}) {
                budget = new RegionGenerationBudget(1);
                held = budget.acquire();
                background = queue(workers, budget, budget.request(false), 1);
                var ticket = budget.request(false);
                if (beforeQueue) ticket.promote();
                var promoted = queue(workers, budget, ticket, 2);
                if (!beforeQueue) ticket.promote();
                ticket.promote(); // Repeated readers do not enqueue duplicate native work.
                held.run();
                var done = promoted.get(2, TimeUnit.SECONDS);
                require(!background.isDone(), "shared preview promotion precedes background");
                done.run(); done.run();
                background.get(2, TimeUnit.SECONDS).run();
                require(budget.queuedRequests() == 0, "promotion retains one admission ticket");
            }
        }
        cancellation();
        ownerClosure();
        System.out.println("PASS: foreground priority, bounded background progress, promotion, cancellation and permit races");
    }

    private static void cancelled(CompletableFuture<Runnable> future) throws Exception {
        try { future.get(2, TimeUnit.SECONDS); throw new AssertionError("cancelled ticket acquired capacity"); }
        catch (java.util.concurrent.ExecutionException error) {
            require(error.getCause() instanceof java.util.concurrent.CancellationException, "cancellation reaches waiter");
        }
    }

    private static void cancellation() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new RegionGenerationBudget(1);
            var before = budget.request(false);
            require(before.cancel(), "unsubmitted work cancels");
            before.promote();
            cancelled(CompletableFuture.supplyAsync(before::acquire, workers));
            require(budget.queuedRequests() == 0, "pre-cancelled work never queues");

            var running = budget.request(false);
            var held = running.acquire();
            var abandoned = budget.request(false);
            var waiting = queue(workers, budget, abandoned, 1);
            abandoned.promote();
            var survivor = queue(workers, budget, budget.request(true), 2);
            long start = System.nanoTime();
            require(abandoned.cancel(), "queued work cancels after promotion");
            cancelled(waiting);
            long elapsed = System.nanoTime() - start;
            require(!survivor.isDone(), "cancellation cannot release another owner's permit");
            require(!running.cancel(), "claimed native work must drain");
            require(!abandoned.cancel(), "repeated cancellation is harmless");
            require(budget.queuedRequests() == 1, "only the surviving world stays queued");
            held.run();
            survivor.get(2, TimeUnit.SECONDS).run();
            budget.acquire().run();
            System.out.println("Queued cancellation while running permit remains held: " + elapsed / 1e6 + " ms");

            for (int i = 0; i < 200; i++) {
                var raceBudget = new RegionGenerationBudget(1);
                var release = raceBudget.acquire();
                var ticket = raceBudget.request(false);
                var pending = queue(workers, raceBudget, ticket, 1);
                var gate = new java.util.concurrent.CountDownLatch(1);
                var cancel = CompletableFuture.supplyAsync(() -> {
                    try { gate.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    return ticket.cancel();
                }, workers);
                var dispatch = CompletableFuture.runAsync(() -> {
                    try { gate.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    release.run();
                }, workers);
                gate.countDown();
                if (cancel.get(2, TimeUnit.SECONDS)) cancelled(pending);
                else pending.get(2, TimeUnit.SECONDS).run();
                dispatch.get(2, TimeUnit.SECONDS);
                var next = CompletableFuture.supplyAsync(raceBudget::acquire, workers).get(2, TimeUnit.SECONDS);
                var blocked = queue(workers, raceBudget, raceBudget.request(false), 1);
                next.run(); next.run();
                blocked.get(2, TimeUnit.SECONDS).run();
                require(raceBudget.queuedRequests() == 0, "racing cancellation preserves exact capacity");
            }
        }
    }


    private static void ownerClosure() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var budget = new RegionGenerationBudget(1);
            var closing = budget.scope();
            var surviving = budget.scope();
            var running = closing.acquire(true);
            var abandoned = CompletableFuture.supplyAsync(() -> closing.acquire(true), workers);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (budget.queuedRequests() != 1 && System.nanoTime() < deadline) Thread.sleep(1);
            require(budget.queuedRequests() == 1, "closing owner's request reached admission");
            var live = CompletableFuture.supplyAsync(() -> surviving.acquire(true), workers);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (budget.queuedRequests() != 2 && System.nanoTime() < deadline) Thread.sleep(1);
            require(budget.queuedRequests() == 2, "another world's request reached admission");
            closing.close(); closing.close();
            cancelled(abandoned);
            require(!live.isDone(), "closing a scope does not release its running permit");
            cancelled(CompletableFuture.supplyAsync(() -> closing.acquire(true), workers));
            running.run(); running.run();
            live.get(2, TimeUnit.SECONDS).run();
            surviving.close();
            require(budget.queuedRequests() == 0, "owner closure leaves no abandoned ticket");
            budget.acquire().run();
        }
    }

}
