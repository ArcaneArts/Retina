package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaChunkGenerator;
import art.arcane.retina.worldgen.EndPortalPreparationAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Unique private boolean retina$pendingEndCredits;

    @Inject(method = "showEndCredits", at = @At("HEAD"), cancellable = true)
    private void retina$prepareCreditsReturn(CallbackInfo callback) {
        var player = (ServerPlayer) (Object) this;
        if (player.level().dimension() == Level.END
                && !((EndPortalPreparationAccess) player).retina$endPortalReady(player.level())) {
            retina$pendingEndCredits = true;
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void retina$finishCreditsReturn(CallbackInfo callback) {
        if (!retina$pendingEndCredits) return;
        var player = (ServerPlayer) (Object) this;
        if (player.level().dimension() != Level.END || !player.canUsePortal(false)) {
            retina$pendingEndCredits = false;
        } else if (((EndPortalPreparationAccess) player).retina$endPortalReady(player.level())) {
            retina$pendingEndCredits = false;
            player.showEndCredits();
        }
    }

    @Inject(method = "setServerLevel", at = @At("HEAD"))
    private void retina$clearPreviousSupport(ServerLevel level, CallbackInfo callback) {
        var player = (ServerPlayer) (Object) this;
        if (player.level() != level && level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator) {
            // Movement otherwise reads the old dimension's support coordinates in
            // the destination, synchronously generating unrelated remote terrain.
            player.mainSupportingBlockPos = Optional.empty();
        }
    }
}
