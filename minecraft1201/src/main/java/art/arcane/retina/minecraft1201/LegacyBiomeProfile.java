package art.arcane.retina.minecraft1201;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static art.arcane.retina.definition.FlatProfileModel.array;
import static art.arcane.retina.minecraft1201.LegacyDensityCompiler.decode;

record LegacyBiomeProfile(List<Holder<Biome>> biomes, JsonArray targets) {
    static LegacyBiomeProfile resolve(RegistryAccess registries, BiomeSource source) {
        if (source.getClass() == FixedBiomeSource.class) {
            return new LegacyBiomeProfile(List.copyOf(source.possibleBiomes()), new JsonArray());
        }
        if (source.getClass() != MultiNoiseBiomeSource.class) {
            throw new IllegalArgumentException("No 1.20.1 terrain adapter for biome source " + source.getClass().getName());
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        var multiNoise = (MultiNoiseBiomeSource) source;
        var encoded = decode(MultiNoiseBiomeSource.CODEC.encodeStart(ops, multiNoise)).getAsJsonObject();
        var parameters = encoded.has("preset")
                ? decode(MultiNoiseBiomeSourceParameterList.CODEC.parse(ops, encoded.get("preset"))).value().parameters()
                : decode(MultiNoiseBiomeSource.DIRECT_CODEC.codec().parse(ops, encoded));
        return targets(parameters.values());
    }

    private static LegacyBiomeProfile targets(List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries) {
        var biomes = new LinkedHashMap<Holder<Biome>, Integer>();
        var targets = new JsonArray();
        for (var entry : entries) {
            var point = entry.getFirst();
            var dimensions = List.of(point.temperature(), point.humidity(), point.continentalness(), point.erosion());
            var target = new JsonObject();
            target.addProperty("biome", biomes.computeIfAbsent(entry.getSecond(), biome -> biomes.size()));
            var min = new JsonArray();
            var max = new JsonArray();
            for (var parameter : dimensions) {
                min.add(Climate.unquantizeCoord(parameter.min()));
                max.add(Climate.unquantizeCoord(parameter.max()));
            }
            target.add("min", min);
            target.add("max", max);
            target.add("weirdness", interval(point.weirdness()));
            target.add("depth", interval(point.depth()));
            target.addProperty("offset", Climate.unquantizeCoord(point.offset()));
            targets.add(target);
        }
        return new LegacyBiomeProfile(new ArrayList<>(biomes.keySet()), targets);
    }

    JsonArray encode(int rock, boolean ceiling) {
        var entries = new JsonArray();
        for (var biome : biomes) {
            String id = biome.unwrapKey().orElseThrow().location().toString();
            var entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("temperature", biome.value().getBaseTemperature());
            entry.add("climate", array(0, 0, 0, 0));
            entry.add("terrain", array(0, 0, 0));
            entry.addProperty("top", rock);
            entry.addProperty("filler", rock);
            entry.addProperty("underwater", rock);
            int flags = 0;
            if (!ceiling) {
                if (biome.value().getBaseTemperature() < 0.15F) flags |= 1;
                if (biome.is(BiomeTags.IS_MOUNTAIN)) flags |= 2;
                if (biome.is(BiomeTags.IS_OCEAN)) flags |= 4;
                if (biome.is(BiomeTags.IS_BADLANDS)) flags |= 8;
                if (biome.is(Biomes.LUSH_CAVES) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.DEEP_DARK)) flags |= 16;
                if (biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.STONY_SHORE)) flags |= 64;
            }
            entry.addProperty("flags", flags);
            entries.add(entry);
        }
        return entries;
    }

    private static JsonArray interval(Climate.Parameter parameter) {
        var result = new JsonArray();
        result.add(Climate.unquantizeCoord(parameter.min()));
        result.add(Climate.unquantizeCoord(parameter.max()));
        return result;
    }
}
