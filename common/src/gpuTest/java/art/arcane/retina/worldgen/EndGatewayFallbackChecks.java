package art.arcane.retina.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import java.util.*;

/** Actual vanilla island/reciprocal-gateway fallback against a controlled empty block store. */
final class EndGatewayFallbackChecks {
    static void run(EndReferenceFixtures fixture) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var level = (EmptyLevel) unsafe.allocateInstance(EmptyLevel.class);
        level.registry = fixture.registry; level.blocks = new HashMap<>(); level.entities = new HashMap<>();
        level.chunk = (EmptyChunk) unsafe.allocateInstance(EmptyChunk.class);
        level.chunkSource = (ServerChunkCache) unsafe.allocateInstance(ServerChunkCache.class);
        var map = (ChunkMap) unsafe.allocateInstance(ChunkMap.class);
        var context = ChunkMap.class.getDeclaredField("worldGenContext"); context.setAccessible(true);
        context.set(map, new WorldGenContext(level, fixture.generator, null, null, Runnable::run, null));
        var chunkMap = ServerChunkCache.class.getDeclaredField("chunkMap"); chunkMap.setAccessible(true); chunkMap.set(level.chunkSource, map);

        var entry = new BlockPos(-96, 75, 1);
        var source = new TheEndGatewayBlockEntity(entry, Blocks.END_GATEWAY.defaultBlockState());
        var destination = source.getPortalPosition(level, entry);
        if (destination == null) throw new AssertionError("actual vanilla fallback destination");
        var metadata = (EndGatewayPreparationAccess) source;
        var exit = metadata.retina$exitPortal();
        if (exit == null || metadata.retina$exactTeleport()) throw new AssertionError("source exit metadata");
        if (!(level.entities.get(exit) instanceof TheEndGatewayBlockEntity reciprocal)
                || !entry.equals(((EndGatewayPreparationAccess) reciprocal).retina$exitPortal())) throw new AssertionError("reciprocal exit");
        long islandBlocks = level.blocks.values().stream().filter(state -> state.is(Blocks.END_STONE)).count();
        if (islandBlocks == 0) throw new AssertionError("vanilla created the destination island");
        var landing = BlockPos.containing(destination).below();
        if (!level.getBlockState(landing).is(Blocks.END_STONE)) throw new AssertionError("vanilla landing on created island");
        var prepared = Set.copyOf(EndGatewayRegionPreparation.destinationRegions(entry, null, false));
        for (var pos : level.blocks.keySet()) {
            var region = new ChunkPos(Math.floorDiv(pos.getX(), 512) * 32, Math.floorDiv(pos.getZ(), 512) * 32);
            if (!prepared.contains(region)) throw new AssertionError("unprepared dynamic write " + pos);
        }
        int writes = level.writes;
        if (!destination.equals(source.getPortalPosition(level, entry)) || writes != level.writes)
            throw new AssertionError("known exit reused without another island/frame");
        for (var gateway : List.of(source, level.entities.get(exit))) {
            var tag = gateway.saveWithFullMetadata(fixture.registry);
            var reopened = BlockEntity.loadStatic(gateway.getBlockPos(), Blocks.END_GATEWAY.defaultBlockState(), tag, fixture.registry);
            if (reopened == null || !tag.equals(reopened.saveWithFullMetadata(fixture.registry))) throw new AssertionError("dynamic gateway NBT roundtrip");
        }
        System.out.println("QA_EVT {\"event\":\"end_gateway_dynamic_island\",\"status\":\"pass\",\"context\":{\"island_blocks\":" + islandBlocks + ",\"writes\":" + writes + "}}");
    }

    private static final class EmptyLevel extends ServerLevel {
        RegistryAccess.Frozen registry;
        Map<BlockPos, BlockState> blocks;
        Map<BlockPos, BlockEntity> entities;
        EmptyChunk chunk;
        ServerChunkCache chunkSource;
        int writes;
        private EmptyLevel() { super(null, Runnable::run, null, null, Level.END, null, false, 0, List.of(), false); }
        @Override public ResourceKey<Level> dimension() { return Level.END; }
        @Override public int getMinY() { return 0; }
        @Override public int getHeight() { return 256; }
        @Override public RegistryAccess registryAccess() { return registry; }
        @Override public ServerChunkCache getChunkSource() { return chunkSource; }
        @Override public LevelChunk getChunk(int x, int z) { chunk.position = new ChunkPos(x, z); return chunk; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return entities.get(pos); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags) { return setBlock(pos, state, flags, 512); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            var at = pos.immutable(); blocks.put(at, state); writes++;
            if (state.is(Blocks.END_GATEWAY)) entities.put(at, new TheEndGatewayBlockEntity(at, state));
            else entities.remove(at);
            return true;
        }
    }
    private static final class EmptyChunk extends LevelChunk {
        ChunkPos position;
        private EmptyChunk() { super(null, new ChunkPos(0, 0)); }
        @Override public ChunkPos getPos() { return position; }
        @Override public int getHighestFilledSectionIndex() { return -1; }
        @Override public int getHighestSectionPosition() { return 0; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
    }
}
