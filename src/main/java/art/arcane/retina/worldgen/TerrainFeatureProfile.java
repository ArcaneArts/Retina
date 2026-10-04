package art.arcane.retina.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.TagKey;
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
                       LinkedHashMap<BlockState,Integer> palette, JsonObject world, MaterialRule materialRule, long seed, List<com.mojang.datafixers.util.Pair<Climate.ParameterPoint, Holder<Biome>>> parameters) {
        var ops = registry.createSerializationContext(JsonOps.INSTANCE);
        var terrain = new JsonObject();
        var bands = new JsonArray();
        // MaterialSystem's 26.3 band recipe. The world-seeded table is generated once;
        // its spatial offset is evaluated on the GPU using the registered noise below.
        if (hasBands(materialRule)) for (var state : bands(seed)) bands.add(material(palette, state));
        terrain.add("bands", bands);
        terrain.addProperty("snow_layer",material(palette,Blocks.SNOW.defaultBlockState()));
        var noise = registry.lookupOrThrow(Registries.NOISE).getOrThrow(ResourceKey.create(Registries.NOISE,
                Identifier.withDefaultNamespace("clay_bands_offset"))).value();
        var data = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, noise).getOrThrow().getAsJsonObject();
        terrain.addProperty("band_frequency", Math.scalb(1.0, data.get("base_octave").getAsInt()));
        terrain.addProperty("band_amplitude", data.has("base_amplitude") ? data.get("base_amplitude").getAsDouble() : 1.0);
        world.add("terrain_features", terrain);
        for (int i = 0; i < biomes.size(); i++) {
            var biome = biomes.get(i); var entry = entries.get(i).getAsJsonObject();
            boolean underground = (entry.get("flags").getAsInt() & 16) != 0;
            int kind = biome.is(Biomes.LUSH_CAVES) ? 1 : biome.is(Biomes.DRIPSTONE_CAVES) ? 2 : biome.is(Biomes.DEEP_DARK) ? 3 : underground ? 4 : 0;
            entry.addProperty("cave_kind", kind);
            double depthMin = 2, depthMax = -2;
            for (var pair : parameters) if (pair.getSecond().equals(biome)) {
                depthMin = Math.min(depthMin, Climate.unquantizeCoord(pair.getFirst().depth().min()));
                depthMax = Math.max(depthMax, Climate.unquantizeCoord(pair.getFirst().depth().max()));
            }
            entry.add("cave_depth", array(depthMin <= depthMax ? depthMin : 0.2, depthMin <= depthMax ? depthMax : 1));
            double lavaChance = 0, waterChance = 0;boolean snowSurface=false;
            var collector = new Collector(registry, palette);
            for (var step : biome.value().getGenerationSettings().features()) for (var placed : step) {
                snowSurface |= placed.value().feature().value() instanceof SnowAndFreezeFeature;
                if (placed.value().feature().value() instanceof LakeFeature lake) {
                    double rarity = 1;boolean surfaceLake=false;
                    for (var modifier : placed.value().placement()) {
                        var m = PlacementModifier.CODEC.encodeStart(ops, modifier).getOrThrow().getAsJsonObject();
                        if (m.get("type").getAsString().endsWith("rarity_filter")) rarity *= m.get("chance").getAsInt();
                        if (m.get("type").getAsString().endsWith("heightmap")) surfaceLake=true;
                    }
                    if(!surfaceLake)continue;
                    var state = providerState(registry, palette, lake.fluid().value());
                    // One globally aligned candidate per 8x8 chunks represents
                    // the chance that at least one registered chunk attempt runs.
                    double chance=1-Math.pow(1-1/rarity,64);
                    if (state.is(Blocks.LAVA)) {
                        lavaChance = 1-(1-lavaChance)*(1-chance);
                        entry.addProperty("lake_barrier", material(palette, providerState(registry, palette, lake.barrier().value())));
                    } else if(palette.getOrDefault(state,-1)==world.get("water").getAsInt()) {
                        waterChance=1-(1-waterChance)*(1-chance);
                        entry.addProperty("lake_water_barrier", material(palette, providerState(registry, palette, lake.barrier().value())));
                    }
                }
                if (underground)
                    collector.walk(Feature.DIRECT_CODEC.encodeStart(ops, placed.value().feature().value()).getOrThrow(), 0);
            }
            // Surface basins require registered lake recipes. Rainfall alone does
            // not request water lakes in modern vanilla.
            entry.add("lakes", array(underground ? 0 : Math.min(1.0,waterChance+lavaChance), underground ? 0 : lavaChance));
            entry.addProperty("snow_surface",snowSurface && biome.value().hasPrecipitation());
            if(snowSurface)entry.addProperty("flags",entry.get("flags").getAsInt()|32);
            entry.add("cave_features", collector.finish(kind));
        }
    }
    private static final class Collector {
        final HolderLookup.Provider registry;
        final LinkedHashMap<BlockState,Integer> palette;
        final LinkedHashSet<BlockState> states = new LinkedHashSet<>();
        final Set<String> visited = new HashSet<>();
        final Set<String> replaceable = new LinkedHashSet<>();
        int dripMin = 2, dripMax = 8, vineMax = 7;
        double density = 0.5, plants = 0.12;
        boolean sculk;
        Collector(HolderLookup.Provider registry, LinkedHashMap<BlockState,Integer> palette) { this.registry = registry; this.palette = palette; }
        void walk(JsonElement value, int depth) {
            if (depth > 24) throw new IllegalArgumentException("Recursive cave feature graph");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                var block = BlockState.CODEC.parse(JsonOps.INSTANCE, value).result();
                if (block.isPresent()) { states.add(block.get()); return; }
                String id = value.getAsString();
                var key = Identifier.tryParse(id);
                if (key == null || !id.contains(":") || !visited.add(id)) return;
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
            for (String key : List.of("replaceable", "replaceable_blocks")) if (object.has(key)) replacement(object.get(key));
            String type = object.has("type") ? object.get("type").getAsString() : "";
            if (type.endsWith("speleothem_cluster")) {
                double[] heights = range(object.get("height")); dripMin = (int) heights[0]; dripMax = (int) heights[1];
                double[] d = range(object.get("density")); density = (d[0] + d[1]) * 0.5;
            }
            if (type.endsWith("vegetation_patch") && object.has("vegetation_chance")) plants = object.get("vegetation_chance").getAsDouble();
            if (type.endsWith("sculk_patch")) sculk = true;
            for (var child : object.entrySet()) walk(child.getValue(), depth + 1);
        }
        void replacement(JsonElement value) {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) replaceable.add(value.getAsString());
            else if (value.isJsonArray()) for (var child : value.getAsJsonArray()) replacement(child);
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
            if (sculk) { result.addProperty("floor", material(palette, Blocks.SCULK.defaultBlockState())); replaceable.add("#minecraft:sculk_replaceable_world_gen"); }
            var targets = new JsonArray(); for (String target : replaceable) targets.add(target); result.add("replacement_targets", targets);
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
    static void finishReplacementTables(JsonArray biomes, LinkedHashMap<BlockState,Integer> palette) {
        for (var b : biomes) {
            var feature = b.getAsJsonObject().getAsJsonObject("cave_features");
            var table = new JsonArray();
            for (var state : palette.keySet()) {
                boolean match = false;
                for (var value : feature.getAsJsonArray("replacement_targets")) {
                    String id = value.getAsString();
                    match |= id.startsWith("#") ? state.is(TagKey.create(Registries.BLOCK, Identifier.parse(id.substring(1))))
                            : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(Identifier.parse(id));
                }
                table.add(match);
            }
            feature.remove("replacement_targets"); feature.add("replaceable", table);
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
                o.has("max_inclusive") ? o.get("max_inclusive").getAsDouble() : o.has("max_exclusive") ? o.get("max_exclusive").getAsDouble() : o.has("max") ? o.get("max").getAsDouble() : 0};
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
