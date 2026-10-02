package art.arcane.retina.worldgen;

import art.arcane.retina.Retina;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.material.condition.*;
import net.minecraft.world.level.levelgen.material.rule.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** One-time projection of the world's actual registries into a resident GPU profile.
 * Height styles are Retina approximations; vanilla does not store a biome height parameter.
 * Surface rules are probed at representative land/subsurface/seabed positions, without
 * evaluating vanilla's density router or per-column material noise on the CPU.
 */
public record BiomeTerrainProfile(int nativeId, int seaLevel, BlockState[] materials, List<Holder<Biome>> biomes, String json) {
    public static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height) {
        return load(registry, source, minY, height, 0L);
    }
    public static BiomeTerrainProfile load(HolderLookup.Provider registry, RetinaBiomeSource source, int minY, int height, long seed) {
        source.underground(List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK).stream()
                .map(key -> (Holder<Biome>) registry.lookupOrThrow(Registries.BIOME).getOrThrow(key)).toList());
        var nativeBiomes = source.nativeBiomes();
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        int sea = settings.seaLevel();
        if (sea < minY || sea > minY + height) throw new IllegalArgumentException("Registry sea level is outside Retina's generation bounds");
        var materials = new LinkedHashMap<BlockState, Integer>();
        var profile = new JsonObject();
        profile.addProperty("biome_scale", source.scale());
        profile.addProperty("blend", source.blend());
        profile.addProperty("sea_level", sea);
        material(materials, Blocks.AIR.defaultBlockState());
        profile.addProperty("stone", material(materials, settings.defaultBlock()));
        profile.addProperty("water", material(materials, settings.defaultFluid()));
        profile.addProperty("bedrock", material(materials, Blocks.BEDROCK.defaultBlockState()));
        profile.addProperty("deepslate", material(materials, Blocks.DEEPSLATE.defaultBlockState()));
        profile.addProperty("snow", material(materials, Blocks.SNOW_BLOCK.defaultBlockState()));
        profile.addProperty("ice", material(materials, Blocks.ICE.defaultBlockState()));
        var parameters = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters().values();
        var biomes = new JsonArray();
        for (var biome : nativeBiomes) {
            String id = biome.unwrapKey().orElseThrow().identifier().toString();
            var entry = new JsonObject();
            entry.addProperty("id", id);
            double[] climate = new double[4];
            int count = 0;
            for (var pair : parameters) {
                if (!pair.getSecond().equals(biome)) continue;
                var point = pair.getFirst();
                var targets = List.of(point.temperature(), point.humidity(), point.continentalness(), point.erosion());
                for (int i = 0; i < 4; i++) climate[i] += Climate.unquantizeCoord((targets.get(i).min() + targets.get(i).max()) / 2);
                count++;
            }
            if (count == 0) {
                // Datapack biomes absent from the overworld climate list still use registered climate.
                var data = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome.value()).getOrThrow().getAsJsonObject();
                climate = new double[]{Math.clamp(biome.value().getBaseTemperature() - 0.5, -1, 1),
                        data.get("downfall").getAsDouble() * 2 - 1, 0.2, 0.0};
            } else for (int i = 0; i < 4; i++) climate[i] /= count;
            entry.add("climate", array(climate));
            entry.add("terrain", array(terrain(id)));
            var land = new Probe(biome, sea + 5, 1, false, sea, minY, height);
            var below = new Probe(biome, sea + 3, 3, false, sea, minY, height);
            var seabed = new Probe(biome, sea - 8, 1, true, sea, minY, height);
            var root = settings.materialRule().value();
            entry.addProperty("top", material(materials, surface(root, land, Blocks.GRASS_BLOCK.defaultBlockState())));
            entry.addProperty("filler", material(materials, surface(root, below, Blocks.DIRT.defaultBlockState())));
            entry.addProperty("underwater", material(materials, surface(root, seabed, Blocks.GRAVEL.defaultBlockState())));
            int flags = biome.value().getBaseTemperature() < 0.15F ? 1 : 0;
            if (id.contains("windswept") || id.contains("peak") || id.contains("slopes")) flags |= 2;
            if (id.contains("ocean")) flags |= 4;
            if (id.contains("badlands")) flags |= 8;
            if (List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK).stream().anyMatch(biome::is)) flags |= 16;
            entry.addProperty("flags", flags);
            biomes.add(entry);
        }
        profile.add("biomes", biomes);
        var noises = new JsonArray();
        for (String id : List.of("temperature", "vegetation", "continentalness", "erosion")) {
            var noise = registry.lookupOrThrow(Registries.NOISE)
                    .getOrThrow(ResourceKey.create(Registries.NOISE, Identifier.withDefaultNamespace(id))).value();
            var data = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, noise).getOrThrow().getAsJsonObject();
            var entry = new JsonObject();
            entry.addProperty("frequency", Math.scalb(1.0, data.get("base_octave").getAsInt()));
            entry.addProperty("amplitude", (data.has("base_amplitude") ? data.get("base_amplitude").getAsDouble() : 1.0));
            int count = data.has("octave_count") ? data.get("octave_count").getAsInt() : 1;
            var modifiers = new JsonArray();
            var supplied = data.getAsJsonArray("amplitude_modifiers");
            for (int i = 0; i < count; i++) modifiers.add(supplied != null && i < supplied.size() ? supplied.get(i).getAsDouble() : 1.0);
            entry.add("modifiers", modifiers);
            noises.add(entry);
        }
        profile.add("noises", noises);
        profile.add("decorations", DecorationProfile.export(registry, nativeBiomes, biomes, materials));
        TerrainFeatureProfile.export(registry, nativeBiomes, biomes, materials, profile, settings.materialRule().value(), seed);
        GeologyProfile.export(registry, nativeBiomes, biomes, materials, profile, minY, height, sea);
        DecorationProfile.materialFlags(profile, materials);
        var palette = new JsonArray();
        for (var state : materials.keySet()) palette.add(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow());
        profile.add("materials", palette);
        String json = profile.toString();
        int nativeId = NativeTerrain.instance().registerProfile(json);
        Retina.LOGGER.info("GPU biome profile {}: {} biomes, {} block states, sea level {}, {} bytes uploaded once", nativeId, biomes.size(), materials.size(), sea, json.length());
        if (Boolean.getBoolean("retina.qa")) Retina.LOGGER.info("QA_EVT {\"event\":\"registry_biome_profile\",\"status\":\"pass\",\"context\":{\"biomes\":{},\"materials\":{},\"profile\":{}}}", biomes.size(), materials.size(), nativeId);
        return new BiomeTerrainProfile(nativeId, sea, materials.keySet().toArray(BlockState[]::new), nativeBiomes, json);
    }

    private static int material(LinkedHashMap<BlockState, Integer> palette, BlockState state) {
        return palette.computeIfAbsent(state, ignored -> palette.size());
    }
    private static JsonArray array(double[] values) {
        var array = new JsonArray();
        for (double value : values) array.add(value);
        return array;
    }
    private static double[] terrain(String id) {
        if (id.contains("deep_") && id.contains("ocean")) return new double[]{-43, 0.25, 0.65};
        if (id.contains("ocean")) return new double[]{-30, 0.3, 0.65};
        if (id.contains("peak") || id.contains("slopes")) return new double[]{65, 1.8, 0.6};
        if (id.contains("windswept")) return new double[]{42, 1.5, 0.7};
        if (id.contains("badlands")) return new double[]{30, 0.8, 0.7};
        if (id.contains("swamp")) return new double[]{-1, 0.12, 0.7};
        if (id.contains("desert")) return new double[]{6, 0.35, 0.8};
        if (id.contains("savanna")) return new double[]{12, 0.5, 0.8};
        if (id.contains("forest") || id.contains("taiga") || id.contains("jungle")) return new double[]{10, 0.7, 0.9};
        return new double[]{4, 0.45, 0.85};
    }
    private record Probe(Holder<Biome> biome, int y, int stoneDepth, boolean underwater, int sea, int minY, int height) { }
    private static BlockState surface(MaterialRule rule, Probe p, BlockState fallback) {
        var state = evaluate(rule, p);
        return state == null || state.isAir() || !state.getFluidState().isEmpty() ? fallback : state;
    }
    private static BlockState evaluate(MaterialRule rule, Probe p) {
        if (rule instanceof MaterialRule.HolderHolder holder) return evaluate(holder.holder().value(), p);
        if (rule instanceof BlockRule block) return block.resultState();
        if (rule instanceof ConditionRule condition) return test(condition.ifTrue(), p) ? evaluate(condition.thenRun(), p) : null;
        if (rule instanceof SequenceRule sequence) {
            for (var item : sequence.sequence()) {
                var result = evaluate(item, p);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static boolean test(MaterialCondition condition, Probe p) {
        if (condition instanceof MaterialCondition.HolderHolder holder) return test(holder.holder().value(), p);
        if (condition instanceof BiomeCondition biome) return biome.biomes().contains(p.biome);
        if (condition instanceof NotCondition not) return !test(not.target(), p);
        if (condition instanceof StoneDepthCondition stone) return stone.surfaceType() == CaveSurface.FLOOR
                && p.stoneDepth <= 1 + stone.offset() + (stone.addSurfaceDepth() ? 3 : 0) + stone.secondaryDepthRange() / 2;
        if (condition instanceof WaterCondition water) return !p.underwater
                || p.y + (water.addStoneDepth() ? p.stoneDepth : 0) >= p.sea + water.offset() + 3 * water.surfaceDepthMultiplier();
        if (condition instanceof YCondition y) return p.y + (y.addStoneDepth() ? p.stoneDepth : 0) >= anchor(y.anchor(), p) + 3 * y.surfaceDepthMultiplier();
        if (condition instanceof NoiseThresholdCondition noise) return noise.minThreshold() <= 0 && noise.maxThreshold() >= 0;
        if (condition instanceof TemperatureCondition) return p.biome.value().getBaseTemperature() < 0.15F;
        if (condition instanceof AbovePreliminarySurfaceCondition) return true;
        if (condition instanceof VerticalGradientCondition gradient) return p.y <= anchor(gradient.trueAtAndBelow(), p);
        return false;
    }
    private static int anchor(VerticalAnchor anchor, Probe p) {
        return switch (anchor) {
            case VerticalAnchor.Absolute a -> a.y();
            case VerticalAnchor.AboveBottom a -> p.minY + a.offset();
            case VerticalAnchor.BelowTop a -> p.minY + p.height - 1 - a.offset();
            case VerticalAnchor.RelativeToSeaLevel a -> p.sea + a.offset();
            default -> throw new IllegalArgumentException("Unknown surface anchor " + anchor);
        };
    }
}
