package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.NativeRegionBudget;
import art.arcane.retina.core.NativeWorldDefinition;
import art.arcane.retina.core.RegionCancellation;
import art.arcane.retina.core.RegionLoadScheduler;
import art.arcane.retina.worldgen.GenerationMetrics;
import art.arcane.retina.worldgen.NativeTimings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import art.arcane.retina.config.RetinaConfig;
import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.core.IdleRegionFiles;
import art.arcane.retina.core.LazyRegionCompression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    private static final int MAX_DETACHED_REGIONS = 8;
    private static final boolean QA = Boolean.getBoolean("retina.qa");

    private static final class Preview {
        final NativeRegionBudget.Request ticket;
        Path directory;
        Path path;
        volatile boolean published;
        volatile boolean readByDetachedGenerator;
        private RegionFile reader;
        private long lastReadNanos;
        private boolean discarded;

        Preview(NativeRegionBudget.Request ticket) {
            this.ticket = ticket;
        }

        synchronized CompoundTag read(ChunkPos position, String dimension) throws IOException {
            if (discarded) return null;
            lastReadNanos = System.nanoTime();
            if (reader == null) {
                reader = RetinaConfiguration.get().usesLazyCompression()
                        ? LazyRegionCompression.open(path, () -> new RegionFile(path, directory, false))
                        : new RegionFile(path, directory, false);
            }
            try (var input = reader.getChunkDataInputStream(position)) {
                if (input == null) throw new IOException("Missing Retina preview record " + position);
                var tag = NbtIo.read(input);
                if (QA && !readByDetachedGenerator) LegacyWorlds.LOG.info(
                        "QA_EVT retina_1201_detached_read {} {},{}", dimension, position.getRegionX(), position.getRegionZ());
                readByDetachedGenerator = true;
                return tag;
            }
        }

        synchronized void coolAndQueue(int x, int z) {
            if (!RetinaConfiguration.get().usesLazyCompression()) return;
            try {
                closeReader();
                queueCompression(path, x, z, true);
            } catch (IOException error) {
                LegacyWorlds.LOG.warn("Cannot close Retina region preview {}", path, error);
            }
        }

        private void closeReader() throws IOException {
            if (reader != null) reader.close();
            reader = null;
        }

        synchronized void closeIdleReader(long now) {
            if (now - lastReadNanos < IdleRegionFiles.IDLE_NANOS) return;
            try { closeReader(); }
            catch (IOException error) { LegacyWorlds.LOG.warn("Cannot close Retina region preview {}", path, error); }
        }

        synchronized void delete() {
            discarded = true;
            if (path != null && RetinaConfiguration.get().usesLazyCompression()) LazyRegionCompression.cancel(path, false);
            try {
                if (reader != null) reader.close();
            } catch (IOException error) {
                LegacyWorlds.LOG.warn("Cannot close Retina region preview {}", path, error);
            }
            try {
                if (directory != null) {
                    try (var files = Files.newDirectoryStream(directory)) {
                        for (var file : files) Files.deleteIfExists(file);
                    }
                    Files.deleteIfExists(directory);
                }
            } catch (IOException error) {
                LegacyWorlds.LOG.warn("Cannot delete Retina region preview {}", directory, error);
            }
        }
    }

    private final NativeWorldDefinition definition;
    private final String dimension;
    private final NativeRegionBudget budget;
    private final ConcurrentHashMap<Long, Preview> previews = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, Preview> detached = new LinkedHashMap<>(16, .75F, true);
    private final int retainedLimit;
    private final ExecutorService workers;
    private final RegionLoadScheduler loads;
    private final GenerationMetrics metrics = new GenerationMetrics();
    private volatile boolean closing;

    LegacyRegionCoordinator(NativeWorldDefinition definition, String dimension, NativeRegionBudget budget) {
        this.definition = definition;
        this.dimension = dimension;
        this.budget = budget;
        this.retainedLimit = Math.min(budget.limit(), MAX_DETACHED_REGIONS);
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

    CompoundTag preview(ChunkPos position) {
        Preview cached;
        synchronized (detached) {
            if (closing) throw new CancellationException("Retina region request was cancelled");
            cached = detached.get(key(position));
        }
        if (cached != null) {
            var tag = readPreview(cached, position);
            if (tag != null) {
                metrics.previewCacheHit();
                return tag;
            }
        }
        return load(position, false, () -> CompletableFuture.supplyAsync(() -> {
            var tag = readPreview(activePreview(position), position);
            if (tag == null) throw new CancellationException("Retina preview was discarded");
            return tag;
        }, workers)).join();
    }

    private CompoundTag readPreview(Preview preview, ChunkPos position) {
        try {
            return preview.read(position, dimension);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read Retina preview for " + dimension + " " + position, error);
        }
    }

    private AutoCloseable prepare(ChunkPos position, long key, BooleanSupplier foreground, RegionCancellation cancellation) {
        Preview cached;
        synchronized (detached) {
            if (closing || cancellation.isCancelled()) throw new CancellationException("Retina region request was cancelled");
            cached = detached.remove(key);
        }
        if (cached != null) {
            previews.put(key, cached);
            metrics.previewCacheHit();
            if (QA) LegacyWorlds.LOG.info("QA_EVT retina_1201_preview_reused {} {},{} foreground={}",
                    dimension, position.getRegionX(), position.getRegionZ(), foreground.getAsBoolean());
            return () -> finish(key, cached);
        }
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
                long generated;
                metrics.begin();
                try {
                    generated = definition.generateRegion(dimension, position.getRegionX(), position.getRegionZ(), preview.path).generated();
                    if (generated != 1024) throw new IllegalStateException("Incomplete Retina region preview " + preview.path);
                    metrics.completedPreviewRegion((int) generated, System.nanoTime() - started, NativeTimings.EMPTY);
                } catch (CancellationException error) {
                    metrics.cancelled();
                    throw error;
                } catch (RuntimeException | Error error) {
                    metrics.failed();
                    throw error;
                }
                if (QA) LegacyWorlds.LOG.info(
                        "QA_EVT retina_1201_prepared {} {},{} chunks={} ms={}", dimension,
                        position.getRegionX(), position.getRegionZ(), generated, (System.nanoTime() - started) / 1_000_000);
                retained = true;
                return () -> finish(key, preview);
            } finally {
                permit.run();
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot prepare Retina region for " + dimension + " " + position, error);
        } finally {
            unsubscribe.run();
            if (!retained) {
                previews.remove(key, preview);
                preview.delete();
            }
        }
    }

    /** Runs on the same serialized queue as Minecraft's region reads and writes. */
    public void publish(ChunkPos position, LegacyRegionStorage storage) throws IOException {
        var preview = activePreview(position);
        if (preview.published) return;
        storage.retina$closeRegion(position);
        Path destination = storage.retina$folder().resolve(fileName(position));
        long started = System.nanoTime();
        metrics.begin();
        NativeWorldDefinition.RegionResult result;
        try {
            result = definition.publishRegion(dimension, position.getRegionX(), position.getRegionZ(),
                    preview.path, destination);
            queueCompression(destination, position.getRegionX(), position.getRegionZ(), false);
            if (result.generated() > 0) metrics.completedPromotion(System.nanoTime() - started);
            else metrics.cancelled();
        } catch (RuntimeException | Error error) {
            metrics.failed();
            throw error;
        }
        preview.published = true;
        if (QA) LegacyWorlds.LOG.info(
                "QA_EVT retina_1201_published {} {},{} generated={} preserved={}", dimension,
                position.getRegionX(), position.getRegionZ(), result.generated(), result.preserved());
    }

    public void closeIdlePreviewReaders() {
        long now = System.nanoTime();
        synchronized (detached) {
            detached.values().forEach(preview -> preview.closeIdleReader(now));
        }
    }

    public int limit() { return budget.limit(); }

    GenerationMetrics.Snapshot metrics() { return metrics.snapshot(); }

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
        synchronized (detached) {
            detached.values().forEach(Preview::delete);
            detached.clear();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Preview activePreview(ChunkPos position) {
        var preview = previews.get(key(position));
        if (preview == null) throw new IllegalStateException("Missing Retina region lease for " + position);
        return preview;
    }

    private void finish(long key, Preview preview) {
        previews.remove(key, preview);
        Preview discard = preview;
        synchronized (detached) {
            if (!closing && preview.readByDetachedGenerator && !preview.published) {
                var position = new ChunkPos(key);
                preview.coolAndQueue(position.x, position.z);
                detached.put(key, preview);
                discard = null;
                if (detached.size() > retainedLimit) {
                    var oldest = detached.values().iterator();
                    discard = oldest.next();
                    oldest.remove();
                }
            }
        }
        if (discard != null) discard.delete();
    }

    private static void release(AutoCloseable lease) {
        if (lease == null) return;
        try { lease.close(); }
        catch (Exception error) { LegacyWorlds.LOG.warn("Cannot release Retina region preview", error); }
    }

    private static void queueCompression(Path path, int x, int z, boolean preview) {
        var config = RetinaConfiguration.get();
        if (!config.usesLazyCompression()) return;
        int codec = config.mcaCompression() == RetinaConfig.McaCompression.LZ4
                ? RetinaConfig.McaCompression.ZLIB.codec() : config.mcaCompression().codec();
        LazyRegionCompression.enqueue(path, x, z, codec, config.mcaCompressionLevel(), preview);
    }

    private static long key(ChunkPos position) {
        return ChunkPos.asLong(position.getRegionX(), position.getRegionZ());
    }

    private static String fileName(ChunkPos position) {
        return "r." + position.getRegionX() + "." + position.getRegionZ() + ".mca";
    }
}
