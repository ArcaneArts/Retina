package art.arcane.retina.core;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/** Runs on the owning I/O queue. Lets vanilla release recently idle region handles before LRU eviction. */
public final class IdleRegionFiles {
    public static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final LinkedHashMap<Long, Long> accessed = new LinkedHashMap<>(16, 0.75f, true);

    @FunctionalInterface public interface Close { void close(long key) throws IOException; }

    public void touch(long key) { accessed.put(key, System.nanoTime()); }
    public void remove(long key) { accessed.remove(key); }

    public void closeIdle(Close close) throws IOException {
        long now = System.nanoTime();
        var iterator = accessed.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() < IDLE_NANOS) break;
            long key = entry.getKey();
            iterator.remove();
            close.close(key);
        }
    }
}
