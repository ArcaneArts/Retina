package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Run vanilla's private gateway search against controlled occupancy, checking every actual read. */
final class EndGatewayPreparationTest {
    static void run() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var search = net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity.class
                .getDeclaredMethod("findExitPortalXZPosTentative", ServerLevel.class, BlockPos.class);
        search.setAccessible(true);
        int cases = 0;
        for (var entry : List.of(new BlockPos(0, 75, 0), new BlockPos(96, 75, 0), new BlockPos(-96, 75, 0),
                new BlockPos(0, 75, 96), new BlockPos(0, 75, -96), new BlockPos(68, 75, 68), new BlockPos(-68, 75, 68),
                new BlockPos(68, 75, -68), new BlockPos(-68, 75, -68), new BlockPos(3120, 75, -1549),
                new BlockPos(-16384, 75, 32), new BlockPos(29999999, 75, -29999998))) {
            var prepared = Set.copyOf(EndGatewayRegionPreparation.destinationRegions(entry, null, false));
            if (prepared.size() > 12) throw new AssertionError("unbounded preparation: " + prepared);
            for (int pattern = 0; pattern < 6; pattern++) {
                var source = (SearchLevel) unsafe.allocateInstance(SearchLevel.class);
                source.pattern = pattern; source.reads = new HashSet<>();
                source.empty = (OccupancyChunk) unsafe.allocateInstance(OccupancyChunk.class); source.empty.highest = -1;
                source.solid = (OccupancyChunk) unsafe.allocateInstance(OccupancyChunk.class); source.solid.highest = 0;
                var selected = (Vec3) search.invoke(null, source, entry);
                for (var read : source.reads) covered(prepared, read.x() * 16, read.z() * 16, 0);
                // Complete selected chunk, tallest-block scan, final exit, rounding and ticking rings.
                int cx = net.minecraft.util.Mth.floor(selected.x / 16) * 16;
                int cz = net.minecraft.util.Mth.floor(selected.z / 16) * 16;
                covered(prepared, cx, cz, 54); covered(prepared, cx + 15, cz + 15, 54);
                cases++;
            }
        }
        for (var exit : List.of(new BlockPos(-513, 75, 511), new BlockPos(0, 75, 0), new BlockPos(511, 75, 512))) {
            covered(Set.copyOf(EndGatewayRegionPreparation.destinationRegions(BlockPos.ZERO, exit, true)), exit.getX(), exit.getZ(), 33);
            covered(Set.copyOf(EndGatewayRegionPreparation.destinationRegions(BlockPos.ZERO, exit, false)), exit.getX(), exit.getZ(), 38);
        }
        System.out.println("QA_EVT {\"event\":\"end_gateway_search_coverage\",\"status\":\"pass\",\"context\":{\"vanilla_searches\":" + cases + ",\"known_exits\":6}}");
    }

    private static void covered(Set<ChunkPos> prepared, int x, int z, int radius) {
        for (int rz = Math.floorDiv(z - radius, 512); rz <= Math.floorDiv(z + radius, 512); rz++)
            for (int rx = Math.floorDiv(x - radius, 512); rx <= Math.floorDiv(x + radius, 512); rx++)
                if (!prepared.contains(new ChunkPos(rx * 32, rz * 32))) throw new AssertionError("missing region " + rx + "," + rz);
    }

    private static final class SearchLevel extends ServerLevel {
        int pattern;
        Set<ChunkPos> reads;
        OccupancyChunk empty, solid;
        // Constructor is never run: source search uses only the overridden chunk lookup.
        private SearchLevel() { super(null, Runnable::run, null, null, Level.END, null, false, 0, List.of(), false); }
        @Override public LevelChunk getChunk(int x, int z) {
            reads.add(new ChunkPos(x, z));
            boolean occupied = switch (pattern) {
                case 0 -> false;
                case 1 -> true;
                case 2 -> (long) x * x + (long) z * z < 64 * 64;
                case 3 -> (long) x * x + (long) z * z >= 64 * 64;
                case 4 -> ((x + z) & 1) == 0;
                default -> ((x * 73428767 ^ z * 912931) & 7) < 3;
            };
            return occupied ? solid : empty;
        }
    }

    private static final class OccupancyChunk extends LevelChunk {
        int highest;
        private OccupancyChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public int getHighestFilledSectionIndex() { return highest; }
    }
}
