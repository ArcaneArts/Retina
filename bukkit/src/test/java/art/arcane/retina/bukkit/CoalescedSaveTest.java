package art.arcane.retina.bukkit;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoalescedSaveTest {
    @Test void queuedEventsCoalesceAndObserverCancellationDoesNotCancelTheWrite() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        var writes = new AtomicInteger();
        var saves = new CoalescedSave(queued::add, writes::incrementAndGet);
        var cancelled = saves.request().toCompletableFuture(); cancelled.cancel(false);
        CompletionStage<Void> last = null;
        for (int i = 0; i < 10000; i++) last = saves.request();
        assertEquals(1, queued.size());
        assertEquals(0, writes.get());
        queued.remove().run();
        last.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, writes.get());
    }

    @Test void eventsDuringWriteRequestExactlyOneMoreCheckpointAndWaitForIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var saves = new CoalescedSave(executor, () -> {
                if (writes.incrementAndGet() == 1) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
            });
            var first = saves.request().toCompletableFuture();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                CompletionStage<Void> last = null;
                for (int i = 0; i < 10000; i++) last = saves.request();
                assertFalse(first.isDone()); assertFalse(last.toCompletableFuture().isDone());
                release.countDown(); last.toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(2, writes.get()); first.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }

    @Test void failureCompletesWaitersAndAllowsRetry() throws Exception {
        var calls = new AtomicInteger();
        var saves = new CoalescedSave(Runnable::run, () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("write failure");
        });
        var error = assertThrows(ExecutionException.class, () -> saves.request().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("write failure", error.getCause().getMessage());
        saves.request().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
    }

    @Test void schedulingFailurePreservesCauseAndAllowsRetry() throws Exception {
        var calls = new AtomicInteger();
        var saves = new CoalescedSave(task -> {
            if (calls.incrementAndGet() == 1) throw new RejectedExecutionException("executor failure");
            task.run();
        }, () -> { });
        var error = assertThrows(ExecutionException.class, () -> saves.request().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("executor failure", error.getCause().getMessage());
        saves.request().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
