package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.EndGatewayPreparationAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TheEndGatewayBlockEntity.class)
public interface EndGatewayBlockEntityAccessor extends EndGatewayPreparationAccess {
    @Override @Accessor("exitPortal") BlockPos retina$exitPortal();
    @Override @Accessor("exactTeleport") boolean retina$exactTeleport();
}
