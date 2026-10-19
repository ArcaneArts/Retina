package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.LevelStem;

/** Opt-in checks of the actual server construction boundary and persisted source definition. */
public final class AutomaticGenerationQa {
    private static boolean checked;
    private static final BlockPos EDIT = new BlockPos(-17, 120, -17);

    private AutomaticGenerationQa() { }

    public static void check(MinecraftServer server) {
        var level = server.overworld();
        require(level.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "runtime activation");
        var runtime = (RetinaChunkGenerator) level.getChunkSource().getGenerator();
        var stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        var source = stems.getValueOrThrow(LevelStem.OVERWORLD).generator();
        if (runtime.selectedSource() != null) {
            require(source == runtime.selectedSource().generator(), "saved registry retains original generator");
            require(!(source instanceof RetinaChunkGenerator), "source remains a normal world type");
            require(server.getWorldData().isFlatWorld() == runtime.selectedSource().flat(), "flat gameplay metadata");
            require(runtime.getSpawnHeight(level) == source.getSpawnHeight(level), "source spawn height");
        } else require(source == runtime, "legacy world is not wrapped twice");
        for (var dimension : java.util.List.of(Level.NETHER, Level.END)) {
            var other = server.getLevel(dimension);
            if (other != null) {
                var key = net.minecraft.resources.ResourceKey.create(Registries.LEVEL_STEM, dimension.identifier());
                require(other.getChunkSource().getGenerator() == stems.getValueOrThrow(key).generator(), "deferred dimension unchanged");
            }
        }
        TerrainRegistryQa.check(runtime);
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_automatic_activation\",\"status\":\"pass\",\"context\":{\"source\":\"{}\",\"flat\":{},\"dedicated\":{}}}",
                source.getClass().getSimpleName(), level.isFlat(), server.isDedicatedServer());
    }

    static void tick(MinecraftServer server) {
        if (!Boolean.getBoolean("retina.qa.automatic") || checked || server.getTickCount() < 40) return;
        checked = true;
        check(server);
        var level = server.overworld();
        boolean reopened = Boolean.getBoolean("retina.qa.automatic.reopen");
        if (reopened) require(level.getBlockState(EDIT).is(Blocks.DIAMOND_BLOCK), "dedicated saved edit survives reopen");
        else {
            level.setBlock(EDIT, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            require(level.getBlockState(EDIT).is(Blocks.DIAMOND_BLOCK), "dedicated edit reaches loaded chunk");
        }
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_automatic_dedicated_save\",\"status\":\"pass\",\"context\":{\"reopened\":{}}}", reopened);
        if (server.isDedicatedServer()) server.halt(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Automatic generation QA: " + message);
    }
}
