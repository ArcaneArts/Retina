package art.arcane.retina.worldgen;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.List;
import java.util.Map;

/** Compiles static generation rules and explicit seed recipes, never an executable world. */
final class WorldCatalogCompiler {
    static final String NAME = "retina-catalog.dat";
    static final String EXPORTER = "Retina vanilla catalog compiler 1";
    private static final String[] BAND_MATERIALS = {
            "minecraft:terracotta", "minecraft:orange_terracotta", "minecraft:yellow_terracotta",
            "minecraft:brown_terracotta", "minecraft:red_terracotta", "minecraft:white_terracotta",
            "minecraft:light_gray_terracotta"
    };

    private WorldCatalogCompiler() { }

    static CompoundTag compile(HolderLookup.Provider registry, Map<ResourceKey<LevelStem>, LevelStem> stems,
                               StructureProfile.Context structures, List<String> packs) {
        var catalog = new CompoundTag();
        catalog.putInt("catalog_version", 1);
        catalog.putInt("materialization_version", 1);
        var variants = new CompoundTag();
        for (boolean enabled : new boolean[]{true, false}) {
            var template = WorldDefinitionCompiler.compile(registry, new WorldOptions(0L, enabled, false),
                    stems, structures, packs, EXPORTER);
            // Export uses a deterministic capture seed, but every world-derived
            // value is removed and represented by a recipe before publication.
            template.remove("seed");
            var recipes = new CompoundTag();
            var materials = template.getCompoundOrEmpty("shared_types").getListOrEmpty("materials");
            var dimensions = template.getCompoundOrEmpty("dimensions");
            for (String key : dimensions.keySet()) {
                var dimension = dimensions.getCompoundOrEmpty(key);
                var terrain = dimension.getCompoundOrEmpty("profile").getCompoundOrEmpty("terrain_features");
                Tag bands = terrain.get("bands");
                if (bands == null || bands instanceof ListTag list && list.isEmpty()) continue;
                if (!(bands instanceof IntArrayTag || bands instanceof ByteArrayTag))
                    throw new IllegalArgumentException("Unsupported band representation in " + key);
                int count = bands instanceof IntArrayTag ints ? ints.getAsIntArray().length
                        : ((ByteArrayTag) bands).getAsByteArray().length;
                if (count == 0) continue;
                if (count != 192) throw new IllegalArgumentException("Unknown band recipe length in " + key);
                int[] palette = ((IntArrayTag) dimension.get("material_palette")).getAsIntArray();
                int[] ids = new int[BAND_MATERIALS.length];
                for (int wanted = 0; wanted < ids.length; wanted++) {
                    ids[wanted] = -1;
                    for (int local = 0; local < palette.length; local++) {
                        var state = (CompoundTag) materials.get(palette[local]);
                        if (state.getStringOr("id", "").equals(BAND_MATERIALS[wanted])
                                && state.getCompoundOrEmpty("properties").isEmpty()) ids[wanted] = local;
                    }
                    if (ids[wanted] < 0) throw new IllegalArgumentException("Catalog palette lacks " + BAND_MATERIALS[wanted]);
                }
                var recipe = new CompoundTag();
                recipe.putString("algorithm", "retina:badlands_bands/1");
                recipe.putIntArray("materials", ids);
                recipes.put(key, recipe);
                terrain.remove("bands");
            }
            var variant = new CompoundTag();
            variant.put("template", template);
            variant.put("seed_derivations", recipes);
            String name = enabled ? "normal" : "normal-no-structures";
            variants.put(name, variant);
            var bonus = variant.copy();
            bonus.getCompoundOrEmpty("template").putBoolean("bonus_chest", true);
            variants.put(name + "-bonus-chest", bonus);
        }
        // Choose the API delivery policy before materializing a world. Keep the
        // full native/mod variants unchanged. Unselected recipes retain their
        // IDs so every remaining root keeps its original order and salt.
        var bukkit = variants.getCompoundOrEmpty("normal-no-structures").copy();
        var dimensions = bukkit.getCompoundOrEmpty("template").getCompoundOrEmpty("dimensions");
        for (String key : dimensions.keySet()) {
            var profile = dimensions.getCompoundOrEmpty(key).getCompoundOrEmpty("profile");
            var recipes = profile.getListOrEmpty("decorations");
            var removed = new java.util.HashSet<Integer>();
            var omissions = profile.getListOrEmpty("decoration_omissions");
            for (int i = 0; i < recipes.size(); i++) {
                var recipe = ((CompoundTag) recipes.get(i)).getCompoundOrEmpty("v");
                if (requiresScheduledTick(recipe)) {
                    removed.add(i);
                    omissions.add(StringTag.valueOf("bukkit-api:scheduled-spring-root:" + recipe.getStringOr("source", "unknown")));
                }
            }
            profile.put("decoration_omissions", omissions);
            for (var raw : profile.getListOrEmpty("biomes")) {
                var biome = ((CompoundTag) raw).getCompoundOrEmpty("v");
                Tag selected = biome.get("decorations");
                int[] ids;
                if (selected instanceof ByteArrayTag bytes) {
                    byte[] values = bytes.getAsByteArray(); ids = new int[values.length];
                    for (int i = 0; i < values.length; i++) ids[i] = Byte.toUnsignedInt(values[i]);
                } else if (selected instanceof IntArrayTag ints) ids = ints.getAsIntArray();
                else if (selected instanceof ListTag list && list.isEmpty()) ids = new int[0];
                else throw new IllegalArgumentException("Unknown decoration selection representation in " + key);
                biome.putIntArray("decorations", java.util.Arrays.stream(ids).filter(id -> !removed.contains(id)).toArray());
            }
        }
        variants.put("bukkit-api", bukkit);
        catalog.put("variants", variants);
        return catalog;
    }

    private static boolean requiresScheduledTick(Tag value) {
        if (value instanceof CompoundTag compound) {
            if (compound.getStringOr("kind", "").equals("spring")) return true;
            for (String key : compound.keySet()) if (requiresScheduledTick(compound.get(key))) return true;
        } else if (value instanceof ListTag list) {
            for (Tag child : list) if (requiresScheduledTick(child)) return true;
        }
        return false;
    }
}
