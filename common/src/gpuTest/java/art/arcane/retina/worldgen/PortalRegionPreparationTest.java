package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/** Regression cases where a maximum-width existing portal reaches another region. */
public final class PortalRegionPreparationTest {
    public static void main(String[] args) throws Exception {
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
        checkEnd();
        EndGatewayPreparationTest.run();
        System.out.println("QA_EVT {\"event\":\"portal_region_coverage\",\"status\":\"pass\",\"context\":{\"nether_cases\":19,\"end_spawn_cases\":30}}");
    }

    private static void checkEnd() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var arena = new HashSet<>(PortalRegionPreparation.regions(BlockPos.ZERO, EndPortalRegionPreparation.ARENA_RADIUS));
        if (!arena.equals(Set.of(new ChunkPos(-32, -32), new ChunkPos(-32, 0),
                new ChunkPos(0, -32), new ChunkPos(0, 0)))) throw new AssertionError(arena);
        // Use the loaded Minecraft class's candidate permutation, not a saved copy of its constants.
        var type = net.minecraft.server.level.PlayerSpawnFinder.class;
        var constructor = type.getDeclaredConstructor(net.minecraft.server.level.ServerLevel.class, BlockPos.class, int.class);
        constructor.setAccessible(true);
        var countField = type.getDeclaredField("candidateCount"); countField.setAccessible(true);
        var strideField = type.getDeclaredField("coprime"); strideField.setAccessible(true);
        var offsetField = type.getDeclaredField("offset"); offsetField.setAccessible(true);
        for (int coordinate : new int[]{-513, -1, 0, 511, 512}) for (int radius : new int[]{0, 1, 10, 16, 1000, 100000}) {
            var center = new BlockPos(coordinate, 80, -coordinate);
            var actual = Set.copyOf(EndPortalRegionPreparation.sharedSpawnRegions(center, radius));
            var finder = constructor.newInstance(null, center, radius);
            int count = countField.getInt(finder), stride = strideField.getInt(finder), offset = offsetField.getInt(finder);
            for (int attempt = 0; attempt < count; attempt++) {
                int candidate = (offset + stride * attempt) % count;
                int x = center.getX() + candidate % (radius * 2 + 1) - radius;
                int z = center.getZ() + candidate / (radius * 2 + 1) - radius;
                covered(actual, x, z);
            }
            covered(actual, center.getX(), center.getZ()); // Final fallback is always consulted if candidates fail.
            if (actual.size() > 16) throw new AssertionError("unbounded spawn preparation: " + actual.size());
        }
    }

    private static void covered(Set<ChunkPos> regions, int x, int z) {
        for (int dx : new int[]{-33, 33}) for (int dz : new int[]{-33, 33}) {
            var required = new ChunkPos(Math.floorDiv(x + dx, 512) * 32, Math.floorDiv(z + dz, 512) * 32);
            if (!regions.contains(required)) throw new AssertionError("unprepared spawn neighbor " + required);
        }
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
