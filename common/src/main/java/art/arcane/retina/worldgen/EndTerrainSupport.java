package art.arcane.retina.worldgen;

import com.google.gson.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compare loaded expressions, not registry names or hashes. Unsupported custom
 * graphs remain with their original generator during the staged rollout. */
final class EndTerrainSupport {
    private EndTerrainSupport() { }

    static String unsupportedReason(HolderLookup.Provider registry, GenerationSource source) {
        if (source.flat()) return "End terrain requires a noise generator";
        var biomeSource = source.generator().getBiomeSource();
        if (!(biomeSource instanceof TheEndBiomeSource) && !(biomeSource instanceof FixedBiomeSource))
            return "Unsupported End biome source " + biomeSource.getClass().getName();
        var settings = source.noise().generatorSettings().value();
        if (settings.noiseSettings().minY() != 0 || settings.noiseSettings().height() != 128)
            return "Unsupported End noise bounds";
        if (!settings.useLegacyRandomSource() || settings.aquifers().isPresent() || !settings.defaultFluid().isAir())
            return "Unsupported End random source or fluids";
        if (!(settings.materialRule().value() instanceof BlockRule rule) || !rule.resultState().equals(settings.defaultBlock())
                || java.util.Arrays.stream(net.minecraft.world.level.levelgen.Heightmap.Types.values()).anyMatch(t -> !t.isOpaque().test(settings.defaultBlock())) || !settings.defaultBlock().getFluidState().isEmpty())
            return "Unsupported End material rule";
        var compiler = new RegistryGpuProgram(registry, source.dimension().minY(), source.dimension().height(), settings.seaLevel());
        var expected = standardGraph();
        // Decode the resource through Minecraft's codec too. Gson otherwise
        // compares codec-produced Float primitives with resource Double values
        // after widening (e.g. 0.64f), despite identical encoded JSON.
        var ops = registry.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var codec = net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CODEC;
        var finalGraph = compiler.encode(codec.parse(ops, expected.get("final_density")).getOrThrow());
        var biomeGraph = compiler.encode(codec.parse(ops, expected.get("erosion")).getOrThrow());
        if (!resolve(compiler, compiler.encode(settings.noiseRouter().finalDensity()), new HashSet<>()).equals(finalGraph))
            return "Unsupported End density graph";
        if (biomeSource instanceof TheEndBiomeSource && !resolve(compiler, compiler.encode(settings.noiseRouter().erosion()), new HashSet<>()).equals(biomeGraph))
            return "Unsupported End biome island graph";
        return null;
    }

    private static JsonObject standardGraph() {
        try (var input = EndTerrainSupport.class.getResourceAsStream("/retina/end/standard-terrain-26.3.json")) {
            if (input == null) throw new IllegalStateException("Missing standard End graph descriptor");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    static JsonElement resolve(RegistryGpuProgram compiler, JsonElement value, Set<String> path) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String key = value.getAsString();
            if (!path.add(key)) throw new IllegalArgumentException("Recursive End density function " + key);
            var result = resolve(compiler, compiler.resolve(value), path); path.remove(key); return result;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        var result = value.getAsJsonObject().deepCopy();
        for (String key : List.of("input", "left", "right", "alpha", "first", "second"))
            if (result.has(key)) result.add(key, resolve(compiler, result.get(key), path));
        return result;
    }
}
