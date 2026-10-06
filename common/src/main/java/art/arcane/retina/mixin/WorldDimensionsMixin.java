package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.RetinaDimensions;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.Map;

@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
    @Shadow @Final private Map<ResourceKey<LevelStem>, LevelStem> dimensions;

    @ModifyVariable(method = "bake", at = @At("HEAD"), argsOnly = true)
    private Registry<LevelStem> retina$preserveSelectedGenerator(Registry<LevelStem> packs) {
        return RetinaDimensions.retainSelection(dimensions, packs);
    }
}
