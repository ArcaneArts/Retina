package art.arcane.retina.minecraft1201;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Flat capture surface for the vanilla landmark piece methods. All writes are local to one capture. */
final class LegacyLandmarkCapture implements WorldGenLevel {
    static final int SURFACE = 64;
    private static final long SEED = 1945L;

    private final RegistryAccess registries;
    private final RandomSource random = RandomSource.create(SEED);
    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    private final Map<ChunkPos, ProtoChunk> chunks = new HashMap<>();

    LegacyLandmarkCapture(RegistryAccess registries) {
        this.registries = registries;
    }

    Map<BlockPos, BlockState> blocks() { return blocks; }
    @Override public long getSeed() { return SEED; }
    @Override public RandomSource getRandom() { return random; }
    @Override public RegistryAccess registryAccess() { return registries; }
    @Override public int getHeight() { return 384; }
    @Override public int getMinBuildHeight() { return -64; }
    @Override public int getHeight(Heightmap.Types type, int x, int z) { return SURFACE; }
    @Override public boolean isClientSide() { return false; }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return entities.get(pos); }
    @Override public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, (pos.getY() < SURFACE ? Blocks.STONE : Blocks.AIR).defaultBlockState());
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }
    @Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }
    @Override public boolean hasChunk(int x, int z) { return true; }
    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        var pos = new ChunkPos(x, z);
        return chunks.computeIfAbsent(pos, at ->
                new ProtoChunk(at, UpgradeData.EMPTY, this, registries.registryOrThrow(Registries.BIOME), null));
    }

    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int depth) {
        var key = pos.immutable();
        blocks.put(key, state);
        var entity = state.getBlock() instanceof EntityBlock type ? type.newBlockEntity(key, state) : null;
        if (entity == null) entities.remove(key);
        else entities.put(key, entity);
        return true;
    }

    @Override public ServerLevel getLevel() { throw unsupported(); }
    @Override public long dayTime() { throw unsupported(); }
    @Override public long nextSubTickCount() { throw unsupported(); }
    @Override public LevelTickAccess<Block> getBlockTicks() { throw unsupported(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { throw unsupported(); }
    @Override public LevelData getLevelData() { throw unsupported(); }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { throw unsupported(); }
    @Override public MinecraftServer getServer() { throw unsupported(); }
    @Override public ChunkSource getChunkSource() { throw unsupported(); }
    @Override public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) { throw unsupported(); }
    @Override public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) { throw unsupported(); }
    @Override public void levelEvent(Player player, int event, BlockPos pos, int data) { throw unsupported(); }
    @Override public void gameEvent(GameEvent event, Vec3 pos, GameEvent.Context context) { throw unsupported(); }
    @Override public int getSkyDarken() { throw unsupported(); }
    @Override public BiomeManager getBiomeManager() { throw unsupported(); }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { throw unsupported(); }
    @Override public int getSeaLevel() { throw unsupported(); }
    @Override public DimensionType dimensionType() { throw unsupported(); }
    @Override public FeatureFlagSet enabledFeatures() { throw unsupported(); }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { throw unsupported(); }
    @Override public boolean destroyBlock(BlockPos pos, boolean drop, Entity entity, int depth) { throw unsupported(); }
    @Override public float getShade(Direction direction, boolean shade) { throw unsupported(); }
    @Override public LevelLightEngine getLightEngine() { throw unsupported(); }
    @Override public int getBlockTint(BlockPos pos, ColorResolver color) { throw unsupported(); }
    @Override public WorldBorder getWorldBorder() { throw unsupported(); }
    @Override public List<Entity> getEntities(Entity entity, AABB box, Predicate<? super Entity> predicate) { throw unsupported(); }
    @Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB box, Predicate<? super T> predicate) { throw unsupported(); }
    @Override public List<? extends Player> players() { throw unsupported(); }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Retina landmark capture has no live world");
    }
}
