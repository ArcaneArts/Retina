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
        require(runtime.savedDefinition() != null, "saved definition owns generation");
        require(runtime.selectedSource() == null, "runtime retains no selected generator");
        require(runtime.request(42, -1, -2).seed() == WorldDefinitions.get(server).seed(), "file supplies seed");
        require(runtime.profile().savedDefinition() == runtime.savedDefinition(), "one definition binding");
        var nether = server.getLevel(Level.NETHER);
        if (nether != null) {
            require(nether.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "Nether runtime activation");
            var adapted = (RetinaChunkGenerator) nether.getChunkSource().getGenerator();
            require(adapted.selectedSource() == null && adapted.savedDefinition() != null, "Nether uses saved definition");
            require(adapted.dimension().equals(Level.NETHER) && (adapted.previewCache()==null || adapted.previewCache().dimension().equals(Level.NETHER)),
                    "Nether request/storage identity");
            require(adapted.profile().nativeId() != runtime.profile().nativeId(), "independent native dimension profiles");
        }
        var end = server.getLevel(Level.END);
        if (end != null) {
            if (WorldDefinitions.get(server).dimension(Level.END).unsupportedReason() != null)
                require(end.getChunkSource().getGenerator() == stems.getValueOrThrow(LevelStem.END).generator(), "unsupported End unchanged");
            else require(end.getChunkSource().getGenerator() instanceof RetinaChunkGenerator adapted
                    && adapted.savedDefinition() != null, "supported End uses saved definition");
            if (Boolean.getBoolean("retina.qa.end.activation")) {
                require(end.getChunkSource().getGenerator() instanceof RetinaChunkGenerator, "automatic End is required in this scenario");
                var adapted = (RetinaChunkGenerator) end.getChunkSource().getGenerator();
                var saved = WorldDefinitions.get(server).dimension(Level.END);
                require(saved.unsupportedReason() == null && adapted.savedDefinition() == saved, "End owned by supported saved definition");
                require(adapted.selectedSource() == null && adapted.dimension().equals(Level.END), "End has no live generation source");
                require(com.google.gson.JsonParser.parseString(adapted.profile().json()).getAsJsonObject().has("end"), "actual native End strategy");
                require(adapted.request(0, 0, 0).seed() == WorldDefinitions.get(server).seed(), "End uses frozen seed");
                Retina.LOGGER.info("QA_EVT {\"event\":\"minecraft_automatic_end_activation\",\"status\":\"pass\",\"context\":{\"dedicated\":{}}}", server.isDedicatedServer());
            }
        }
        TerrainRegistryQa.check(runtime);
        NetherCompatibilityQa.check(server);
        String capture = System.getProperty("retina.qa.definition.capture", "");
        if (!capture.isBlank()) {
            for (var key : java.util.List.of(Level.OVERWORLD, Level.NETHER)) {
                var target = server.getLevel(key);
                if (target == null) continue;
                var generator = (RetinaChunkGenerator) target.getChunkSource().getGenerator();
                var position = new net.minecraft.world.level.ChunkPos(-64, 32);
                var directory = java.nio.file.Path.of(capture).resolve(key.equals(Level.OVERWORLD) ? "region" : "DIM-1/region");
                try { java.nio.file.Files.createDirectories(directory); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                NativeTerrain.RegionReport report;
                if (generator.regionMode()) {
                    generator.previewCache().read(position);
                    report=generator.publishPreview(position,directory.resolve("r.-2.1.mca"));
                } else report=WorldDefinitionGeometryQa.capture(target,generator,position,directory.resolve("r.-2.1.mca"));
                require(report != null, "captured native preview");
                Retina.LOGGER.info("QA_EVT {\"event\":\"world_definition_mod_region_capture\",\"dimension\":\"{}\",\"region_x\":-2,\"region_z\":1}", key.identifier());
            }
        }
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
