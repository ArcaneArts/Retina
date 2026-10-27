package art.arcane.retina.mixin;

import art.arcane.retina.worldgen.EndPortalPreparationAccess;
import art.arcane.retina.worldgen.EndPortalRegionPreparation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
abstract class EntityEndPortalMixin implements EndPortalPreparationAccess {
    @Unique private EndPortalRegionPreparation retina$endPortalPreparation;

    @Override public boolean retina$endPortalReady(ServerLevel source) {
        if (retina$endPortalPreparation == null) retina$endPortalPreparation = new EndPortalRegionPreparation();
        return retina$endPortalPreparation.ready(source, (Entity) (Object) this);
    }
}
