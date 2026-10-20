package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/** Regression cases where a maximum-width existing portal reaches another region. */
public final class PortalRegionPreparationTest {
    public static void main(String[] args) {
        check(true, 452, Set.of(new ChunkPos(0, 0), new ChunkPos(32, 0)));
        check(true, 443, Set.of(new ChunkPos(0, 0)));
        check(true, 444, Set.of(new ChunkPos(0, 0), new ChunkPos(32, 0)));
        check(true, 68, Set.of(new ChunkPos(0, 0)));
        check(true, 67, Set.of(new ChunkPos(-32, 0), new ChunkPos(0, 0)));
        check(true, -444, Set.of(new ChunkPos(-32, 0)));
        check(true, -445, Set.of(new ChunkPos(-64, 0), new ChunkPos(-32, 0)));
        check(false, 336, Set.of(new ChunkPos(0, 0), new ChunkPos(32, 0)));
        check(false, 179, Set.of(new ChunkPos(-32, 0), new ChunkPos(0, 0)));
        var origin = new HashSet<>(PortalRegionPreparation.regions(BlockPos.ZERO,
                PortalRegionPreparation.destinationRadius(true)));
        if (!origin.equals(Set.of(new ChunkPos(-32, -32), new ChunkPos(-32, 0),
                new ChunkPos(0, -32), new ChunkPos(0, 0)))) throw new AssertionError(origin);
        System.out.println("QA_EVT {\"event\":\"portal_region_coverage\",\"status\":\"pass\",\"context\":{\"cases\":19}}");
    }

    private static void check(boolean toNether, int coordinate, Set<ChunkPos> expected) {
        int radius = PortalRegionPreparation.destinationRadius(toNether);
        for (boolean transpose : new boolean[]{false, true}) {
            var center = transpose ? new BlockPos(256, 64, coordinate) : new BlockPos(coordinate, 64, 256);
            var actual = new HashSet<>(PortalRegionPreparation.regions(center, radius));
            var wanted = transpose ? expected.stream().map(pos -> new ChunkPos(pos.z(), pos.x()))
                    .collect(java.util.stream.Collectors.toSet()) : expected;
            if (!actual.equals(wanted)) throw new AssertionError(center + ": " + actual + " != " + wanted);
        }
    }
}
