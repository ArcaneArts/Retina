package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.GatewayPortalPreparationState;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hold only pending gateway pearl flight; vanilla ownership/death/tickets and base ticks continue. */
@Mixin(ThrowableProjectile.class)
abstract class ThrowableGatewayPreparationMixin extends Projectile {
    protected ThrowableGatewayPreparationMixin(EntityType<? extends Projectile> type, Level level) { super(type, level); }

    @Unique private boolean retina$waitingGateway() {
        return (Object) this instanceof ThrownEnderpearl && portalProcess instanceof GatewayPortalPreparationState state
                && state.retina$waitingForGateway();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void retina$waitBeforeFlight(CallbackInfo callback) {
        if (retina$waitingGateway()) {
            // Projectile.tick calls Entity.tick/handlePortal, so readiness and the original transfer
            // still progress. ThrownEnderpearl.tick surrounds this with its normal owner/ticket logic.
            super.tick();
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V", shift = At.Shift.AFTER), cancellable = true)
    private void retina$holdSourceImpact(CallbackInfo callback) {
        // Contact can accept a gateway during this movement, before the superclass handles portals.
        // Do not let a source-side impact destroy that pearl while the destination is publishing.
        if (retina$waitingGateway()) callback.cancel();
    }
}
