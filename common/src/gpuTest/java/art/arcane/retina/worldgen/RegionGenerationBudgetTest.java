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
        System.out.println("PASS: foreground priority, bounded background progress, virtual waiters and preview promotion");
    }
}
