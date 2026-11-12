package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyTerrainStats;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class LegacyServerPayloadMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow public ServerPlayer player;
    @Unique private long retina$lastTerrainRequest;

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void retina$terrainStats(ServerboundCustomPayloadPacket packet, CallbackInfo callback) {
        if (!LegacyTerrainStats.REQUEST.equals(packet.getIdentifier())) return;
        callback.cancel();
        if (packet.getData().isReadable()) return;

        long now = System.nanoTime();
        // Enforce the rate before queuing work on the server thread.
        if (retina$lastTerrainRequest != 0 && now - retina$lastTerrainRequest < LegacyTerrainStats.POLL_NANOS * 3 / 4) return;
        retina$lastTerrainRequest = now;
        var listener = (ServerGamePacketListenerImpl) (Object) this;
        server.execute(() -> {
            if (!listener.isAcceptingMessages()) return;
            var buffer = new FriendlyByteBuf(Unpooled.buffer(64));
            LegacyTerrainStats.forLevel(player.serverLevel()).write(buffer);
            listener.send(new ClientboundCustomPayloadPacket(LegacyTerrainStats.RESPONSE, buffer));
        });
    }
}
