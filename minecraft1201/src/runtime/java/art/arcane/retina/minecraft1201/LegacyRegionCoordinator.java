package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeRegionBudget;
import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.RegionCancellation;
import art.arcane.retina.core.RegionLoadScheduler;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Prepares immutable MCA files, leaving live storage to Minecraft's owning I/O queue. */
public final class LegacyRegionCoordinator implements AutoCloseable {
    private static final class Preview {
        final NativeRegionBudget.Request ticket;
        Path directory;
        Path path;
        boolean published;

        Preview(NativeRegionBudget.Request ticket) {
            this.ticket = ticket;
        }
    }

    private final NativeWorldDefinition definition;
    private final String dimension;
    private final NativeRegionBudget budget;
    private final ConcurrentHashMap<Long, Preview> previews = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final RegionLoadScheduler loads;
    private volatile boolean closing;

    LegacyRegionCoordinator(NativeWorldDefinition definition, String dimension, NativeRegionBudget budget) {
        this.definition = definition;
        this.dimension = dimension;
        this.budget = budget;
        this.workers = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "retina-1201-region-prepare");
            thread.setDaemon(true);
            return thread;
        });
        this.loads = new RegionLoadScheduler(workers, budget.admissionCapacity(), LegacyRegionCoordinator::release);
    }

    public <T> CompletableFuture<T> load(ChunkPos position, boolean foreground, Supplier<CompletableFuture<T>> read) {
        long key = key(position);
        return loads.load(key, foreground, () -> true,
                (priority, cancellation) -> prepare(position, key, priority, cancellation),
                () -> {
                    var preview = previews.get(key);
                    if (preview != null) preview.ticket.promote();
                }, read);
    }

    private AutoCloseable prepare(ChunkPos position, long key, BooleanSupplier foreground, RegionCancellation cancellation) {
        var preview = new Preview(budget.request(foreground.getAsBoolean()));
        previews.put(key, preview);
        boolean retained = false;
        var unsubscribe = cancellation.onCancel(preview.ticket::cancel);
        try {
            if (closing) preview.ticket.cancel();
            if (foreground.getAsBoolean()) preview.ticket.promote();
            var permit = preview.ticket.acquire();
            try {
                if (closing || cancellation.isCancelled()) throw new CancellationException("Retina region request was cancelled");
                preview.directory = Files.createTempDirectory("retina-1201-preview-");
                preview.path = preview.directory.resolve(fileName(position));
                long started = System.nanoTime();
                var result = definition.generateRegion(dimension, position.getRegionX(), position.getRegionZ(), preview.path);
                if (result.generated() != 1024) throw new IllegalStateException("Incomplete Retina region preview " + preview.path);
                if (Boolean.getBoolean("retina.qa")) LegacyWorlds.LOG.info(
                        "QA_EVT retina_1201_prepared {} {},{} chunks={} ms={}", dimension,
                        position.getRegionX(), position.getRegionZ(), result.generated(), (System.nanoTime() - started) / 1_000_000);
                retained = true;
                return () -> discard(key, preview);
            } finally {
                permit.run();
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot prepare Retina region for " + dimension + " " + position, error);
        } finally {
            unsubscribe.run();
            if (!retained) discard(key, preview);
        }
    }

    /** Runs on the same serialized queue as Minecraft's region reads and writes. */
    public void publish(ChunkPos position, LegacyRegionStorage storage) throws IOException {
        var preview = previews.get(key(position));
        if (preview == null) throw new IllegalStateException("Missing Retina region lease for " + position);
        if (preview.published) return;
        storage.retina$closeRegion(position);
        var result = definition.publishRegion(dimension, position.getRegionX(), position.getRegionZ(),
                preview.path, storage.retina$folder().resolve(fileName(position)));
        preview.published = true;
        if (Boolean.getBoolean("retina.qa")) LegacyWorlds.LOG.info(
                "QA_EVT retina_1201_published {} {},{} generated={} preserved={}", dimension,
                position.getRegionX(), position.getRegionZ(), result.generated(), result.preserved());
    }

    public void stopAdmission() {
        closing = true;
        previews.values().forEach(preview -> preview.ticket.cancel());
    }

    /** The I/O mailbox has stopped before releasing its outstanding publication leases. */
    @Override public void close() {
        stopAdmission();
        loads.close();
        workers.shutdown();
        boolean interrupted = false;
        while (!workers.isTerminated()) {
            try { workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void discard(long key, Preview preview) {
        previews.remove(key, preview);
        try {
            if (preview.path != null) Files.deleteIfExists(preview.path);
            if (preview.directory != null) Files.deleteIfExists(preview.directory);
        } catch (IOException cleanup) {
            LegacyWorlds.LOG.warn("Cannot delete Retina region preview {}", preview.directory, cleanup);
        }
    }

    private static void release(AutoCloseable lease) {
        if (lease == null) return;
        try { lease.close(); }
        catch (Exception error) { LegacyWorlds.LOG.warn("Cannot release Retina region preview", error); }
    }

    private static long key(ChunkPos position) {
        return ChunkPos.asLong(position.getRegionX(), position.getRegionZ());
    }

    private static String fileName(ChunkPos position) {
        return "r." + position.getRegionX() + "." + position.getRegionZ() + ".mca";
    }
}
