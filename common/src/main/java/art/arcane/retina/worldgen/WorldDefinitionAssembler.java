package art.arcane.retina.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import java.util.*;

/** Shared structural assembly after registry resolution; publishing and loading stay separate. */
final class WorldDefinitionAssembler {
    private final CompoundTag root = new CompoundTag();
    private final CompoundTag dimensions = new CompoundTag();
    private final LinkedHashMap<BlockState, Integer> materials = new LinkedHashMap<>();
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
        var entry=new CompoundTag();var saved=parameters.copy();saved.putInt("sea_level",data.seaLevel());
        entry.put("parameters",saved);
        var profile=JsonParser.parseString(data.json()).getAsJsonObject();
        profile.addProperty("dimension",key);
        if(generation!=null)profile.add("generation",generation.deepCopy());
        profile.remove("materials");profile.remove("mca_compression");
        var local=data.materials();var palette=new int[local.length];
        for(int i=0;i<local.length;i++)palette[i]=materials.computeIfAbsent(local[i],ignored->materials.size());
        entry.putIntArray("material_palette",palette);
        entry.putIntArray("biome_palette",data.biomes().stream().mapToInt(b->biomes.computeIfAbsent(
                b.unwrapKey().orElseThrow().identifier().toString(),ignored->biomes.size())).toArray());
        entry.put("profile",ProfileNbt.encode(profile));dimensions.put(key,entry);
    }

    CompoundTag build() {
        var shared=new CompoundTag();var states=new ListTag();
        for(var state:materials.keySet()) {
            var encoded=BlockState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,state).getOrThrow();
            var entry=new CompoundTag();entry.putString("id",encoded.isJsonPrimitive()?encoded.getAsString():encoded.getAsJsonObject().get("id").getAsString());
            var properties=new CompoundTag();
            if(encoded.isJsonObject()&&encoded.getAsJsonObject().has("properties"))
                encoded.getAsJsonObject().getAsJsonObject("properties").entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(p->properties.putString(p.getKey(),p.getValue().getAsString()));
            entry.put("properties",properties);states.add(entry);
        }
        shared.put("materials",states);var names=new ListTag();biomes.keySet().forEach(id->names.add(StringTag.valueOf(id)));
        shared.put("biomes",names);root.put("shared_types",shared);root.put("dimensions",dimensions);
        return root;
    }
}
