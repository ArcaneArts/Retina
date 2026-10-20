package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.PortalRegionPreparation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PortalProcessor.class)
abstract class PortalProcessorMixin {
    @Shadow @Final private Portal portal;
    @Unique private final PortalRegionPreparation retina$destination = new PortalRegionPreparation();

    @Inject(method = "processPortalTeleportation", at = @At("RETURN"), cancellable = true)
    private void retina$prepareDestination(ServerLevel level, Entity entity, boolean allowed,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && portal.getClass() == NetherPortalBlock.class
                && !retina$destination.ready(level, entity)) callback.setReturnValue(false);
    }
}
