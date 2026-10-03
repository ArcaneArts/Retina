package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import java.util.*;

/** Projects climate noise leaves and horizontal scales; it does not execute the density router. */
final class ClimateNoiseProfile {
    private record Leaf(Identifier noise, double scale) { }

    static JsonArray export(HolderLookup.Provider registry, NoiseGeneratorSettings settings, boolean imported) {
        var result = new JsonArray();
        var router = settings.noiseRouter();
        var channels = List.of(router.temperature(),router.vegetation(),router.continents(),router.erosion(),router.ridges());
        var names = List.of("temperature","vegetation","continentalness","erosion","ridge");
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        for (int i=0;i<channels.size();i++) {
            var preferred = Identifier.withDefaultNamespace(names.get(i));
            var leaves = new ArrayList<Leaf>();
            if (imported) collect(registry,DensityFunction.CODEC.encodeStart(ops,channels.get(i)).getOrThrow(),new HashSet<>(),leaves,0);
            var leaf = leaves.stream().filter(l -> l.noise.equals(preferred)).findFirst()
                    .orElseGet(() -> leaves.isEmpty() ? new Leaf(preferred,1) : leaves.getFirst());
            var value = registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,leaf.noise)).value();
            var data = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,value).getOrThrow().getAsJsonObject();
            var entry = new JsonObject();
            double base = Math.scalb(1.0,data.get("base_octave").getAsInt());
            entry.addProperty("frequency",base*leaf.scale);
            entry.addProperty("amplitude",data.has("base_amplitude") ? data.get("base_amplitude").getAsDouble() : 1.0);
            entry.addProperty("source",leaf.noise.toString());
            entry.addProperty("xz_scale",leaf.scale);
            double reference = Math.scalb(1.0,NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,
                    registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,preferred)).value()).getOrThrow().getAsJsonObject().get("base_octave").getAsInt());
            entry.addProperty("spacing_ratio",reference/(base*leaf.scale));
            var modifiers = new JsonArray(); var supplied = data.getAsJsonArray("amplitude_modifiers");
            int count = data.has("octave_count") ? data.get("octave_count").getAsInt() : 1;
            for(int octave=0;octave<count;octave++)modifiers.add(supplied!=null && octave<supplied.size() ? supplied.get(octave).getAsDouble() : 1.0);
            entry.add("modifiers",modifiers); result.add(entry);
        }
        return result;
    }

    static float siteScale(JsonArray channels, float configured) {
        double log = 0;
        for(int i=0;i<4;i++)log+=Math.log(channels.get(i).getAsJsonObject().get("spacing_ratio").getAsDouble());
        return (float)Math.clamp(configured*Math.exp(log/4),128,4096);
    }

    private static void collect(HolderLookup.Provider registry, JsonElement value, Set<String> visited, List<Leaf> leaves, int depth) {
        if(depth>64)return;
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String name = value.getAsString(); var id = Identifier.tryParse(name);
            if(id==null || !visited.add(name))return;
            registry.lookupOrThrow(Registries.DENSITY_FUNCTION).get(ResourceKey.create(Registries.DENSITY_FUNCTION,id)).ifPresent(h ->
                    collect(registry,net.minecraft.world.level.levelgen.densityfunction.DensityFunctions.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE),h.value()).getOrThrow(),visited,leaves,depth+1));
        } else if(value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if(object.has("noise") && object.get("noise").isJsonPrimitive() && object.has("xz_scale")) {
                var id = Identifier.tryParse(object.get("noise").getAsString());
                double scale = Math.abs(object.get("xz_scale").getAsDouble());
                if(id!=null && scale>0)leaves.add(new Leaf(id,scale));
                return;
            }
            // The outside branch usually carries the base climate signal;
            // conditional terms and shifts remain approximations.
            if(object.has("when_out_of_range"))collect(registry,object.get("when_out_of_range"),visited,leaves,depth+1);
            for(var entry:object.entrySet())if(!entry.getKey().equals("type") && !entry.getKey().equals("when_out_of_range"))
                collect(registry,entry.getValue(),visited,leaves,depth+1);
        } else if(value.isJsonArray())for(var child:value.getAsJsonArray())collect(registry,child,visited,leaves,depth+1);
    }
}
