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
        var nether = server.getLevel(Level.NETHER);
        if (nether != null) {
            var original = stems.getValueOrThrow(LevelStem.NETHER).generator();
            require(nether.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "Nether runtime activation");
            var adapted = (RetinaChunkGenerator) nether.getChunkSource().getGenerator();
            require(adapted.selectedSource().generator() == original, "Nether saved source remains original");
            require(adapted.dimension().equals(Level.NETHER) && adapted.previewCache().dimension().equals(Level.NETHER),
                    "Nether request/storage identity");
            require(adapted.profile().nativeId() != runtime.profile().nativeId(), "independent native dimension profiles");
            require(adapted.selectedSource().dimension() == nether.dimensionType(), "effective Nether dimension properties retained");
        }
        var end = server.getLevel(Level.END);
        if (end != null) require(end.getChunkSource().getGenerator() == stems.getValueOrThrow(LevelStem.END).generator(), "End unchanged");
        TerrainRegistryQa.check(runtime);
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_automatic_activation\",\"status\":\"pass\",\"context\":{\"source\":\"{}\",\"flat\":{},\"dedicated\":{}}}",
                source.getClass().getSimpleName(), level.isFlat(), server.isDedicatedServer());
    }

    static void tick(MinecraftServer server) {
        if (!Boolean.getBoolean("retina.qa.automatic") || checked || server.getTickCount() < 40) return;
        checked = true;
        check(server);
        boolean reopened = Boolean.getBoolean("retina.qa.automatic.reopen");
        checkSavedEdits(server, reopened);
        Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_automatic_dedicated_save\",\"status\":\"pass\",\"context\":{\"reopened\":{}}}", reopened);
        if (server.isDedicatedServer()) server.halt(false);
    }

    public static void checkSavedEdits(MinecraftServer server, boolean reopened) {
        for (var key : java.util.List.of(Level.OVERWORLD, Level.NETHER)) {
            var level = server.getLevel(key);
            if (level == null) continue;
            var position = key.equals(Level.NETHER) ? new BlockPos(-17, 200, -17) : EDIT;
            if (reopened) {
                require(level.getBlockState(position).is(Blocks.DIAMOND_BLOCK), "saved edit survives reopen in " + key.identifier());
            } else {
                level.setBlock(position, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                require(level.getBlockState(position).is(Blocks.DIAMOND_BLOCK), "edit reaches loaded chunk in " + key.identifier());
            }
            Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_dimension_saved_edit\",\"status\":\"pass\",\"context\":{\"dimension\":\"{}\",\"y\":{},\"reopened\":{}}}",
                    key.identifier(), position.getY(), reopened);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Automatic generation QA: " + message);
    }
}
