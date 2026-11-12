package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyClientStats;
import art.arcane.retina.minecraft1201.LegacyTerrainStats;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.ResourceLocationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

@Mixin(ClientPacketListener.class)
abstract class LegacyClientPayloadMixin implements LegacyClientStats {
    @Shadow @Final private Minecraft minecraft;
    @Shadow public abstract void send(Packet<?> packet);
    @Unique private long retina$requestedAt;
    @Unique private long retina$receivedAt;
    @Unique private LegacyTerrainStats retina$stats;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void retina$terrainStats(ClientboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (!LegacyTerrainStats.RESPONSE.equals(packet.getIdentifier())) return;
        callback.cancel();
        var buffer = packet.getData();
        try {
            if (buffer.readableBytes() > LegacyTerrainStats.MAX_PACKET_BYTES) return;
            var stats = LegacyTerrainStats.read(buffer);
            if (buffer.isReadable()) return;
            minecraft.execute(() -> {
                if (retina$requestedAt == 0 || minecraft.getConnection() != (Object) this) return;
                retina$stats = stats;
                retina$receivedAt = System.nanoTime();
            });
        } catch (IndexOutOfBoundsException | DecoderException | ResourceLocationException ignored) {
            // A mismatched server payload must not interrupt gameplay.
        } finally {
            buffer.release();
        }
    }

    @Override
    public List<String> retina$terrainLines(ResourceLocation dimension) {
        long now = System.nanoTime();
        if (retina$requestedAt == 0 || (retina$stats != null && now - retina$requestedAt >= LegacyTerrainStats.POLL_NANOS)) {
            retina$requestedAt = now;
            send(new ServerboundCustomPayloadPacket(LegacyTerrainStats.REQUEST, new FriendlyByteBuf(Unpooled.EMPTY_BUFFER)));
        }
        var stats = retina$stats;
        if (stats == null || !dimension.equals(stats.dimension()) || now - retina$receivedAt > 4 * LegacyTerrainStats.POLL_NANOS) {
            return List.of();
        }
        return List.of("", "§b§lRetina§r", "Dimension: " + dimension,
                String.format(Locale.ROOT, "Region latency: %.0f ms mean / %.0f ms p95 (last %d)",
                        stats.averageRegionMs(), stats.p95RegionMs(), stats.samples()),
                String.format(Locale.ROOT, "Completed terrain: %.1f chunks/s (%.1fs wall window)",
                        stats.chunksPerSecond(), stats.windowSeconds()),
                "In flight: " + stats.inFlight() + "  Prepared regions: " + stats.previews() + "  Published: " + stats.publications(),
                "Terrain chunks: " + stats.chunks() + "  Failed: " + stats.failures());
    }
}
