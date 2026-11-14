package art.arcane.retina.minecraft1201;

import art.arcane.retina.core.LazyRegionCompression;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public record LegacyTerrainStats(ResourceLocation dimension, double chunksPerSecond, double windowSeconds,
                                 double averageRegionMs, double p95RegionMs, int samples,
                                 int inFlight, long chunks, long previews, long publications, long failures, long raw, long encoded, long lazyLive, long lazyPreview) {
    public static final ResourceLocation REQUEST = new ResourceLocation("retina", "terrain_stats_request_v2");
    public static final ResourceLocation RESPONSE = new ResourceLocation("retina", "terrain_stats_v2");
    public static final long POLL_NANOS = 1_000_000_000L;
    public static final int MAX_PACKET_BYTES = 1024;
    private static final int MAX_DIMENSION_LENGTH = 256;
    private static final LegacyTerrainStats INACTIVE = new LegacyTerrainStats(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public static LegacyTerrainStats forLevel(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof LegacyChunkGenerator generator)) return INACTIVE;
        var dimension = level.dimension().location();
        if (dimension.toString().length() > MAX_DIMENSION_LENGTH) return INACTIVE;
        var snapshot = generator.regions().metrics();
        var compression = LazyRegionCompression.counts();
        return new LegacyTerrainStats(dimension, snapshot.completedChunksPerSecond(), snapshot.completionWindowSeconds(),
                snapshot.averageRegionMs(), snapshot.p95RegionMs(), snapshot.regionSamples(), snapshot.inFlight(),
                snapshot.total(), snapshot.previewRegions(), snapshot.promotions(), snapshot.failures(),
                compression.rawChunks(), compression.encodedChunks(), compression.lazyLiveChunks(), compression.lazyPreviewChunks());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(dimension != null);
        if (dimension == null) return;
        buffer.writeUtf(dimension.toString(), MAX_DIMENSION_LENGTH);
        buffer.writeDouble(chunksPerSecond).writeDouble(windowSeconds).writeDouble(averageRegionMs).writeDouble(p95RegionMs);
        buffer.writeVarInt(samples).writeVarInt(inFlight);
        buffer.writeVarLong(chunks).writeVarLong(previews).writeVarLong(publications).writeVarLong(failures);
        buffer.writeVarLong(raw).writeVarLong(encoded).writeVarLong(lazyLive).writeVarLong(lazyPreview);
    }

    public static LegacyTerrainStats read(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return INACTIVE;
        return new LegacyTerrainStats(new ResourceLocation(buffer.readUtf(MAX_DIMENSION_LENGTH)),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong());
    }
}
