package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.WorldDefinitions;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void retina$openDefinition(CallbackInfo callback) {
        art.arcane.retina.worldgen.EndLifecycleQa.prepareDefinition((MinecraftServer) (Object) this);
        WorldDefinitions.initialize((MinecraftServer) (Object) this);
    }
}
