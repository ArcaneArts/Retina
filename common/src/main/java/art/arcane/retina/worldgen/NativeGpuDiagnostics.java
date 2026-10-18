package art.arcane.retina.worldgen;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Profile-lifetime shader, logical transfer and generated-chunk counters. */
public record NativeGpuDiagnostics(int status, long compileNanos, long sourceBytes, int nodes, int emittedNodes,
                                   int graphs, int horizontalFields, long cacheHits, long uploadBytes, long readbackBytes,
                                   long generatedChunks) {
    public static final NativeGpuDiagnostics EMPTY = new NativeGpuDiagnostics(0,0,0,0,0,0,0,0,0);
    public NativeGpuDiagnostics(int status,long compileNanos,long sourceBytes,int nodes,int emittedNodes,int graphs,int horizontalFields,long cacheHits,long uploadBytes,long readbackBytes) {
        this(status,compileNanos,sourceBytes,nodes,emittedNodes,graphs,horizontalFields,cacheHits,uploadBytes,readbackBytes,0);
    }
    public NativeGpuDiagnostics(int status,long compileNanos,long sourceBytes,int nodes,int emittedNodes,int graphs,long cacheHits,long uploadBytes,long readbackBytes) {
        this(status,compileNanos,sourceBytes,nodes,emittedNodes,graphs,0,cacheHits,uploadBytes,readbackBytes);
    }
    public String execution() { return switch(status) {case 1->"interpreter (compiling)";case 2->"specialized";case 3->"interpreter (compile failed)";case 4->"mixed (terrain interpreter)";default->"interpreter";}; }
    // Use profile-lifetime native chunks, not Java session/publication counts:
    // reopening a world or promoting cached terrain must not dilute this mean.
    // Transfers include setup/probes and work still in flight, so it is an estimate.
    public double uploadMiBPerRegion() { return perRegion(uploadBytes); }
    public double readbackMiBPerRegion() { return perRegion(readbackBytes); }
    public double totalMiBPerRegion() { return uploadMiBPerRegion()+readbackMiBPerRegion(); }
    private double perRegion(long bytes) { return generatedChunks>0 ? bytes*1024.0/generatedChunks/1048576.0 : 0; }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(status);b.writeVarLong(compileNanos);b.writeVarLong(sourceBytes);b.writeVarInt(nodes);b.writeVarInt(emittedNodes);
        b.writeVarInt(graphs);b.writeVarInt(horizontalFields);b.writeVarLong(cacheHits);b.writeVarLong(uploadBytes);b.writeVarLong(readbackBytes);
        b.writeVarLong(generatedChunks);
    }
    public static NativeGpuDiagnostics read(RegistryFriendlyByteBuf b) {
        return new NativeGpuDiagnostics(b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong());
    }
}
