package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;

/** Read destination metadata without resolving it, placing islands or loading chunks. */
public interface EndGatewayPreparationAccess {
    BlockPos retina$exitPortal();
    boolean retina$exactTeleport();
}
