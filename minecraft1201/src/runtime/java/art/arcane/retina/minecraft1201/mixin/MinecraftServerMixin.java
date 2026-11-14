package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.minecraft1201.LegacyRegionIo;
import art.arcane.retina.minecraft1201.LegacyWorlds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "createLevels", at = @At("HEAD"))
    private void retina$openDefinition(ChunkProgressListener progress, CallbackInfo callback) {
        LegacyWorlds.initialize((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void retina$sweepIdleRegions(java.util.function.BooleanSupplier hasTime, CallbackInfo callback) {
        var server = (MinecraftServer) (Object) this;
        if (server.getTickCount() % 100 != 0 || !RetinaConfiguration.get().usesLazyCompression()) return;
        for (var level : server.getAllLevels()) {
            if (level.getChunkSource().chunkScanner() instanceof LegacyRegionIo io) io.retina$sweepIdleRegions();
        }
    }

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void retina$closeDefinition(CallbackInfo callback) {
        LegacyWorlds.close((MinecraftServer) (Object) this);
    }
}
