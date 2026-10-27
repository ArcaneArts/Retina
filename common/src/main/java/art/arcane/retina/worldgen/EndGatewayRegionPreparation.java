package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Prepare a superset of vanilla's search; never choose an exit or edit a gateway. */
public final class EndGatewayRegionPreparation {
    private record Request(ServerLevel level, BlockPos entry, BlockPos exit, boolean exact) { }
    private Request request;
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private long began;
    private boolean reported;
    private int count;

    public boolean ready(ServerLevel level, BlockPos entry) {
        if (!(level.getBlockEntity(entry) instanceof TheEndGatewayBlockEntity gateway)) return true;
        var metadata = (EndGatewayPreparationAccess) gateway;
        var next = new Request(level, entry.immutable(), metadata.retina$exitPortal(), metadata.retina$exactTeleport());
        // Outside the End an unknown exit is null in vanilla. Do not invent a search or destination.
        if (next.exit() == null && level.dimension() != Level.END) return true;
        if (!next.equals(request)) {
            request = next;
            var regions = destinationRegions(next.entry(), next.exit(), next.exact());
            count = regions.size(); began = System.nanoTime(); reported = false;
            pending = PortalRegionPreparation.prepare(level, regions);
        }
        if (!pending.isDone()) return false;
        pending.join();
        if (!reported && Boolean.getBoolean("retina.qa")) {
            reported = true;
            Retina.LOGGER.info("QA_EVT {\"event\":\"end_gateway_regions_ready\",\"status\":\"pass\",\"context\":{\"known_exit\":{},\"regions\":{},\"ms\":{}}}",
                    next.exit() != null, count, (System.nanoTime() - began) / 1e6);
        }
        return true;
    }

    static List<ChunkPos> destinationRegions(BlockPos entry, BlockPos exit, boolean exact) {
        if (exit != null) return PortalRegionPreparation.regions(exit, 32 + (exact ? 1 : 6));
        var regions = new LinkedHashSet<ChunkPos>();
        var direction = new Vec3(entry.getX(), 0, entry.getZ()).normalize();
        var backwards = direction.scale(-16);
        var forwards = direction.scale(16);
        var back = direction.scale(1024);
        // Match the actual repeated Vec3 additions, including possible endpoints/rounding. The
        // backward loop can stop after 0..16 steps; each forward loop can then take 0..16 steps.
        for (int skippedBack = 0; skippedBack <= 16; skippedBack++, back = back.add(backwards)) {
            var candidate = back;
            for (int skippedForward = 0; skippedForward <= 16; skippedForward++, candidate = candidate.add(forwards)) {
                int x = Mth.floor(candidate.x / 16) * 16 + 8;
                int z = Mth.floor(candidate.z / 16) * 16 + 8;
                // Entire chosen chunk + tallest-block radius16 + final exit radius5 + one rounding
                // block + two ticking rings. Also covers the <=6 island and reciprocal gateway frame.
                regions.addAll(PortalRegionPreparation.regions(new BlockPos(x, 0, z), 8 + 16 + 5 + 1 + 32));
            }
        }
        return List.copyOf(regions);
    }
}
