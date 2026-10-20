package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
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
