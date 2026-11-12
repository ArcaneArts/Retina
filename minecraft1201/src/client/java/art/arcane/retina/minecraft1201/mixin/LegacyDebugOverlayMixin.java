package art.arcane.retina.minecraft1201.mixin;

import art.arcane.retina.config.RetinaConfiguration;
import art.arcane.retina.minecraft1201.LegacyClientStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(DebugScreenOverlay.class)
abstract class LegacyDebugOverlayMixin {
    @Inject(method = "getGameInformation", at = @At("RETURN"), cancellable = true)
    private void retina$displayTerrainStats(CallbackInfoReturnable<List<String>> callback) {
        var minecraft = Minecraft.getInstance();
        if (!RetinaConfiguration.get().f3Overlay() || minecraft.level == null
                || !(minecraft.getConnection() instanceof LegacyClientStats stats)) return;
        var lines = stats.retina$terrainLines(minecraft.level.dimension().location());
        if (lines.isEmpty()) return;
        var result = new ArrayList<>(callback.getReturnValue());
        result.addAll(lines);
        callback.setReturnValue(result);
    }
}
