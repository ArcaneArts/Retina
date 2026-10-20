package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.ChunkyLifecycle;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Bind to each real Chunky lifetime, including spawn chunks before its initializer. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor", remap = false)
abstract class DistantHorizonsChunkyMixin {
    @Shadow private boolean listenerBound;
    @Unique private long retina$chunkyGeneration = -1;

    @WrapMethod(method = "tryRunFirstTimeSetup")
    private void retina$retryChunkySetup(Operation<Void> original) {
        synchronized (this) {
            long generation = ChunkyLifecycle.generation();
            if (generation != retina$chunkyGeneration) {
                listenerBound = false;
                retina$chunkyGeneration = generation;
            }
            try {
                original.call();
            } catch (IllegalStateException error) {
                var trace = error.getStackTrace();
                if (!"Chunky is not loaded.".equals(error.getMessage()) || trace.length == 0
                        || !trace[0].getClassName().equals("org.popcraft.chunky.ChunkyProvider")
                        || !trace[0].getMethodName().equals("get")) {
                    throw error;
                }
                // DH sets this before binding. Leave setup pending so the next chunk
                // update binds the real listener once Chunky has initialized.
                listenerBound = false;
            }
        }
    }
}
