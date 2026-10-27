package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import java.util.*;

/** Shared structural assembly after registry resolution; publishing and loading stay separate. */
final class WorldDefinitionAssembler {
    private final CompoundTag root = new CompoundTag();
    private final CompoundTag dimensions = new CompoundTag();
    private final LinkedHashMap<Material, Integer> materials = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> biomes = new LinkedHashMap<>();

    WorldDefinitionAssembler(WorldOptions options,List<String> packs,String exporter) {
        root.putInt("format_version",WorldDefinitionCompiler.FORMAT_VERSION);
        root.putInt("generation_version",WorldDefinitionCompiler.GENERATION_VERSION);
        root.putString("minecraft_version",SharedConstants.getCurrentVersion().id());
        root.putInt("data_version",SharedConstants.getCurrentVersion().dataVersion().version());
        root.putString("exporter",exporter);
        root.putLong("seed",options.seed());
        root.putBoolean("generate_structures",options.generateStructures());
        root.putBoolean("bonus_chest",options.generateBonusChest());
        var datapacks=new ListTag();
        for(String pack:packs) {var entry=new CompoundTag();entry.putString("id",pack);datapacks.add(entry);}
        root.put("datapacks",datapacks);
    }

    void unsupported(String key,CompoundTag parameters,String reason) {
        var entry=new CompoundTag();entry.put("parameters",parameters.copy());
        entry.putString("unsupported_reason",reason);
        entry.putIntArray("material_palette",new int[0]);entry.putIntArray("biome_palette",new int[0]);
        dimensions.put(key,entry);
    }

    void supported(String key,CompoundTag parameters,TerrainProfileData data,JsonObject generation) {
        var profile=JsonParser.parseString(data.json()).getAsJsonObject();
        // Minimal procedural exports keep this table only in TerrainProfileData.
        // Resolve its canonical representation before structural assembly.
        if(!profile.has("materials")) {
            var states=new JsonArray();
            for(var state:data.materials())states.add(net.minecraft.world.level.block.state.BlockState.CODEC
                    .encodeStart(com.mojang.serialization.JsonOps.INSTANCE,state).getOrThrow());
            profile.add("materials",states);
        }
        supported(key,parameters,profile,generation);
    }

    /** Resolved canonical names/properties are sufficient; binding is a later adapter step. */
    void supported(String key,CompoundTag parameters,JsonObject model,JsonObject generation) {
        var entry=new CompoundTag();var saved=parameters.copy();
        saved.putInt("sea_level",model.get("sea_level").getAsInt());entry.put("parameters",saved);
        // Only the envelope changes. ProfileNbt copies the nested programs and
        // templates into NBT before returning, without duplicating their JSON trees.
        var profile=new JsonObject();
        model.entrySet().forEach(field->profile.add(field.getKey(),field.getValue()));
        profile.addProperty("dimension",key);
        if(generation!=null)profile.add("generation",generation.deepCopy());
        var local=profile.getAsJsonArray("materials");var palette=new int[local.size()];
        for(int i=0;i<local.size();i++) {
            var material=Material.decode(local.get(i));
            palette[i]=materials.computeIfAbsent(material,ignored->materials.size());
        }
        entry.putIntArray("material_palette",palette);
        entry.putIntArray("biome_palette",profile.getAsJsonArray("biomes").asList().stream().mapToInt(b->biomes.computeIfAbsent(
                b.getAsJsonObject().get("id").getAsString(),ignored->biomes.size())).toArray());
        profile.remove("materials");profile.remove("mca_compression");profile.remove("program_execution");
        entry.put("profile",ProfileNbt.encode(profile));dimensions.put(key,entry);
    }

    private record Material(String id,Map<String,String> properties) {
        static Material decode(JsonElement encoded) {
            if(encoded.isJsonPrimitive())return new Material(encoded.getAsString(),Map.of());
            var value=encoded.getAsJsonObject();var properties=new TreeMap<String,String>();
            if(value.has("properties"))value.getAsJsonObject("properties").entrySet()
                    .forEach(p->properties.put(p.getKey(),p.getValue().getAsString()));
            return new Material(value.get("id").getAsString(),Collections.unmodifiableMap(properties));
        }
    }

    CompoundTag build() {
        var shared=new CompoundTag();var states=new ListTag();
        for(var state:materials.keySet()) {
            var entry=new CompoundTag();entry.putString("id",state.id());
            var properties=new CompoundTag();
            state.properties().forEach(properties::putString);
            entry.put("properties",properties);states.add(entry);
        }
        shared.put("materials",states);var names=new ListTag();biomes.keySet().forEach(id->names.add(StringTag.valueOf(id)));
        shared.put("biomes",names);root.put("shared_types",shared);root.put("dimensions",dimensions);
        return root;
    }
}
