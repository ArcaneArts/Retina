package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.material.rule.*;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.*;

/** Small resident feature tables, projected from this world's registered feature graphs. */
final class TerrainFeatureProfile {
    static void export(HolderLookup.Provider registry, List<Holder<Biome>> biomes, JsonArray entries,
                       LinkedHashMap<BlockState,Integer> palette, JsonObject world, MaterialRule materialRule, long seed) {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var terrain = new JsonObject();
        var bands = new JsonArray();
        // MaterialSystem's 26.3 band recipe. The world-seeded table is generated once;
        // its spatial offset is evaluated on the GPU using the registered noise below.
        if (hasBands(materialRule)) for (var state : bands(seed)) bands.add(material(palette, state));
        terrain.add("bands", bands);
        var noise = registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,
                Identifier.withDefaultNamespace("clay_bands_offset"))).value();
        var data = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, noise).getOrThrow().getAsJsonObject();
        terrain.addProperty("band_frequency", Math.scalb(1.0, data.get("base_octave").getAsInt()));
        terrain.addProperty("band_amplitude", data.has("base_amplitude") ? data.get("base_amplitude").getAsDouble() : 1.0);
        world.add("terrain_features", terrain);
        var parameters = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters().values();
        for (int i = 0; i < biomes.size(); i++) {
            var biome = biomes.get(i); var entry = entries.get(i).getAsJsonObject();
            boolean underground = (entry.get("flags").getAsInt() & 16) != 0;
            int kind = biome.is(Biomes.LUSH_CAVES) ? 1 : biome.is(Biomes.DRIPSTONE_CAVES) ? 2 : biome.is(Biomes.DEEP_DARK) ? 3 : 0;
            entry.addProperty("cave_kind", kind);
            double depthMin = 2, depthMax = -2;
            for (var pair : parameters) if (pair.getSecond().equals(biome)) {
                depthMin = Math.min(depthMin, Climate.unquantizeCoord(pair.getFirst().depth().min()));
                depthMax = Math.max(depthMax, Climate.unquantizeCoord(pair.getFirst().depth().max()));
            }
            entry.add("cave_depth", array(depthMin <= depthMax ? depthMin : 0.2, depthMin <= depthMax ? depthMax : 1));
            double lavaChance = 0;
            var collector = new Collector(registry, palette);
            for (var step : biome.value().getGenerationSettings().features()) for (var placed : step) {
                String id = placed.unwrapKey().map(k -> k.identifier().toString()).orElse("inline");
                if (placed.value().feature().value() instanceof LakeFeature lake && id.contains("surface")) {
                    int rarity = 1;
                    for (var modifier : placed.value().placement()) {
                        var m = PlacementModifier.CODEC.encodeStart(ops, modifier).getOrThrow().getAsJsonObject();
                        if (m.get("type").getAsString().endsWith("rarity_filter")) rarity *= m.get("chance").getAsInt();
                    }
                    var state = providerState(registry, palette, lake.fluid().value());
                    if (state.is(Blocks.LAVA)) {
                        lavaChance = Math.min(0.18, 64.0 / rarity);
                        entry.addProperty("lake_barrier", material(palette, providerState(registry, palette, lake.barrier().value())));
                    }
                }
                if (underground && (id.contains("lush") || id.contains("cave_vine") || id.contains("spore") || id.contains("dripstone") || id.contains("sculk")))
                    collector.walk(Feature.DIRECT_CODEC.encodeStart(ops, placed.value().feature().value()).getOrThrow(), 0);
            }
            var climate = Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, biome.value()).getOrThrow().getAsJsonObject();
            double wetness = climate.get("downfall").getAsDouble();
            int flags = entry.get("flags").getAsInt();
            // Modern vanilla registers lava lakes. Water basins are Retina's approximation,
            // using the registered default fluid, seabed material, and biome rainfall.
            entry.add("lakes", array(underground || (flags & (4 | 8)) != 0 ? 0 : 0.15 + wetness * 0.45, underground ? 0 : lavaChance));
            entry.add("cave_features", collector.finish(kind));
        }
    }
    private static final class Collector {
        final HolderLookup.Provider registry;
        final LinkedHashMap<BlockState,Integer> palette;
        final LinkedHashSet<BlockState> states = new LinkedHashSet<>();
        final Set<String> visited = new HashSet<>();
        int dripMin = 2, dripMax = 8, vineMax = 7;
        double density = 0.5, plants = 0.12;
        boolean sculk;
        Collector(HolderLookup.Provider registry, LinkedHashMap<BlockState,Integer> palette) { this.registry = registry; this.palette = palette; }
        void walk(JsonElement value, int depth) {
            if (depth > 24) throw new IllegalArgumentException("Recursive cave feature graph");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String id = value.getAsString();
                if (!id.startsWith("minecraft:") || !visited.add(id)) return;
                var key = Identifier.parse(id);
                var ops = registry.createSerializationContext(JsonOps.INSTANCE);
                var feature = registry.lookupOrThrow(Registries.FEATURE).get(ResourceKey.create(Registries.FEATURE, key));
                if (feature.isPresent()) { walk(Feature.DIRECT_CODEC.encodeStart(ops, feature.get().value()).getOrThrow(), depth + 1); return; }
                var placed = registry.lookupOrThrow(Registries.PLACED_FEATURE).get(ResourceKey.create(Registries.PLACED_FEATURE, key));
                if (placed.isPresent()) { walk(Feature.DIRECT_CODEC.encodeStart(ops, placed.get().value().feature().value()).getOrThrow(), depth + 1); return; }
                var provider = registry.lookupOrThrow(Registries.BLOCK_STATE_PROVIDER).get(ResourceKey.create(Registries.BLOCK_STATE_PROVIDER, key));
                if (provider.isPresent()) walk(BlockStateProvider.DIRECT_CODEC.encodeStart(ops, provider.get().value()).getOrThrow(), depth + 1);
                return;
            }
            if (value.isJsonArray()) { for (var child : value.getAsJsonArray()) walk(child, depth + 1); return; }
            if (!value.isJsonObject()) return;
            var object = value.getAsJsonObject();
            var state = BlockState.CODEC.parse(JsonOps.INSTANCE, object).result();
            if (state.isPresent()) { states.add(state.get()); return; }
            String type = object.has("type") ? object.get("type").getAsString() : "";
            if (type.endsWith("speleothem_cluster")) {
                double[] heights = range(object.get("height")); dripMin = (int) heights[0]; dripMax = (int) heights[1];
                double[] d = range(object.get("density")); density = (d[0] + d[1]) * 0.5;
            }
            if (type.endsWith("vegetation_patch") && object.has("vegetation_chance")) plants = object.get("vegetation_chance").getAsDouble();
            if (type.endsWith("sculk_patch")) sculk = true;
            for (var child : object.entrySet()) walk(child.getValue(), depth + 1);
        }
        JsonObject finish(int kind) {
            var result = new JsonObject();
            var floorPlants = new JsonArray(); var vineBodies = new JsonArray(); var vineTips = new JsonArray();
            BlockState pointed = null;
            for (var state : states) {
                material(palette, state);
                if (state.is(Blocks.MOSS_BLOCK)) result.addProperty("floor", material(palette, state));
                else if (state.is(Blocks.CLAY)) result.addProperty("clay", material(palette, state));
                else if (state.is(Blocks.DRIPSTONE_BLOCK)) result.addProperty("floor", material(palette, state));
                else if (state.is(Blocks.POINTED_DRIPSTONE)) pointed = state;
                else if (state.is(Blocks.CAVE_VINES)) vineTips.add(material(palette, state));
                else if (state.is(Blocks.CAVE_VINES_PLANT)) vineBodies.add(material(palette, state));
                else if (state.is(Blocks.SPORE_BLOSSOM)) result.addProperty("blossom", material(palette, state));
                else if (state.is(Blocks.MOSS_CARPET) || state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA) || state.is(Blocks.SHORT_GRASS)) floorPlants.add(material(palette, state));
            }
            if (sculk) result.addProperty("floor", material(palette, Blocks.SCULK.defaultBlockState()));
            var up = new JsonArray(); var down = new JsonArray();
            if (pointed != null) for (var thickness : List.of(SpeleothemThickness.TIP, SpeleothemThickness.FRUSTUM, SpeleothemThickness.MIDDLE, SpeleothemThickness.BASE)) {
                up.add(material(palette, pointed.setValue(SpeleothemBlock.TIP_DIRECTION, Direction.UP).setValue(SpeleothemBlock.THICKNESS, thickness)));
                down.add(material(palette, pointed.setValue(SpeleothemBlock.TIP_DIRECTION, Direction.DOWN).setValue(SpeleothemBlock.THICKNESS, thickness)));
            }
            result.add("plants", floorPlants); result.add("vine_bodies", vineBodies); result.add("vine_tips", vineTips);
            result.add("drip_up", up); result.add("drip_down", down);
            result.addProperty("drip_min", Math.max(1, dripMin)); result.addProperty("drip_max", Math.min(16, dripMax));
            result.addProperty("density", kind == 2 ? Math.clamp(density, 0, 1) : 0.65);
            result.addProperty("plant_chance", Math.clamp(plants, 0.02, 0.5)); result.addProperty("vine_max", vineMax);
            return result;
        }
    }
    private static BlockState providerState(HolderLookup.Provider registry, LinkedHashMap<BlockState,Integer> palette, BlockStateProvider provider) {
        var collector = new Collector(registry, palette);
        collector.walk(BlockStateProvider.DIRECT_CODEC.encodeStart(registry.createSerializationContext(JsonOps.INSTANCE), provider).getOrThrow(), 0);
        if (collector.states.isEmpty()) throw new IllegalArgumentException("Lake provider contains no exportable block states");
        return collector.states.iterator().next();
    }
    private static double[] range(JsonElement value) {
        if (value == null) return new double[]{0, 0};
        if (value.isJsonPrimitive()) return new double[]{value.getAsDouble(), value.getAsDouble()};
        var o = value.getAsJsonObject();
        return new double[]{o.has("min_inclusive") ? o.get("min_inclusive").getAsDouble() : o.has("min") ? o.get("min").getAsDouble() : 0,
                o.has("max_inclusive") ? o.get("max_inclusive").getAsDouble() : o.has("max") ? o.get("max").getAsDouble() : 0};
    }
    private static boolean hasBands(MaterialRule rule) {
        if (rule instanceof MaterialRule.HolderHolder h) return hasBands(h.holder().value());
        if (rule instanceof BandlandsRule) return true;
        if (rule instanceof ConditionRule c) return hasBands(c.thenRun());
        if (rule instanceof SequenceRule s) return s.sequence().stream().anyMatch(TerrainFeatureProfile::hasBands);
        return false;
    }
    private static BlockState[] bands(long seed) {
        var random = RandomSource.create(seed ^ 0x6a09e667f3bcc909L);
        var bands = new BlockState[192]; Arrays.fill(bands, Blocks.TERRACOTTA.defaultBlockState());
        for (int i = 0; i < bands.length; i++) { i += random.nextInt(5) + 1; if (i < bands.length) bands[i] = Blocks.DYED_TERRACOTTA.orange().defaultBlockState(); }
        for (int color = 0; color < 3; color++) {
            var state = (color == 0 ? Blocks.DYED_TERRACOTTA.yellow() : color == 1 ? Blocks.DYED_TERRACOTTA.brown() : Blocks.DYED_TERRACOTTA.red()).defaultBlockState();
            int count = random.nextIntBetweenInclusive(6, 15);
            for (int i = 0; i < count; i++) { int width = (color == 1 ? 2 : 1) + random.nextInt(3), start = random.nextInt(bands.length);
                for (int p = 0; p < width && start + p < bands.length; p++) bands[start + p] = state; }
        }
        int count = random.nextIntBetweenInclusive(9, 15), start = 0;
        for (int i = 0; i < count && start < bands.length; i++, start += random.nextInt(16) + 4) {
            bands[start] = Blocks.DYED_TERRACOTTA.white().defaultBlockState();
            if (start - 1 > 0 && random.nextBoolean()) bands[start - 1] = Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState();
            if (start + 1 < bands.length && random.nextBoolean()) bands[start + 1] = Blocks.DYED_TERRACOTTA.lightGray().defaultBlockState();
        }
        return bands;
    }
    private static int material(LinkedHashMap<BlockState,Integer> palette, BlockState state) { return palette.computeIfAbsent(state, ignored -> palette.size()); }
    private static JsonArray array(double... values) { var a = new JsonArray(); for (double v : values) a.add(v); return a; }
}
