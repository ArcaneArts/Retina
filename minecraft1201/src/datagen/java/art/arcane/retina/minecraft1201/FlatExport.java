package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.BinaryDefinitionTags;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Headless datagen using the same pack and registry loading path as the 1.20.1 server. */
public final class FlatExport {
    private FlatExport() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path world = Path.of(args[0]).toAbsolutePath();
        long seed = Long.parseLong(args[1]);
        Path packFolder = world.resolve("datapacks");
        Files.createDirectories(packFolder);
        long started = System.nanoTime();
        Loaded loaded = load(packFolder);
        var definition = FlatDefinitionCompiler.compile(loaded.registries(), loaded.dimensions(),
                new WorldOptions(seed, false, false), loaded.packs());
        Path path = BinaryDefinitionTags.writeNew(world, definition);
        var event = new JsonObject();
        event.addProperty("event", "flat_1201_definition_export");
        event.addProperty("version", SharedConstants.getCurrentVersion().getName());
        event.addProperty("data_version", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        event.addProperty("path", path.toString());
        event.addProperty("dimensions", loaded.dimensions().size());
        event.addProperty("bytes", Files.size(path));
        var selectedPacks = new JsonArray();
        loaded.packs().forEach(selectedPacks::add);
        event.add("packs", selectedPacks);
        var overworld = loaded.dimensions().getHolderOrThrow(LevelStem.OVERWORLD).value().generator();
        if (overworld instanceof FlatLevelSource flat) {
            event.addProperty("biome", flat.settings().getBiome().unwrapKey().orElseThrow().location().toString());
        }
        event.addProperty("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        System.out.println("QA_EVT " + event);
        if (args.length == 3 && args[2].equals("--verify")) {
            FlatExportVerification.verify(world, loaded.registries(), loaded.dimensions(), seed);
        }
    }

    private static Loaded load(Path packs) throws Exception {
        var repository = ServerPacksSource.createPackRepository(packs);
        var config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(repository, WorldDataConfiguration.DEFAULT, false, true),
                Commands.CommandSelection.DEDICATED, 2);
        return WorldLoader.<WorldDimensions.Complete, Loaded>load(config, context -> {
            var presets = context.datapackWorldgen().registryOrThrow(Registries.WORLD_PRESET);
            var dimensions = presets.getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
            var complete = dimensions.bake(context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM));
            return new WorldLoader.DataLoadOutput<>(complete, complete.dimensionsRegistryAccess());
        }, (resources, reloaded, layers, dimensions) -> {
            try (resources) {
                return new Loaded(layers.compositeAccess(), dimensions.dimensions(),
                        resources.listPacks().map(PackResources::packId).toList());
            }
        }, Util.backgroundExecutor(), Runnable::run).get(120, TimeUnit.SECONDS);
    }

    private record Loaded(RegistryAccess.Frozen registries, Registry<LevelStem> dimensions, List<String> packs) { }
}
