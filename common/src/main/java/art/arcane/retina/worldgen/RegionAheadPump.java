package art.arcane.retina.worldgen;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Streams a task's distinct region origins into a bounded set of asynchronous publication jobs. */
final class RegionAheadPump implements AutoCloseable {
    interface Source { Long next(BooleanSupplier stopped) throws Exception; }
    record Result(long completed, boolean stopped, Throwable failure) {}
    private final Executor dispatcher;
    private final Supplier<Source> sourceFactory;
    private final LongFunction<? extends CompletableFuture<?>> generate;
    private final Consumer<Result> finished;
    private final int parallelism;
    private final AtomicBoolean stopped = new AtomicBoolean();
    // All remaining state is confined to the serial dispatcher.
    private Source source;
    private int inFlight;
    private long completed;
    private boolean exhausted;
    private boolean reported;
    private Throwable failure;

    RegionAheadPump(Executor dispatcher, int parallelism, Supplier<Source> sourceFactory,
                    LongFunction<? extends CompletableFuture<?>> generate, Consumer<Result> finished) {
        if (parallelism < 1) throw new IllegalArgumentException("Region parallelism must be positive");
        this.dispatcher = dispatcher;
        this.parallelism = parallelism;
        this.sourceFactory = sourceFactory;
        this.generate = generate;
        this.finished = finished;
    }

    void start() { dispatcher.execute(this::fill); }

    private void fill() {
        if (reported) return;
        try {
            if (!stopped.get() && source == null) source = sourceFactory.get();
            while (!stopped.get() && !exhausted && inFlight < parallelism) {
                Long key = source.next(stopped::get);
                if (key == null) { exhausted = true; break; }
                if (stopped.get()) break;
                var job = generate.apply(key);
                inFlight++;
                // Never recurse inline for already-generated regions, or scan a selection on the I/O queue.
                job.whenComplete((value, error) -> dispatcher.execute(() -> {
                    inFlight--;
                    if (error == null) completed++;
                    else fail(error);
                    fill();
                }));
            }
        } catch (Throwable error) { fail(error); }
        if (inFlight == 0 && (exhausted || stopped.get())) {
            reported = true;
            finished.accept(new Result(completed, stopped.get(), failure));
        }
    }

    private void fail(Throwable error) {
        if (failure == null) failure = error;
        stopped.set(true);
    }

    @Override public void close() {
        if (stopped.compareAndSet(false, true)) dispatcher.execute(this::fill);
    }
}
