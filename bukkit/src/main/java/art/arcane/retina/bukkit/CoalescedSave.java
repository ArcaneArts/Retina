package art.arcane.retina.bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** One queued task per owner, with snapshots created only by the two active writers. */
final class CoalescedSave {
    private static final Executor WRITERS = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("retina-cache-save-", 0).factory());
    private final Executor executor;
    private final Runnable checkpoint;
    private CompletableFuture<Void> pending;
    private CompletionStage<Void> receipt;
    private boolean requested;

    CoalescedSave(Runnable checkpoint) { this(WRITERS, checkpoint); }
    CoalescedSave(Executor executor, Runnable checkpoint) {
        this.executor = executor; this.checkpoint = checkpoint;
    }

    synchronized CompletionStage<Void> request() {
        requested = true;
        var completion = pending;
        var result = receipt;
        if (completion == null) {
            completion = new CompletableFuture<>();
            pending = completion;
            result = completion.minimalCompletionStage();
            receipt = result;
            var scheduled = completion;
            try { executor.execute(() -> drain(scheduled)); }
            catch (RuntimeException | Error error) {
                pending = null; receipt = null; requested = false; completion.completeExceptionally(error);
            }
        }
        // Cancellation by one observer must not cancel another observer's flush.
        return result;
    }

    private void drain(CompletableFuture<Void> completion) {
        try {
            while (true) {
                synchronized (this) { requested = false; }
                checkpoint.run();
                synchronized (this) {
                    if (requested) continue;
                    pending = null; receipt = null;
                    break;
                }
            }
            completion.complete(null);
        } catch (Throwable error) {
            synchronized (this) { pending = null; receipt = null; requested = false; }
            completion.completeExceptionally(error);
        }
    }
}
