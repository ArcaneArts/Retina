package art.arcane.retina.worldgen;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Stage snapshot. Session CPU counters add across workers; region CPU counters estimate wall shares. */
public record NativeTimings(int flags, long chunks, long gpuColumns, long gpuJobs, long[] nanos) {
    public static final int VERSION = 8;
    public static final int STAGES = 33;
    public static final int BYTES = 32 + STAGES * Long.BYTES;
    public static final int QUEUE=0, ENCODE=1, WAIT_COPY=2, HEIGHT=3, SITES=4, COLUMNS=5,
            CAVE_DENSITY=6, CAVE_MASK=7, STRUCTURE_PLAN=8, VEGETATION_PLAN=9, ORE_PLAN=10,
            ASSEMBLY=11, GEOLOGY=12, CAVE_FEATURES=13, VEGETATION=14, STRUCTURES=15,
            SNOW=16, NBT=17, COMPRESS=18, IO=19, MATERIALS=20, AQUIFER_FIELDS=21, AQUIFER_MASK=22, FEATURE_COUNTS=23, ORE_MASK=24, PROVIDER_NOISE=25,
            LAKE_CANDIDATES=26, LAKE_DENSITY=27, LAKE_REDUCE=28,
            LIGHT_HOST=29, LIGHT_SKY=30, LIGHT_SPREAD=31, LIGHT_PACK=32;
    public static final NativeTimings EMPTY = new NativeTimings(0,0,0,0,new long[STAGES]);
    public NativeTimings {
        if (nanos.length != STAGES) throw new IllegalArgumentException("Incorrect native timing stage count");
        nanos = nanos.clone();
    }
    @Override public long[] nanos() { return nanos.clone(); }
    public long nanos(int stage) { return nanos[stage]; }
    public boolean gpuMeasured() { return (flags & 1) != 0; }
    public boolean providerMeasured() { return (flags & 4) != 0; }
    public boolean oreMeasured() { return (flags & 2) != 0; }
    public boolean lightingMeasured() { return (flags & 8) != 0; }
    public double chunkMs(int stage) { return nanos[stage] / (Math.max(1,chunks) * 1e6); }
    public double dispatchMs(int stage) { return nanos[stage] / (Math.max(1,gpuJobs) * 1e6); }
    public double gpuChunkMs(int stage) { return nanos[stage] * 256.0 / (Math.max(256,gpuColumns) * 1e6); }
    public long workerNanos() { long sum=0; for(int i=ASSEMBLY;i<=COMPRESS;i++) sum+=nanos[i]; return sum; }
    public double workerPercent(int stage) { return 100.0 * nanos[stage] / Math.max(1,workerNanos()); }
    public NativeTimings since(NativeTimings baseline) {
        long[] delta=new long[STAGES];
        for(int i=0;i<STAGES;i++) delta[i]=Math.max(0,nanos[i]-baseline.nanos[i]);
        return new NativeTimings(flags,Math.max(0,chunks-baseline.chunks),Math.max(0,gpuColumns-baseline.gpuColumns),
                Math.max(0,gpuJobs-baseline.gpuJobs),delta);
    }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(flags); buffer.writeVarLong(chunks); buffer.writeVarLong(gpuColumns); buffer.writeVarLong(gpuJobs);
        for(long value:nanos) buffer.writeVarLong(value);
    }
    public static NativeTimings read(RegistryFriendlyByteBuf buffer) {
        int flags=buffer.readVarInt(); long chunks=buffer.readVarLong(), columns=buffer.readVarLong(), jobs=buffer.readVarLong();
        long[] values=new long[STAGES]; for(int i=0;i<STAGES;i++) values[i]=buffer.readVarLong();
        return new NativeTimings(flags,chunks,columns,jobs,values);
    }
}
