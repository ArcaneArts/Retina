package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaGenerators;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    // Runs on creation AND reopen, after pack resolution and before ServerChunkCache/ChunkMap construction.
    // Keeping the registry's LevelStem intact also retains flat/debug metadata and source serialization.
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/dimension/LevelStem;generator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"))
    private ChunkGenerator retina$activate(ChunkGenerator generator, @Local(argsOnly = true) LevelStem stem,
                                         @Local(argsOnly = true) ResourceKey<Level> dimension,
                                         @Local(argsOnly = true) net.minecraft.server.MinecraftServer server) {
        return RetinaGenerators.adapt(server, dimension, stem, generator);
    }
}
