package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record TerrainStatsPayload(boolean active, String backend, String mode, GenerationMetrics.Snapshot stats, NativeGpuDiagnostics diagnostics, String dimension) implements CustomPacketPayload {
    public TerrainStatsPayload(boolean active,String backend,String mode,GenerationMetrics.Snapshot stats,NativeGpuDiagnostics diagnostics) {
        this(active,backend,mode,stats,diagnostics,"");
    }
    public boolean activeIn(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
        return active && dimension.equals(level.identifier().toString());
    }
    public TerrainStatsPayload(boolean active,String backend,String mode,GenerationMetrics.Snapshot stats) {
        this(active,backend,mode,stats,NativeGpuDiagnostics.EMPTY);
    }
    public static final Type<TerrainStatsPayload> TYPE = new Type<>(Retina.id("terrain_stats"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TerrainStatsPayload> CODEC = CustomPacketPayload.codec(
            TerrainStatsPayload::write, TerrainStatsPayload::read);
    public static final TerrainStatsPayload INACTIVE = new TerrainStatsPayload(false, "", "",
            new GenerationMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(active);
        buffer.writeUtf(backend, 256);
        buffer.writeUtf(mode, 16);
        buffer.writeDouble(stats.chunksPerSecond());
        buffer.writeDouble(stats.msPerChunk());
        buffer.writeDouble(stats.nativeMs());
        buffer.writeDouble(stats.conversionMs());
        buffer.writeVarInt(stats.inFlight());
        buffer.writeVarLong(stats.total());
        buffer.writeVarLong(stats.failures());
        buffer.writeVarLong(stats.regions());
        buffer.writeDouble(stats.averageRegionMs());
        buffer.writeDouble(stats.p95RegionMs());
        buffer.writeVarLong(stats.previewRegions());
        buffer.writeVarLong(stats.previewCacheHits());
        buffer.writeVarLong(stats.promotions());
        stats.stages().write(buffer);
        buffer.writeVarInt(stats.regionSamples());
        stats.regionStages().write(buffer);
        buffer.writeDouble(stats.columnCacheMs());
        buffer.writeDouble(stats.completedChunksPerSecond());
        buffer.writeDouble(stats.completionWindowSeconds());
        diagnostics.write(buffer);
        buffer.writeUtf(dimension);
    }

    private static TerrainStatsPayload read(RegistryFriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        String backend = buffer.readUtf(256);
        String mode = buffer.readUtf(16);
        return new TerrainStatsPayload(active, backend, mode, new GenerationMetrics.Snapshot(
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(), buffer.readDouble(), buffer.readDouble(), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(), NativeTimings.read(buffer),buffer.readVarInt(),NativeTimings.read(buffer),buffer.readDouble(),buffer.readDouble(),buffer.readDouble()),NativeGpuDiagnostics.read(buffer),buffer.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
