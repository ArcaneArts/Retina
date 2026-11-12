package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.worldgen.LegacyChunkyPregenerator;
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
abstract class LegacyChunkyTaskMixin {
    @Unique private volatile AutoCloseable retina$ahead;
    @Unique private volatile boolean retina$stopped;

    @WrapMethod(method = "run")
    private void retina$generateSelection(Operation<Void> original) throws Exception {
        var session = retina$stopped ? null : LegacyChunkyPregenerator.start(this);
        retina$ahead = session;
        boolean dispatched = false;
        try {
            if (retina$stopped && session != null) session.close();
            original.call();
            dispatched = true;
        } finally {
            // Chunky 1.3 returns after dispatching chunk futures, before they have finished.
            if (!dispatched || retina$stopped) {
                if (session != null) session.close();
                retina$ahead = null;
            }
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void retina$stopSelection(boolean cancelled, CallbackInfo callback) throws Exception {
        retina$stopped = true;
        var session = retina$ahead;
        if (session != null) session.close();
    }
}
