package art.arcane.retina.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseSettings;

/** Storage, density volume, and WorldGenerationContext are distinct vertical ranges. */
record GenerationBounds(int storageMinY, int storageHeight, int minY, int height,
                        int terrainMinY, int terrainHeight) {
    static GenerationBounds noise(NoiseSettings noise, int storageMinY, int storageHeight) {
        var clipped = noise.clampToHeightAccessor(LevelHeightAccessor.create(storageMinY, storageHeight));
        // Match WorldGenerationContext for anchors, including offset/custom dimensions.
        // Its depth is the smaller height, not the intersection used by density filling.
        return new GenerationBounds(storageMinY, storageHeight,
                Math.max(storageMinY, noise.minY()), Math.min(storageHeight, noise.height()),
                clipped.minY(), Math.max(0, clipped.height()));
    }

    static GenerationBounds uniform(int minY, int height) {
        return new GenerationBounds(minY, height, minY, height, minY, height);
    }

    JsonObject describe() {
        var data = new JsonObject();
        data.addProperty("storage_min_y", storageMinY);
        data.addProperty("storage_height", storageHeight);
        data.addProperty("min_y", minY);
        data.addProperty("height", height);
        data.addProperty("terrain_min_y", terrainMinY);
        data.addProperty("terrain_height", terrainHeight);
        return data;
    }

    /** Resolve only a height-provider tree, never arbitrary feature/template NBT. */
    void resolveHeightProvider(JsonElement value, int seaLevel) {
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> resolveHeightProvider(child, seaLevel));
        } else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.size() == 1) {
                if (object.has("above_bottom")) {
                    object.addProperty("absolute", minY + object.remove("above_bottom").getAsInt());
                } else if (object.has("below_top")) {
                    object.addProperty("absolute", minY + height - 1 - object.remove("below_top").getAsInt());
                } else if (object.has("relative_to_sea_level")) {
                    object.addProperty("absolute", seaLevel + object.remove("relative_to_sea_level").getAsInt());
                }
            }
            object.entrySet().forEach(entry -> resolveHeightProvider(entry.getValue(), seaLevel));
        }
    }
}
