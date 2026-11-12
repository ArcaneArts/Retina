package art.arcane.retina.fabric1201.mixin;

import art.arcane.retina.minecraft1201.LegacyStructurePiece;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltInRegistries.class)
abstract class StructurePieceRegistrationMixin {
    @Inject(method = "freeze", at = @At("HEAD"))
    private static void retina$registerPiece(CallbackInfo callback) {
        Registry.register(BuiltInRegistries.STRUCTURE_PIECE, LegacyStructurePiece.ID, LegacyStructurePiece.TYPE);
    }
}
