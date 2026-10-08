package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.List;

/** Rust registration of the shared registry projection, preserving existing callers. */
public record BiomeTerrainProfile(int nativeId, int seaLevel, BlockState[] materials, List<Holder<Biome>> biomes, String json) {
    public static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height) {
        return load(registry, source, minY, height, 0L);
    }
    public static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed) {
        return load(registry, source, minY, height, seed, registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value());
    }
    public static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed, NoiseGeneratorSettings settings) {
        return load(registry,source,minY,height,seed,settings,StructureProfile.Context.NONE);
    }
    static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed, NoiseGeneratorSettings settings, StructureProfile.Context structures) {
        return load(registry,source,minY,height,seed,settings,structures,true);
    }
    static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed, NoiseGeneratorSettings settings, StructureProfile.Context structures, boolean densityComposition) {
        return load(registry,source,minY,height,seed,settings,structures,densityComposition,true);
    }
    static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed, NoiseGeneratorSettings settings, StructureProfile.Context structures, boolean densityComposition, boolean skyLight) {
        return register(TerrainProfileData.export(registry, source, minY, height, seed, settings, structures, densityComposition, skyLight));
    }

    /** Explicit Rust adapter. Bend consumes TerrainProfileData without calling this. */
    public static BiomeTerrainProfile register(TerrainProfileData data) {
        int nativeId = NativeTerrain.instance().registerProfile(data.json());
        var materials = data.materials();
        Retina.LOGGER.info("GPU biome profile {}: {} biomes, {} block states, sea level {}, {} bytes uploaded once",
                nativeId, data.biomes().size(), materials.length, data.seaLevel(), data.json().length());
        if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info("QA_EVT {\"event\":\"registry_biome_profile\",\"status\":\"pass\",\"context\":{\"biomes\":{},\"materials\":{},\"profile\":{}}}",
                data.biomes().size(), materials.length, nativeId);
        return new BiomeTerrainProfile(nativeId, data.seaLevel(), materials, data.biomes(), data.json());
    }
}
