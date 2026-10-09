package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RegionCoordinator;
import art.arcane.retina.worldgen.RegionStorageBridge;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow protected abstract ChunkGenerator generator();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void retina$attachStorage(CallbackInfo callback) {
        if (generator() instanceof RetinaChunkGenerator retina) {
            retina.bindWorld(level.registryAccess(), level.getSeed(),level.getStructureTemplateManager(),level.getServer().getWorldGenSettings().options().generateStructures(),level.dimensionType().hasSkyLight());
            if (!retina.regionMode()) return;
            var bounds = retina.request(level.getSeed(), 0, 0);
            if (bounds.minY() != level.getMinY() || bounds.height() != level.getHeight()) {
                throw new IllegalArgumentException("Retina MCA bounds must match the dimension bounds for stored sections and heightmaps");
            }
            var worker = (IOWorker) ((ChunkMap) (Object) this).chunkScanner();
            var storage = (RegionStorageBridge) (Object) ((IOWorkerAccessor) worker).retina$storage();
            var coordinator = new RegionCoordinator(retina, level.getSeed(), storage.retina$folder());
            storage.retina$configure(coordinator);
            if (retina.bendMode()) retina.bindBendStorage(coordinator, ((ChunkMap)(Object)this)::read);
        }
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void retina$skipBendStatusOnlySave(ChunkAccess chunk, CallbackInfoReturnable<Boolean> callback) {
        if (generator() instanceof RetinaChunkGenerator retina && retina.bendMode()
                && chunk.getPersistedStatus().isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN)) {
            // These holders only contain preliminary biome/status data. Saving
            // them would overwrite a completed neighbor from Bend's sparse batch.
            chunk.tryMarkSaved();
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "handleChunkLoadFailure", at = @At("HEAD"))
    private void retina$surfaceRegionFailure(Throwable error, ChunkPos position,
                                             CallbackInfoReturnable<ChunkAccess> callback) {
        if (generator() instanceof RetinaChunkGenerator retina && retina.regionMode()) {
            // Vanilla substitutes an empty chunk after a failed read. Region mode must expose
            // the actual file/GPU failure instead of generating terrain over an unreadable save.
            throw new ReportedException(CrashReport.forThrowable(error, "Loading Retina MCA chunk " + position));
        }
    }
}
