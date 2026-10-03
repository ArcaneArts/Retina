package art.arcane.retina.worldgen;

import java.util.ArrayDeque;
import java.util.Deque;

/** Five-second production rate and weighted timings from the last 128 chunk/region jobs. */
public final class GenerationMetrics {
    private static final long WINDOW_NANOS = 5_000_000_000L;
    private final long started = System.nanoTime();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<Sample> timings = new ArrayDeque<>();
    private int inFlight;
    private long total;
    private long failures;
    private long regions;
    private double lastRegionMs;
    private long previewRegions, previewCacheHits, promotions;
    private NativeTimings nativeTimings = NativeTimings.EMPTY;
    private NativeTimings nativeBaseline = NativeTimings.EMPTY;

    public synchronized void startNativeTimings(NativeTimings baseline) {
        nativeBaseline=baseline; nativeTimings=NativeTimings.EMPTY;
    }
    public synchronized void nativeTimings(NativeTimings value) { nativeTimings = value.since(nativeBaseline); }

    public synchronized void begin() {
        inFlight++;
    }

    public synchronized void completed(long elapsedNanos, long nativeNanos, long conversionNanos) {
        inFlight--;
        record(1, elapsedNanos, nativeNanos, conversionNanos);
    }

    public synchronized void completedRegion(int chunks, long elapsedNanos) {
        inFlight--;
        if (chunks == 0) return;
        regions++;
        lastRegionMs = elapsedNanos / 1_000_000.0;
        record(chunks, elapsedNanos, elapsedNanos, 0);
    }

    public synchronized void completedPreviewRegion(int chunks, long elapsedNanos) {
        inFlight--; previewRegions++; lastRegionMs = elapsedNanos / 1e6;
        record(chunks, elapsedNanos, elapsedNanos, 0);
    }

    public synchronized void previewCacheHit() { previewCacheHits++; }

    public synchronized void completedPromotion(long elapsedNanos) {
        inFlight--; regions++; promotions++; lastRegionMs = elapsedNanos / 1e6;
    }

    private void record(int chunks, long elapsedNanos, long nativeNanos, long conversionNanos) {
        total += chunks;
        long now = System.nanoTime();
        var sample = new Sample(now, chunks, elapsedNanos, nativeNanos, conversionNanos);
        samples.addLast(sample);
        timings.addLast(sample);
        if (timings.size() > 128) timings.removeFirst();
        prune(now);
    }

    public synchronized void failed() {
        inFlight--;
        failures++;
    }

    public synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        prune(now);
        long elapsed = 0;
        long nativeTime = 0;
        long conversion = 0;
        long timedChunks = 0;
        for (var sample : timings) {
            timedChunks += sample.chunks;
            elapsed += sample.elapsed;
            nativeTime += sample.nativeTime;
            conversion += sample.conversion;
        }
        long count = samples.stream().mapToLong(Sample::chunks).sum();
        double windowSeconds = Math.max(0.001, Math.min(WINDOW_NANOS, now - started) / 1_000_000_000.0);
        double divisor = Math.max(1, timedChunks) * 1_000_000.0;
        return new Snapshot(count / windowSeconds, elapsed / divisor, nativeTime / divisor,
                conversion / divisor, inFlight, total, failures, regions, lastRegionMs, previewRegions, previewCacheHits, promotions, nativeTimings);
    }

    private void prune(long now) {
        while (!samples.isEmpty() && now - samples.peekFirst().completedAt > WINDOW_NANOS) {
            samples.removeFirst();
        }
    }

    private record Sample(long completedAt, int chunks, long elapsed, long nativeTime, long conversion) { }

    public record Snapshot(double chunksPerSecond, double msPerChunk, double nativeMs,
                           double conversionMs, int inFlight, long total, long failures, long regions, double lastRegionMs, long previewRegions, long previewCacheHits, long promotions, NativeTimings stages) {
        public Snapshot(double chunksPerSecond, double msPerChunk, double nativeMs, double conversionMs, int inFlight,
                        long total, long failures, long regions, double lastRegionMs, long previewRegions, long previewCacheHits, long promotions) {
            this(chunksPerSecond, msPerChunk, nativeMs, conversionMs, inFlight, total, failures, regions, lastRegionMs,
                    previewRegions, previewCacheHits, promotions, NativeTimings.EMPTY);
        }
    }
}
