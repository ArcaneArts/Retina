package art.arcane.retina.worldgen;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.*;

/** One-time compilation only. No native library or GPU is initialized here. */
public final class WorldDefinitionCompiler {
    public static final int FORMAT_VERSION = 2;
    public static final int GENERATION_VERSION = 1;

    private WorldDefinitionCompiler() { }

    public static CompoundTag compile(MinecraftServer server) {
        if (Boolean.getBoolean("retina.qa.definition.failCompiler"))
            throw new IllegalStateException("World definition compiler intentionally disabled by QA");
        var stems = new LinkedHashMap<ResourceKey<LevelStem>, LevelStem>();
        server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).listElements()
                .forEach(holder -> stems.put(holder.key(), holder.value()));
        String build = WorldDefinitionCompiler.class.getPackage().getImplementationVersion();
        return compile(server.registryAccess(), server.getWorldGenSettings().options(), stems,
                StructureProfile.Context.of(server.getStructureTemplateManager(),
                        server.getWorldGenSettings().options().generateStructures()),
                server.getResourceManager().listPacks().map(pack -> pack.packId()).toList(),
                "Retina " + (build == null ? "development" : build));
    }

    static CompoundTag compile(HolderLookup.Provider registry, WorldOptions options,
                               Map<ResourceKey<LevelStem>, LevelStem> stems,
                               StructureProfile.Context structures, List<String> packs, String exporter) {
        var assembly = new WorldDefinitionAssembler(options, packs, exporter);
        var effectiveStructures = new StructureProfile.Context(structures.templates(),
                structures.enabled() && options.generateStructures(), structures.allowedSets());
        var keys = new ArrayList<>(stems.keySet());
        keys.sort(Comparator.comparing(key -> key.identifier().toString()));
        for (var key : keys) {
            var stem = stems.get(key);
            var level = Registries.levelStemToLevel(key);
            var generator = stem.generator();
            var type = stem.type().value();
            var parameters = parameters(generator, type);
            String unsupported = RetinaGenerators.unsupportedReason(level, generator);
            if (unsupported != null) {
                assembly.unsupported(level.identifier().toString(), parameters, unsupported);
            } else {
                var source = generator instanceof RetinaChunkGenerator ? null : new GenerationSource(level, generator, type);
                var data = generator instanceof RetinaChunkGenerator retina
                        ? retina.exportDefinition(registry, options.seed(), effectiveStructures, type.hasSkyLight())
                        : export(registry, source, options.seed(), effectiveStructures);
                assembly.supported(level.identifier().toString(), parameters, data, source == null ? null : source.describe());
            }
        }
        return assembly.build();
    }

    static CompoundTag parameters(net.minecraft.world.level.chunk.ChunkGenerator generator,
                                  net.minecraft.world.level.dimension.DimensionType type) {
        var parameters = new CompoundTag();
        parameters.putString("generator", generator.getClass().getName());
        parameters.putInt("min_y", type.minY());
        parameters.putInt("height", type.height());
        var storage = new CompoundTag();
        storage.putInt("min_y", type.minY()); storage.putInt("height", type.height());
        parameters.put("storage", storage);
        parameters.putInt("generator_depth", generator.getGenDepth());
        parameters.putInt("sea_level", generator.getSeaLevel());
        parameters.putInt("spawn_height", generator.getSpawnHeight(LevelHeightAccessor.create(type.minY(), type.height())));
        parameters.putBoolean("skylight", type.hasSkyLight());
        parameters.putBoolean("ceiling", type.hasCeiling());
        parameters.putInt("logical_height", type.logicalHeight());
        parameters.putDouble("coordinate_scale", type.coordinateScale());
        parameters.putFloat("base_height", 64F);
        parameters.putFloat("amplitude", 48F);
        parameters.putFloat("frequency", .008F);
        if (generator instanceof RetinaChunkGenerator retina) retina.definitionParameters(parameters);
        return parameters;
    }

    static TerrainProfileData export(HolderLookup.Provider registry, GenerationSource source, long seed,
                                     StructureProfile.Context structures) {
        if (source.flat()) return FlatTerrainProfile.export(registry, source, seed, structures, source.dimension().hasSkyLight());
        if (source.endTerrain()) return EndTerrainProfile.export(registry, source, seed, structures);
        return TerrainProfileData.export(registry, RetinaBiomeSource.fromSelected(source.generator().getBiomeSource()),
                source.dimension().minY(), source.dimension().height(), seed, source.noise().generatorSettings().value(),
                structures, true, source.dimension().hasSkyLight(), source.enclosedTerrain());
    }
}
