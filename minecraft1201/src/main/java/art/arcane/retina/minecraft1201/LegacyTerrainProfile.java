package art.arcane.retina.minecraft1201;

import art.arcane.retina.definition.TerrainBandModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

final class LegacyTerrainProfile {
    private LegacyTerrainProfile() { }

    static JsonObject compile(RegistryAccess registries, LevelStem stem, NoiseBasedChunkGenerator generator, long seed) {
        var type = stem.type().value();
        var settings = generator.generatorSettings().value();
        var storage = LevelHeightAccessor.create(type.minY(), type.height());
        var context = new WorldGenerationContext(generator, storage);
        var volume = settings.noiseSettings().clampToHeightAccessor(storage);
        var compiler = new LegacyDensityCompiler(registries, settings.noiseSettings());
        var biomeProfile = LegacyBiomeProfile.resolve(registries, generator.getBiomeSource());
        var palette = new LinkedHashMap<BlockState, Integer>();

        var world = baseProfile(settings, palette);
        int rock = world.get("stone").getAsInt();
        world.add("biomes", biomeProfile.encode(rock, type.hasCeiling()));
        world.add("climate_targets", biomeProfile.targets());

        // Keep the fallback noise metadata required by the profile layout. Registered
        // terrain and climate sample the actual compiled router below.
        var noises = new JsonArray();
        for (String id : List.of("temperature", "vegetation", "continentalness", "erosion")) {
            noises.add(fallbackNoise(compiler, id));
        }
        world.add("noises", noises);
        world.add("weirdness_noise", fallbackNoise(compiler, "ridge"));

        var router = settings.noiseRouter();
        var climate = compiler.new Program();
        for (var fn : List.of(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.ridges(), router.depth())) {
            climate.roots.add(climate.density(compiler.encode(fn)));
        }
        compiler.programs.add(climate.finish());

        var finalDensity = compiler.encode(router.finalDensity());
        var surface = compiler.new Program();
        surface.roots.add(surface.density(finalDensity));
        surface.roots.add(surface.constant(volume.minY() + volume.height()));
        compiler.programs.add(surface.finish());
        var density = compiler.new Program();
        density.roots.add(density.density(finalDensity));
        compiler.programs.add(density.finish());

        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var surfaceRule = decode(SurfaceRules.RuleSource.CODEC.encodeStart(ops, settings.surfaceRule())).getAsJsonObject();
        boolean usesBands = false;
        for (var biome : biomeProfile.biomes()) {
            var program = compiler.new Program();
            var surfaceCompiler = new LegacySurfaceProgram(program, palette, biome, context.getMinGenY(), context.getGenDepth());
            int root = surfaceCompiler.rule(surfaceRule);
            usesBands |= surfaceCompiler.usesBands();
            program.roots.add(root);
            program.roots.add(root);
            compiler.programs.add(program.finish());
        }
        if (usesBands) {
            int[] materials = LegacySurfaceProgram.bandStates().stream().mapToInt(state -> material(palette, state)).toArray();
            var random = settings.getRandomSource().newInstance(seed).forkPositional().fromHashOf(new ResourceLocation("clay_bands"));
            var bands = new JsonArray();
            for (int color : TerrainBandModel.colors(random::nextInt, random::nextBoolean)) bands.add(materials[color]);
            var features = new JsonObject();
            features.add("bands", bands);
            world.add("terrain_features", features);
        }

        var aquifer = new JsonObject();
        aquifer.addProperty("enabled", settings.isAquifersEnabled());
        if (settings.isAquifersEnabled()) {
            aquifer.addProperty("program", compiler.programs.size());
            var flooded = compiler.new Program();
            flooded.roots.add(flooded.density(compiler.encode(router.fluidLevelFloodednessNoise())));
            flooded.roots.add(0);
            compiler.programs.add(flooded.finish());
            for (var fn : List.of(router.fluidLevelSpreadNoise(), router.lavaNoise(), router.barrierNoise())) {
                var program = compiler.new Program();
                program.roots.add(program.density(compiler.encode(fn)));
                compiler.programs.add(program.finish());
            }
            var probe = compiler.new Program();
            int initial = probe.density(compiler.encode(router.initialDensityWithoutJaggedness()));
            // The old preliminary surface checks initial density against 25/64.
            probe.roots.add(probe.node(5, initial, probe.constant(0.390625F), 0));
            probe.roots.add(probe.constant(volume.minY() + volume.height()));
            compiler.programs.add(probe.finish());
            aquifer.add("surface", array(volume.minY(), volume.getCellHeight(), 0));
            compiler.approximations.add("aquifer:gpu-positional-hash");
            compiler.approximations.add("aquifer:4x4x4-barrier-interpolation");
            compiler.approximations.add("aquifer:preliminary-surface-minimum-on-miss");
        }

        var generation = new JsonObject();
        generation.addProperty("storage_min_y", type.minY());
        generation.addProperty("storage_height", type.height());
        generation.addProperty("min_y", context.getMinGenY());
        generation.addProperty("height", context.getGenDepth());
        generation.addProperty("terrain_min_y", volume.minY());
        generation.addProperty("terrain_height", Math.max(0, volume.height()));
        generation.addProperty("ceiling", type.hasCeiling());
        world.add("generation_bounds", generation);

        var program = new JsonObject();
        program.add("generation", generation);
        program.add("aquifer", aquifer);
        program.add("programs", compiler.programs);
        program.add("noises", compiler.noises);
        program.add("points", compiler.points);
        program.add("interpolations", compiler.interpolations);
        program.add("terrain_cell", array(volume.getCellWidth(), volume.getCellHeight()));
        program.add("surface", array(volume.minY(), volume.getCellHeight(), 0));
        program.addProperty("density_composition", true);
        program.addProperty("material_layers", true);
        var surfaceNoises = new JsonArray();
        for (String id : List.of("surface", "surface_secondary", "clay_bands_offset")) {
            surfaceNoises.add(compiler.noise(new JsonPrimitive("minecraft:" + id)));
        }
        program.add("surface_noises", surfaceNoises);
        compiler.approximations.add("material:preliminary-surface-from-final-height");
        compiler.approximations.add("material:shared-vertical-gradient-randomness");
        compiler.approximations.add("geology:shared-carver-shapes");
        compiler.approximations.add("geology:union-of-carver-replacement-sets");
        compiler.approximations.add("geology:shared-ore-attempt-stream");
        var approximations = new JsonArray();
        compiler.approximations.forEach(approximations::add);
        program.add("approximations", approximations);
        world.add("registry_program", program);

        terrainPreviewOmissions(world);
        LegacyGeologyProfile.export(registries, biomeProfile, world, palette, context, storage,
                type.hasCeiling(), settings.oreVeinsEnabled(), compiler);
        WorldDefinitionCompiler.writePalette(world, palette, type.hasSkyLight());
        return world;
    }

    static JsonObject baseProfile(NoiseGeneratorSettings settings, LinkedHashMap<BlockState, Integer> palette) {
        var world = new JsonObject();
        world.addProperty("biome_scale", 128);
        world.addProperty("blend", 0.55);
        world.addProperty("sea_level", settings.seaLevel());
        world.addProperty("generator_depth", settings.noiseSettings().height());
        material(palette, Blocks.AIR.defaultBlockState());
        world.addProperty("stone", material(palette, settings.defaultBlock()));
        world.addProperty("water", material(palette, settings.defaultFluid()));
        world.addProperty("bedrock", material(palette, Blocks.BEDROCK.defaultBlockState()));
        world.addProperty("deepslate", material(palette, Blocks.DEEPSLATE.defaultBlockState()));
        world.addProperty("snow", material(palette, Blocks.SNOW_BLOCK.defaultBlockState()));
        world.addProperty("ice", material(palette, Blocks.ICE.defaultBlockState()));
        world.addProperty("lava", material(palette, Blocks.LAVA.defaultBlockState()));
        world.addProperty("lava_level", Math.min(-54, settings.seaLevel()));
        return world;
    }

    static void terrainPreviewOmissions(JsonObject world) {
        world.add("decoration_omissions", omissions("1.20.1 terrain preview omits placed decorations"));
        world.add("geology_omissions", omissions("1.20.1 terrain preview omits configured carvers, ores and ore veins"));
        var structures = new JsonObject();
        structures.add("templates", new JsonArray());
        structures.add("definitions", new JsonArray());
        structures.add("pools", new JsonObject());
        structures.add("sets", new JsonArray());
        structures.add("omissions", omissions("1.20.1 terrain preview omits structures"));
        world.add("structures", structures);
    }

    private static JsonArray omissions(String reason) {
        var report = new JsonArray();
        report.add(reason);
        return report;
    }

    static JsonObject fallbackNoise(LegacyDensityCompiler compiler, String id) {
        var parameters = compiler.noiseParameters(new JsonPrimitive("minecraft:" + id));
        var noise = new JsonObject();
        noise.addProperty("frequency", Math.scalb(1.0, parameters.get("base_octave").getAsInt()));
        noise.addProperty("amplitude", 1);
        noise.add("modifiers", parameters.get("amplitude_modifiers"));
        return noise;
    }

    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }
}
