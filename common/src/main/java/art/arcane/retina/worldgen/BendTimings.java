package art.arcane.retina.worldgen;

import java.io.IOException;
import java.util.function.LongSupplier;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Sequential host elapsed spans, including transport, worker execution and waits.
 * These are neither summed worker CPU times nor GPU device timestamps. */
public record BendTimings(int regions, long[] nanos) {
    public enum Stage {
        INITIALIZE("Runtime / registry setup"),
        DENSITY_PLAN("Density layout"), DENSITY("Density lattice"),
        SURFACE("Surface / climate"), SHORES("Shorelines"), LAKES("Lake geometry"),
        MATERIALS("Base materials / lakes"), CAVES("Cave fields / biomes"),
        AQUIFERS("Aquifer fields"), CARVING("Cave carving"), ORES("Ores"),
        CAVE_DECORATION("Cave decoration"), VEGETATION("Vegetation / trees"),
        STRUCTURES("Structures"), WEATHER("Snow / freezing"), LIGHTING("Lighting"),
        SERIALIZE("NBT / zlib / MCA"), HEADER("MCA header / validation");

        private final String label;
        Stage(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static final int STAGES = Stage.values().length;
    public static final BendTimings EMPTY = new BendTimings(0, new long[STAGES]);
    public BendTimings {
        if (nanos.length != STAGES) throw new IllegalArgumentException("Incorrect Bend timing stage count");
        nanos = nanos.clone();
    }
    @Override public long[] nanos() { return nanos.clone(); }
    public long nanos(Stage stage) { return nanos[stage.ordinal()]; }
    public long totalNanos() { long sum = 0; for (long value : nanos) sum += value; return sum; }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(regions);
        for (long value : nanos) buffer.writeVarLong(value);
    }
    public static BendTimings read(RegistryFriendlyByteBuf buffer) {
        int regions = buffer.readVarInt();
        long[] nanos = new long[STAGES];
        for (int i = 0; i < STAGES; i++) nanos[i] = buffer.readVarLong();
        return new BendTimings(regions, nanos);
    }

    @FunctionalInterface interface Work<T> { T run() throws IOException; }
    static final class Recorder {
        private final LongSupplier clock;
        private final long[] nanos = new long[STAGES];
        Recorder() { this(System::nanoTime); }
        Recorder(LongSupplier clock) { this.clock = clock; }
        <T> T measure(Stage stage, Work<T> work) throws IOException {
            long started = clock.getAsLong();
            try { return work.run(); }
            finally { nanos[stage.ordinal()] += clock.getAsLong() - started; }
        }
        BendTimings snapshot() { return new BendTimings(1, nanos); }
    }
}
