package art.arcane.retina.worldgen;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Profile shader compilation and actual host/device transfer counters. */
public record NativeGpuDiagnostics(int status, long compileNanos, long sourceBytes, int nodes, int emittedNodes,
                                   int graphs, int horizontalFields, long cacheHits, long uploadBytes, long readbackBytes) {
    public static final NativeGpuDiagnostics EMPTY = new NativeGpuDiagnostics(0,0,0,0,0,0,0,0,0);
    public NativeGpuDiagnostics(int status,long compileNanos,long sourceBytes,int nodes,int emittedNodes,int graphs,long cacheHits,long uploadBytes,long readbackBytes) {
        this(status,compileNanos,sourceBytes,nodes,emittedNodes,graphs,0,cacheHits,uploadBytes,readbackBytes);
    }
    public String execution() { return switch(status) {case 1->"interpreter (compiling)";case 2->"specialized";case 3->"interpreter (compile failed)";default->"interpreter";}; }
    public void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(status);b.writeVarLong(compileNanos);b.writeVarLong(sourceBytes);b.writeVarInt(nodes);b.writeVarInt(emittedNodes);
        b.writeVarInt(graphs);b.writeVarInt(horizontalFields);b.writeVarLong(cacheHits);b.writeVarLong(uploadBytes);b.writeVarLong(readbackBytes);
    }
    public static NativeGpuDiagnostics read(RegistryFriendlyByteBuf b) {
        return new NativeGpuDiagnostics(b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readVarLong());
    }
}
