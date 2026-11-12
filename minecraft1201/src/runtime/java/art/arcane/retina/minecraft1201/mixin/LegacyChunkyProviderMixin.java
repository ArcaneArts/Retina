package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.worldgen.ChunkyLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidate optional listeners when the actual Chunky provider changes. */
@Pseudo
@Mixin(targets = "org.popcraft.chunky.ChunkyProvider", remap = false)
abstract class LegacyChunkyProviderMixin {
    @Inject(method = {"register", "unregister"}, at = @At("RETURN"))
    private static void retina$providerChanged(CallbackInfo callback) {
        ChunkyLifecycle.changed();
    }
}
