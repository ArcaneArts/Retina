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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Headless datagen using the same pack and registry loading path as the 1.20.1 server. */
public final class RegistryExport {
    private RegistryExport() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path world = Path.of(args[0]).toAbsolutePath();
        long seed = Long.parseLong(args[1]);
        Path packFolder = world.resolve("datapacks");
        Files.createDirectories(packFolder);
        long started = System.nanoTime();
        boolean noise = args[2].equals("noise");
        var preset = ResourceKey.create(Registries.WORLD_PRESET, new ResourceLocation(args[3]));
        boolean structures = Arrays.asList(args).contains("--structures");
        boolean verify = Arrays.asList(args).contains("--verify");
        try (var loaded = load(packFolder, preset)) {
            export(world, seed, started, noise, preset, loaded, structures, verify);
            if (Arrays.asList(args).contains("--verify-jigsaws")) JigsawVerification.verify(world, loaded, seed);
            if (Arrays.asList(args).contains("--verify-portals")) NetherPortalVerification.verify(world, loaded, seed);
            if (Arrays.asList(args).contains("--verify-attachments")) AttachmentVerification.verify(world);
            if (Arrays.asList(args).contains("--verify-flat-worldgen")) FlatWorldgenVerification.verify(world, loaded, seed);
        }
    }

    private static void export(Path world, long seed, long started, boolean noise, ResourceKey<WorldPreset> preset,
                               Loaded loaded, boolean structures, boolean verify) throws Exception {
        var definition = WorldDefinitionCompiler.compile(loaded.registries(), loaded.dimensions(),
                new WorldOptions(seed, structures, false), loaded.packs(), noise, loaded::template);
        Path path = BinaryDefinitionTags.writeNew(world, definition);
        var event = new JsonObject();
        event.addProperty("event", noise ? "noise_1201_definition_export" : "flat_1201_definition_export");
        event.addProperty("preset", preset.location().toString());
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
        if (verify) {
            if (structures) {
                if (loaded.packs().contains("file/retina-landmarks")) {
                    LandmarkVerification.verify(world, loaded, seed);
                } else if (loaded.packs().contains("file/retina-nether-fortress")) {
                    NetherFortressVerification.verify(world, loaded, seed);
                } else {
                    EndCityVerification.verify(world, loaded, seed);
                    NetherFossilVerification.verify(world, loaded, seed);
                }
            } else if (noise) {
                boolean vanilla = seed == 12345 && preset.equals(WorldPresets.NORMAL) && loaded.packs().equals(List.of("vanilla"));
                NoiseExportVerification.verify(world, loaded.registries(), loaded.dimensions(), seed, vanilla);
            } else {
                FlatExportVerification.verify(world, loaded.registries(), loaded.dimensions(), seed);
            }
        }
    }

    static Loaded load(Path packs, ResourceKey<WorldPreset> preset) throws Exception {
        var repository = ServerPacksSource.createPackRepository(packs);
        var config = new WorldLoader.InitConfig(
                new WorldLoader.PackConfig(repository, WorldDataConfiguration.DEFAULT, false, true),
                Commands.CommandSelection.DEDICATED, 2);
        return WorldLoader.<WorldDimensions.Complete, Loaded>load(config, context -> {
            var presets = context.datapackWorldgen().registryOrThrow(Registries.WORLD_PRESET);
            var dimensions = presets.getHolderOrThrow(preset).value().createWorldDimensions();
            var complete = dimensions.bake(context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM));
            return new WorldLoader.DataLoadOutput<>(complete, complete.dimensionsRegistryAccess());
        }, (resources, reloaded, layers, dimensions) ->
                new Loaded(layers.compositeAccess(), dimensions.dimensions(),
                        resources.listPacks().map(PackResources::packId).toList(), resources),
                Util.backgroundExecutor(), Runnable::run).get(120, TimeUnit.SECONDS);
    }

    record Loaded(RegistryAccess.Frozen registries, Registry<LevelStem> dimensions, List<String> packs,
                  CloseableResourceManager resources) implements AutoCloseable {
        Optional<CompoundTag> template(ResourceLocation id) {
            var path = id.withPath("structures/" + id.getPath() + ".nbt");
            return resources.getResource(path).map(resource -> {
                try (var input = resource.open()) {
                    var raw = NbtIo.readCompressed(input);
                    var updated = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(),
                            raw, NbtUtils.getDataVersion(raw, 500));
                    var template = new StructureTemplate();
                    template.load(registries.lookupOrThrow(Registries.BLOCK), updated);
                    return template.save(new CompoundTag());
                } catch (IOException error) {
                    throw new UncheckedIOException("Cannot read structure template " + id, error);
                }
            });
        }

        @Override public void close() { resources.close(); }
    }
}
