package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.LightSeams;
import art.arcane.retina.worldgen.RetinaChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Shadow @Final private LightEngine<?,?> blockEngine;
    @Shadow @Final private LightEngine<?,?> skyEngine;
    @Unique private LightChunkGetter retina$lightChunks;
    @Inject(method="<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V",at=@At("TAIL"))
    private void retina$captureSource(LightChunkGetter source,boolean block,boolean sky,CallbackInfo callback) {
        retina$lightChunks=source;
    }
    @Inject(method="propagateLightSources",at=@At("TAIL"))
    private void retina$seedPrelitNeighbors(ChunkPos position,CallbackInfo callback) {
        if(retina$lightChunks!=null&&retina$lightChunks.getLevel() instanceof ServerLevel level
                &&level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator generator&&generator.regionMode())
            // Runs inside the existing lighting task, with direct engine calls, not per-voxel tasks.
            LightSeams.seed(retina$lightChunks,position,blockEngine,skyEngine);
    }
}
