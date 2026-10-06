package art.arcane.retina.client.mixin;

import art.arcane.retina.client.TerrainClientQa;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftClientQaMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void retina$gameplayQa(CallbackInfo callback) {
        TerrainClientQa.tick((Minecraft) (Object) this);
    }
}
