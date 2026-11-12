package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.minecraft1201.LegacyWorlds;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/dimension/LevelStem;generator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"))
    private ChunkGenerator retina$activate(ChunkGenerator generator, @Local(argsOnly = true) MinecraftServer server,
                                           @Local(argsOnly = true) ResourceKey<Level> dimension, @Local(argsOnly = true) LevelStem stem) {
        return LegacyWorlds.adapt(server, dimension, stem, generator);
    }
}
