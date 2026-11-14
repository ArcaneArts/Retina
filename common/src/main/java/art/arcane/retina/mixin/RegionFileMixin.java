package art.arcane.retina.mixin;

import art.arcane.retina.core.LazyRegionCompression;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(RegionFile.class)
abstract class RegionFileMixin implements LazyRegionCompression.Handle {
    @Unique private Path retina$compressionPin;

    @Override public void retina$attachCompressionPin(Path path) { retina$compressionPin = path; }

    @Inject(method = "close", at = @At("RETURN"))
    private void retina$releaseCompressionPin(CallbackInfo callback) {
        var path = retina$compressionPin;
        retina$compressionPin = null;
        if (path != null) LazyRegionCompression.release(path);
    }
}
