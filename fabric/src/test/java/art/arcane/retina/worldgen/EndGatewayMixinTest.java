package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Fabric transformations, without opening another client or generating regions. */
class EndGatewayMixinTest {
    @Test void gatewayMetadataAndPendingPearlFlightUseTransformedClasses() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.core.registries.BuiltInRegistries.bootStrap();
        var gateway = new TheEndGatewayBlockEntity(BlockPos.ZERO, Blocks.END_GATEWAY.defaultBlockState());
        var exit = new BlockPos(-513, 81, 1024);
        gateway.setExitPosition(exit, true);
        var metadata = (EndGatewayPreparationAccess) gateway;
        assertEquals(exit, metadata.retina$exitPortal()); assertTrue(metadata.retina$exactTeleport());

        try (var fixture = new EndReferenceFixtures()) {
            var pearl = new HeldPearl(EndEntityFixture.create(fixture.registry));
            pearl.setPos(96.5, 75.5, .5); pearl.setDeltaMovement(-1, -.1, .2);
            pearl.portalProcess = new PortalProcessor((Portal) Blocks.END_GATEWAY, new BlockPos(96, 75, 0));
            var waiting = PortalProcessor.class.getDeclaredField("retina$waitingForGateway"); waiting.setAccessible(true);
            waiting.setBoolean(pearl.portalProcess, true);
            assertTrue(((GatewayPortalPreparationState) pearl.portalProcess).retina$waitingForGateway());
            Vec3 position = pearl.position(), motion = pearl.getDeltaMovement();
            pearl.tick();
            assertEquals(position, pearl.position(), "no flight during accepted pending gateway");
            assertEquals(motion, pearl.getDeltaMovement(), "no gravity/inertia during held flight");
            assertEquals(1, pearl.baseTicks, "vanilla projectile base tick still runs");
            assertFalse(pearl.isRemoved());
        }
    }

    private static final class HeldPearl extends ThrownEnderpearl {
        int baseTicks;
        HeldPearl(Level level) { super(EntityTypes.ENDER_PEARL, level); }
        // This isolated fixture verifies the mixin's held-flight branch. Real readiness, ownership,
        // ticket processing and destination impact are covered by the integrated gateway scenario.
        @Override public void baseTick() { baseTicks++; }
        @Override public void gameEvent(Holder<GameEvent> event, Entity source) { }
    }
}
