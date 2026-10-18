package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.ChunkyRegionPregenerator;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
abstract class ChunkyGenerationTaskMixin {
    @Unique private volatile AutoCloseable retina$ahead;
    @Unique private volatile boolean retina$stopped;

    @WrapMethod(method = "run")
    private void retina$generateSelection(Operation<Void> original) throws Exception {
        var session = retina$stopped ? null : ChunkyRegionPregenerator.start(this);
        retina$ahead = session;
        try {
            if (retina$stopped && session != null) session.close();
            original.call();
        } finally {
            if (session != null) session.close();
            retina$ahead = null;
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void retina$stopSelection(boolean cancelled, CallbackInfo callback) throws Exception {
        retina$stopped = true;
        var session = retina$ahead;
        if (session != null) session.close();
    }
}
