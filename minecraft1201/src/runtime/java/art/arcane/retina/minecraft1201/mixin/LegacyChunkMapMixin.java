package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyWorlds;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
abstract class LegacyChunkMapMixin {
    @Shadow protected abstract ChunkGenerator generator();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void retina$attachStorage(CallbackInfo callback) {
        LegacyWorlds.attachRegionIo(generator(), ((ChunkMap) (Object) this).chunkScanner());
    }

    @Inject(method = "handleChunkLoadFailure", at = @At("HEAD"))
    private void retina$reportLoadFailure(Throwable error, ChunkPos position,
            CallbackInfoReturnable<?> callback) {
        if (LegacyWorlds.usesRegionIo(generator())) {
            throw new ReportedException(CrashReport.forThrowable(error, "Loading Retina 1.20.1 MCA chunk " + position));
        }
    }
}
