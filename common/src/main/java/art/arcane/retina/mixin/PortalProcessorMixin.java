package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.PortalRegionPreparation;
import art.arcane.retina.worldgen.EndPortalPreparationAccess;
import art.arcane.retina.worldgen.EndGatewayRegionPreparation;
import art.arcane.retina.worldgen.GatewayPortalPreparationState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PortalProcessor.class)
abstract class PortalProcessorMixin implements GatewayPortalPreparationState {
    @Shadow @Final private Portal portal;
    @Unique private final PortalRegionPreparation retina$destination = new PortalRegionPreparation();
    @Unique private EndGatewayRegionPreparation retina$gateway;
    @Unique private boolean retina$waitingForGateway;

    @Override public boolean retina$waitingForGateway() { return retina$waitingForGateway; }

    @Inject(method = "processPortalTeleportation", at = @At("HEAD"))
    private void retina$beginAttempt(ServerLevel level, Entity entity, boolean allowed, CallbackInfoReturnable<Boolean> callback) {
        retina$waitingForGateway = false;
    }

    @Inject(method = "processPortalTeleportation", at = @At("RETURN"), cancellable = true)
    private void retina$prepareDestination(ServerLevel level, Entity entity, boolean allowed,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && portal.getClass() == NetherPortalBlock.class
                && !retina$destination.ready(level, entity)) callback.setReturnValue(false);
        if (callback.getReturnValueZ() && portal.getClass() == EndPortalBlock.class
                && !((EndPortalPreparationAccess) entity).retina$endPortalReady(level)) {
            // Vanilla already accepted this instant portal. Retain that acceptance while terrain is
            // publishing, even if movement takes the entity out of the portal on the following tick.
            ((PortalProcessor) (Object) this).setAsInsidePortalThisTick(true);
            callback.setReturnValue(false);
        }
        if (callback.getReturnValueZ() && portal.getClass() == EndGatewayBlock.class) {
            if (retina$gateway == null) retina$gateway = new EndGatewayRegionPreparation();
            if (!retina$gateway.ready(level, ((PortalProcessor) (Object) this).getEntryPosition())) {
                // The gateway already accepted the entity and started vanilla's cooldown. Keep the
                // instant processor alive without requiring another contact during that cooldown.
                retina$waitingForGateway = true;
                ((PortalProcessor) (Object) this).setAsInsidePortalThisTick(true);
                callback.setReturnValue(false);
            }
        }
    }
}
