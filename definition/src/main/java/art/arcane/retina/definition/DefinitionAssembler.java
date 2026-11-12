package art.arcane.retina.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the versioned structural document after the game adapter resolves worldgen. */
public final class DefinitionAssembler<T, C extends T, L extends T> {
    public static final int FORMAT_VERSION = 2;
    public static final int GENERATION_VERSION = 1;

    private final DefinitionTags<T, C, L> tags;
    private final C root;
    private final C dimensions;
    private final Map<Material, Integer> materials = new LinkedHashMap<>();
    private final Map<String, Integer> biomes = new LinkedHashMap<>();

    public DefinitionAssembler(DefinitionTags<T, C, L> tags, String minecraftVersion, int dataVersion,
                               String exporter, long seed, boolean structures, boolean bonusChest, List<String> packs) {
        this.tags = tags;
        root = tags.compound();
        dimensions = tags.compound();
        tags.put(root, "format_version", tags.intTag(FORMAT_VERSION));
        tags.put(root, "generation_version", tags.intTag(GENERATION_VERSION));
        tags.put(root, "minecraft_version", tags.string(minecraftVersion));
        tags.put(root, "data_version", tags.intTag(dataVersion));
        tags.put(root, "exporter", tags.string(exporter));
        tags.put(root, "seed", tags.longTag(seed));
        tags.put(root, "generate_structures", tags.byteTag(structures ? (byte) 1 : (byte) 0));
        tags.put(root, "bonus_chest", tags.byteTag(bonusChest ? (byte) 1 : (byte) 0));
        L datapacks = tags.list();
        for (String pack : packs) {
            C entry = tags.compound();
            tags.put(entry, "id", tags.string(pack));
            tags.add(datapacks, entry);
        }
        tags.put(root, "datapacks", datapacks);
    }

    public void unsupported(String key, C parameters, String reason) {
        C entry = tags.compound();
        tags.put(entry, "parameters", tags.copy(parameters));
        tags.put(entry, "unsupported_reason", tags.string(reason));
        tags.put(entry, "material_palette", tags.ints(new int[0]));
        tags.put(entry, "biome_palette", tags.ints(new int[0]));
        tags.put(dimensions, key, entry);
    }

    /** Models contain canonical block IDs and properties. Runtime identity binding is separate. */
    public void supported(String key, C parameters, JsonObject model, JsonObject generation) {
        C entry = tags.compound();
        C saved = tags.copy(parameters);
        tags.put(saved, "sea_level", tags.intTag(model.get("sea_level").getAsInt()));
        tags.put(entry, "parameters", saved);

        // Only the envelope changes. Encoding reads the nested programs and templates directly.
        var profile = new JsonObject();
        model.entrySet().forEach(field -> profile.add(field.getKey(), field.getValue()));
        profile.addProperty("dimension", key);
        if (generation != null) profile.add("generation", generation.deepCopy());

        var local = profile.getAsJsonArray("materials");
        var palette = new int[local.size()];
        for (int i = 0; i < local.size(); i++) {
            var material = Material.decode(local.get(i));
            palette[i] = materials.computeIfAbsent(material, ignored -> materials.size());
        }
        tags.put(entry, "material_palette", tags.ints(palette));
        int[] biomePalette = profile.getAsJsonArray("biomes").asList().stream().mapToInt(biome -> {
            String id = biome.getAsJsonObject().get("id").getAsString();
            return biomes.computeIfAbsent(id, ignored -> biomes.size());
        }).toArray();
        tags.put(entry, "biome_palette", tags.ints(biomePalette));
        profile.remove("materials");
        profile.remove("mca_compression");
        profile.remove("program_execution");
        tags.put(entry, "profile", ProfileEncoding.encode(profile, tags));
        tags.put(dimensions, key, entry);
    }

    public C build() {
        C shared = tags.compound();
        L states = tags.list();
        for (Material material : materials.keySet()) {
            C entry = tags.compound();
            tags.put(entry, "id", tags.string(material.id()));
            C properties = tags.compound();
            material.properties().forEach((key, value) -> tags.put(properties, key, tags.string(value)));
            tags.put(entry, "properties", properties);
            tags.add(states, entry);
        }
        tags.put(shared, "materials", states);
        L names = tags.list();
        biomes.keySet().forEach(id -> tags.add(names, tags.string(id)));
        tags.put(shared, "biomes", names);
        tags.put(root, "shared_types", shared);
        tags.put(root, "dimensions", dimensions);
        return root;
    }

    private record Material(String id, Map<String, String> properties) {
        static Material decode(JsonElement encoded) {
            if (encoded.isJsonPrimitive()) return new Material(encoded.getAsString(), Map.of());
            var value = encoded.getAsJsonObject();
            var properties = new TreeMap<String, String>();
            if (value.has("properties")) {
                value.getAsJsonObject("properties").entrySet()
                        .forEach(property -> properties.put(property.getKey(), property.getValue().getAsString()));
            }
            return new Material(value.get("id").getAsString(), Collections.unmodifiableMap(properties));
        }
    }
}
