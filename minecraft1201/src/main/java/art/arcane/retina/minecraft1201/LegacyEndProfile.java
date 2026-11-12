package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

final class LegacyEndProfile {
    private static final List<String> ENTRIES = List.of("minecraft:the_end", "minecraft:end_highlands",
            "minecraft:end_midlands", "minecraft:small_end_islands", "minecraft:end_barrens");

    private LegacyEndProfile() { }

    static boolean selected(LevelStem stem, NoiseBasedChunkGenerator generator) {
        var source = generator.getBiomeSource().getClass();
        return source == TheEndBiomeSource.class || generator.generatorSettings().is(NoiseGeneratorSettings.END)
                || source == FixedBiomeSource.class && stem.type().value().effectsLocation().equals(BuiltinDimensionTypes.END_EFFECTS);
    }

    static JsonObject compile(RegistryAccess registries, LevelStem stem, NoiseBasedChunkGenerator generator) {
        var source = generator.getBiomeSource();
        boolean fixed = source.getClass() == FixedBiomeSource.class;
        if (!fixed && source.getClass() != TheEndBiomeSource.class) {
            throw new IllegalArgumentException("Unsupported 1.20.1 End biome source " + source.getClass().getName());
        }
        var settings = generator.generatorSettings().value();
        verifyGraph(registries, settings, fixed);

        var biomes = source.possibleBiomes().stream()
                .sorted(Comparator.comparing(holder -> holder.unwrapKey().orElseThrow().location())).toList();
        var names = biomes.stream().map(holder -> holder.unwrapKey().orElseThrow().location().toString()).toList();
        var mapping = new JsonArray();
        for (String id : ENTRIES) {
            int index = fixed ? 0 : names.indexOf(id);
            if (index < 0) throw new IllegalArgumentException("1.20.1 End source is missing " + id);
            mapping.add(index);
        }

        var palette = new LinkedHashMap<BlockState, Integer>();
        var world = LegacyTerrainProfile.baseProfile(settings, palette);
        int material = world.get("stone").getAsInt();
        var end = new JsonObject();
        end.addProperty("algorithm", "legacy_1_20");
        end.addProperty("material", material);
        end.add("biomes", mapping);
        world.add("end", end);
        var biomesProfile = new LegacyBiomeProfile(biomes, new JsonArray());
        world.add("biomes", biomesProfile.encode(material, true));
        world.add("climate_targets", biomesProfile.targets());
        var noises = new JsonArray();
        // The profile buffer has four climate noise slots. End terrain does not sample them.
        for (int i = 0; i < 4; i++) {
            var unused = new JsonObject();
            unused.addProperty("frequency", 1);
            unused.addProperty("amplitude", 1);
            unused.add("modifiers", array(1));
            noises.add(unused);
        }
        world.add("noises", noises);
        LegacyTerrainProfile.terrainPreviewOmissions(world);
        WorldDefinitionCompiler.writePalette(world, palette, stem.type().value().hasSkyLight());
        return world;
    }

    private static void verifyGraph(RegistryAccess registries, NoiseGeneratorSettings settings, boolean fixed) {
        var noise = settings.noiseSettings();
        if (noise.minY() != 0 || noise.height() != 128 || noise.getCellWidth() != 8 || noise.getCellHeight() != 4
                || !settings.useLegacyRandomSource() || settings.isAquifersEnabled() || !settings.defaultFluid().isAir()) {
            throw new IllegalArgumentException("Unsupported 1.20.1 End noise settings");
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var rule = decode(SurfaceRules.RuleSource.CODEC.encodeStart(ops, settings.surfaceRule())).getAsJsonObject();
        if (!LegacyDensityCompiler.type(rule).equals("block")
                || !decode(BlockState.CODEC.parse(JsonOps.INSTANCE, rule.get("result_state"))).equals(settings.defaultBlock())
                || !settings.defaultBlock().getFluidState().isEmpty()
                || Arrays.stream(Heightmap.Types.values()).anyMatch(heightmap -> !heightmap.isOpaque().test(settings.defaultBlock()))) {
            throw new IllegalArgumentException("Unsupported 1.20.1 End surface rule");
        }

        var compiler = new LegacyDensityCompiler(registries, noise);
        var descriptor = descriptor();
        var router = settings.noiseRouter();
        var codec = DensityFunction.HOLDER_HELPER_CODEC;
        var expectedTerrain = compiler.encode(decode(codec.parse(ops, descriptor.get("final_density"))));
        var expectedErosion = compiler.encode(decode(codec.parse(ops, descriptor.get("erosion"))));
        if (!resolve(compiler, compiler.encode(router.finalDensity()), new HashSet<>()).equals(expectedTerrain)) {
            throw new IllegalArgumentException("Unsupported 1.20.1 End density graph");
        }
        if (!fixed && !resolve(compiler, compiler.encode(router.erosion()), new HashSet<>()).equals(expectedErosion)) {
            throw new IllegalArgumentException("Unsupported 1.20.1 End biome graph");
        }
    }

    private static JsonElement resolve(LegacyDensityCompiler compiler, JsonElement value, Set<String> path) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String name = value.getAsString();
            if (!path.add(name)) throw new IllegalArgumentException("Recursive 1.20.1 End density function " + name);
            var result = resolve(compiler, compiler.resolve(value), path);
            path.remove(name);
            return result;
        }
        if (!value.isJsonObject()) return value;
        var node = value.getAsJsonObject().deepCopy();
        for (String input : List.of("argument", "argument1", "argument2")) {
            if (node.has(input)) node.add(input, resolve(compiler, node.get(input), path));
        }
        return node;
    }

    private static JsonObject descriptor() {
        try (var stream = LegacyEndProfile.class.getResourceAsStream("/retina/end/standard-terrain-1.20.1.json")) {
            if (stream == null) throw new IllegalStateException("Missing 1.20.1 End terrain descriptor");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
