package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.*;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;

/** Initialize registered provider stacks once; spatial sampling belongs to the GPU. */
final class ProviderNoiseProfile {
    final JsonArray programs = new JsonArray();
    private final LinkedHashMap<String,Integer> ids = new LinkedHashMap<>();
    private final HolderLookup.Provider registry;
    ProviderNoiseProfile(HolderLookup.Provider registry) { this.registry=registry; }

    int register(JsonObject provider,boolean slow) {
        var parameters=provider.get(slow?"slow_noise":"noise");
        float scale=provider.get(slow?"slow_scale":"scale").getAsFloat();
        long seed=provider.get("seed").getAsLong();
        String key=parameters+"/"+Float.toHexString(scale)+"/"+seed+"/"+slow;
        var prior=ids.get(key);if(prior!=null)return prior;
        var noise=NormalNoise.DIRECT_CODEC.parse(registry.createSerializationContext(JsonOps.INSTANCE),parameters).getOrThrow();
        int id=programs.size();programs.add(export(noise,scale,seed,slow));ids.put(key,id);return id;
    }
    static JsonObject export(NormalNoise parameters,float scale,long seed) {
        return export(parameters,scale,seed,false);
    }
    static JsonObject export(NormalNoise parameters,float scale,long seed,boolean slow) {
        var noise=parameters.create(new WorldgenRandom(new LegacyRandomSource(seed)));
        var result=new JsonObject();var layers=new JsonArray();
        for(var layer:(Object[])field(noise,"layers")) {
            var perlin=field(layer,"noise");
            var encoded=new JsonObject();encoded.addProperty("frequency",((Number)field(layer,"frequency")).doubleValue()*(slow?1:scale));
            encoded.addProperty("amplitude",((Number)field(layer,"amplitude")).floatValue());
            var offsets=new JsonArray();for(String axis:new String[]{"X","Y","Z"})offsets.add(((Number)field(perlin,"offset"+axis)).doubleValue());encoded.add("offsets",offsets);
            var permutation=new JsonArray();for(byte p:(byte[])field(perlin,"perms"))permutation.add(Byte.toUnsignedInt(p));encoded.add("permutation",permutation);
            layers.add(encoded);
        }
        result.add("layers",layers);if(slow)result.addProperty("coordinate_scale",scale);return result;
    }
    private static Object field(Object object,String name) {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass()) {
            try { Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object); }
            catch(NoSuchFieldException ignored) { }
            catch(ReflectiveOperationException error) {throw new IllegalStateException("Cannot export provider noise field "+name,error);}
        }
        throw new IllegalStateException("Missing provider noise field "+name+" in "+object.getClass());
    }
}
